package cl.guardiania.agent.capture

import cl.guardiania.agent.data.CaptureVector
import cl.guardiania.agent.data.ComposedCapture
import cl.guardiania.agent.data.IntegrityInfo
import cl.guardiania.agent.data.LocalSignal
import cl.guardiania.agent.data.TimeWindow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * Pure aggregation from local sanitized signals to the wire contract.
 * No Android dependencies, so the privacy-critical rules are unit tested on
 * the JVM. Signal captures use 5-minute buckets: a repeated signal of the same
 * category in a later bucket becomes a new event instead of being absorbed by
 * an hourly window. Usage captures keep their hourly bucket.
 */
object FeatureComposer {

    data class UsageCapture(val eventId: String, val payload: String)

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val SIGNAL_BUCKET_MS = 5L * 60L * 1000L
    private const val MAX_AGE_MS = 7L * 24L * HOUR_MS
    private const val MAX_FLAGS = 16

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun composeSignals(
        deviceId: String,
        signals: List<LocalSignal>,
        now: Long,
        tzOffsetMinutes: Int,
        appVersion: String,
    ): List<ComposedCapture> {
        val cutoff = now - MAX_AGE_MS
        val fresh = signals.filter { it.createdAt in cutoff..now }
        return fresh
            .groupBy { it.category to signalBucket(it.createdAt, tzOffsetMinutes) }
            .mapNotNull { (key, group) ->
                val start = group.minOf { it.createdAt }
                val end = minOf(now, maxOf(start + 1_000L, group.maxOf { it.createdAt } + 1_000L))
                if (end <= start) return@mapNotNull null

                val flags = group
                    .flatMap { it.flags }
                    .distinct()
                    .take(MAX_FLAGS)
                val lexicalCount = group.sumOf { it.flags.size }.coerceAtLeast(flags.size).coerceAtMost(64)
                val eventId = UUID.nameUUIDFromBytes(
                    "$deviceId|${key.first}|${key.second}".toByteArray(Charsets.UTF_8),
                ).toString()
                val vector = CaptureVector(
                    eventId = eventId,
                    deviceId = deviceId,
                    timeWindow = TimeWindow(
                        start = Instant.ofEpochMilli(start).toString(),
                        end = Instant.ofEpochMilli(end).toString(),
                        tzOffsetMinutes = tzOffsetMinutes,
                    ),
                    category = key.first,
                    lexicalFlagCount = lexicalCount,
                    flags = flags,
                    usageDurationMinutes = 0,
                    notificationCount = group.size,
                    eventCount = group.size,
                    integrity = IntegrityInfo(attestationOk = true, appVersion = appVersion),
                )
                ComposedCapture(
                    eventId = eventId,
                    payload = json.encodeToString(vector),
                    signalIds = group.map { it.id },
                )
            }
            .sortedBy { it.payload }
    }

    fun composeUsage(
        deviceId: String,
        minutes: Int,
        windowStart: Long,
        windowEnd: Long,
        tzOffsetMinutes: Int,
        appVersion: String,
    ): UsageCapture {
        require(windowEnd > windowStart) { "La ventana debe tener duración positiva" }
        val bucket = hourBucket(windowStart, tzOffsetMinutes)
        val eventId = UUID.nameUUIDFromBytes(
            "$deviceId|uso|$bucket".toByteArray(Charsets.UTF_8),
        ).toString()
        val vector = CaptureVector(
            eventId = eventId,
            deviceId = deviceId,
            timeWindow = TimeWindow(
                start = Instant.ofEpochMilli(windowStart).toString(),
                end = Instant.ofEpochMilli(windowEnd).toString(),
                tzOffsetMinutes = tzOffsetMinutes,
            ),
            category = "uso",
            lexicalFlagCount = 0,
            usageDurationMinutes = minutes.coerceIn(0, 1440),
            integrity = IntegrityInfo(attestationOk = true, appVersion = appVersion),
        )
        return UsageCapture(eventId = eventId, payload = json.encodeToString(vector))
    }

    private fun signalBucket(at: Long, tzOffsetMinutes: Int): Long {
        return Math.floorDiv(at + tzOffsetMinutes * 60_000L, SIGNAL_BUCKET_MS)
    }

    private fun hourBucket(at: Long, tzOffsetMinutes: Int): Long {
        return Math.floorDiv(at + tzOffsetMinutes * 60_000L, HOUR_MS)
    }
}
