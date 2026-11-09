package cl.guardiania.agent.sync

import cl.guardiania.agent.capture.FeatureComposer
import cl.guardiania.agent.capture.UsageCollector
import cl.guardiania.agent.data.CaptureVector
import cl.guardiania.agent.data.DeviceSession
import cl.guardiania.agent.data.DeviceStatusReporter
import cl.guardiania.agent.data.NotPairedException
import cl.guardiania.agent.data.SyncRequest
import cl.guardiania.agent.data.SecureStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.time.Instant
import java.util.TimeZone
import java.util.UUID

data class SyncOutcome(
    val accepted: Int,
    val duplicates: Int,
    val rejected: Int,
    val pending: Int,
    val aiEvaluated: Int = 0,
    val aiAlerts: Int = 0,
    val warning: String? = null,
)

class SyncEngine(
    private val store: SecureStore,
    private val session: DeviceSession,
    private val usage: UsageCollector,
    private val appVersion: String,
    private val statusReporter: DeviceStatusReporter? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    suspend fun syncNow(): SyncOutcome {
        val pairing = store.pairing() ?: throw NotPairedException()
        // El estado de permisos se reporta en cada sync, aunque no haya capturas.
        runCatching { statusReporter?.report() }
        val now = System.currentTimeMillis()
        store.pruneSignals(now - MAX_AGE_MS)

        val signals = store.pendingSignals(MAX_SIGNALS_PER_SYNC)
        if (signals.isNotEmpty()) {
            val composed = FeatureComposer.composeSignals(
                deviceId = pairing.deviceId,
                signals = signals,
                now = now,
                tzOffsetMinutes = offsetMinutes(now),
                appVersion = appVersion,
            )
            store.commitComposed(composed)
        }

        captureCompletedUsageHour(pairing.deviceId, pairing.linkedAt, now)

        val pending = store.pendingCaptures(MAX_CAPTURES_PER_BATCH)
        if (pending.isEmpty()) {
            markSynced(now)
            return SyncOutcome(0, 0, 0, store.pendingCount())
        }

        val vectors = mutableListOf<CaptureVector>()
        val undecodable = mutableListOf<String>()
        pending.forEach { capture ->
            runCatching { json.decodeFromString<CaptureVector>(capture.payload) }
                .onSuccess(vectors::add)
                .onFailure { undecodable += capture.eventId }
        }
        store.markCapturesSynced(undecodable)

        if (vectors.isEmpty()) {
            markSynced(now)
            return SyncOutcome(0, 0, undecodable.size, store.pendingCount())
        }

        val request = SyncRequest(
            batchId = UUID.randomUUID().toString(),
            deviceClock = Instant.ofEpochMilli(now).toString(),
            captures = emptyList(),
        )

        // Las señales MEDIA/ALTA se evalúan una a una para que el tutor reciba
        // la alerta sin esperar al lote. Si la evaluación falla por red, el
        // vector vuelve al sync normal y no se pierde.
        val evaluatedIds = mutableSetOf<String>()
        var aiEvaluated = 0
        var aiAlerts = 0
        vectors.filter(HighRiskPolicy::shouldEvaluate)
            .take(MAX_AI_EVALUATIONS)
            .forEach { vector ->
                try {
                    val result = session.authorized { api, token ->
                        api.evaluate("Bearer $token", vector)
                    }
                    evaluatedIds += vector.eventId
                    aiEvaluated += 1
                    if (result.alertId != null) aiAlerts += 1
                } catch (error: HttpException) {
                    if (error.code() in setOf(401, 403, 410)) throw error
                } catch (error: java.io.IOException) {
                    // Sin red para esta evaluación: se reintenta dentro del lote.
                }
            }

        val batch = vectors.filterNot { it.eventId in evaluatedIds }
        val response = if (batch.isEmpty()) {
            null
        } else {
            try {
                session.authorized { api, token ->
                    api.sync("Bearer $token", request.copy(captures = batch))
                }
            } catch (error: HttpException) {
                if (isPermanentClientError(error.code())) {
                    store.markCapturesSynced(pending.map { it.eventId })
                    markSynced(System.currentTimeMillis())
                    return SyncOutcome(
                        accepted = evaluatedIds.size,
                        duplicates = 0,
                        rejected = batch.size,
                        pending = store.pendingCount(),
                        aiEvaluated = aiEvaluated,
                        aiAlerts = aiAlerts,
                        warning = "El panel rechazó ${batch.size} capturas; se descartaron.",
                    )
                }
                throw error
            }
        }

        store.markCapturesSynced(pending.map { it.eventId })
        markSynced(System.currentTimeMillis())
        return SyncOutcome(
            accepted = (response?.accepted ?: 0) + evaluatedIds.size,
            duplicates = response?.duplicates ?: 0,
            rejected = response?.rejected?.size ?: 0,
            pending = store.pendingCount(),
            aiEvaluated = aiEvaluated,
            aiAlerts = aiAlerts,
        )
    }

    private fun captureCompletedUsageHour(deviceId: String, linkedAt: Long, now: Long) {
        if (!usage.hasAccess()) return
        val offsetMs = offsetMinutes(now) * 60_000L
        val localNow = now + offsetMs
        val currentHourStart = localNow - localNow % HOUR_MS
        val completedStart = currentHourStart - HOUR_MS
        val bucket = completedStart / HOUR_MS
        if (store.usageHour() == bucket.toString()) return

        val start = completedStart - offsetMs
        val end = currentHourStart - offsetMs
        if (end <= linkedAt) return
        val effectiveStart = maxOf(start, linkedAt)
        val minutes = usage.minutesBetween(effectiveStart, end)
        if (minutes > 0) {
            val capture = FeatureComposer.composeUsage(
                deviceId = deviceId,
                minutes = minutes,
                windowStart = effectiveStart,
                windowEnd = end,
                tzOffsetMinutes = offsetMinutes(effectiveStart),
                appVersion = appVersion,
            )
            store.enqueueCapture(capture.eventId, capture.payload, now)
        }
        store.setUsageHour(bucket.toString())
    }

    private fun markSynced(at: Long) {
        store.setLastSyncAt(at)
        SyncEvents.notify(at)
    }

    private fun offsetMinutes(at: Long): Int = TimeZone.getDefault().getOffset(at) / 60_000

    private fun isPermanentClientError(code: Int): Boolean {
        return code in 400..499 && code !in setOf(401, 403, 410, 429)
    }

    private companion object {
        const val MAX_SIGNALS_PER_SYNC = 200
        const val MAX_CAPTURES_PER_BATCH = 100
        const val MAX_AI_EVALUATIONS = 5
        const val HOUR_MS = 60L * 60L * 1000L
        const val MAX_AGE_MS = 7L * 24L * HOUR_MS
    }
}
