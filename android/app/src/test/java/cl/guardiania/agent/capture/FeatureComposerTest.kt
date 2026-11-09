package cl.guardiania.agent.capture

import cl.guardiania.agent.data.CaptureVector
import cl.guardiania.agent.data.LocalSignal
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class FeatureComposerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val deviceId = "9d8f4b7a-0f1b-4a4e-8f2a-7f2c1b8d3a11"

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun signal(id: Long, category: String, flags: List<String>, iso: String) =
        LocalSignal(id = id, category = category, flags = flags, createdAt = at(iso))

    @Test
    fun `signals in one five minute bucket and category become one deterministic capture`() {
        val now = at("2026-10-22T10:30:00Z")
        val signals = listOf(
            signal(1, "social", listOf("gambling"), "2026-10-22T10:05:00Z"),
            signal(2, "social", listOf("gambling", "drugs"), "2026-10-22T10:06:00Z"),
            signal(3, "social", emptyList(), "2026-10-22T10:07:00Z"),
        )

        val first = FeatureComposer.composeSignals(deviceId, signals, now, 0, "1.0.0")
        val second = FeatureComposer.composeSignals(deviceId, signals, now, 0, "1.0.0")

        assertEquals(1, first.size)
        assertEquals(first.single().eventId, second.single().eventId)
        assertTrue(UUID.fromString(first.single().eventId).toString().isNotBlank())

        val vector = json.decodeFromString<CaptureVector>(first.single().payload)
        assertEquals(deviceId, vector.deviceId)
        assertEquals("social", vector.category)
        assertEquals(listOf("gambling", "drugs"), vector.flags)
        assertEquals(3, vector.notificationCount)
        assertEquals(3, vector.eventCount)
        assertEquals(3, vector.lexicalFlagCount)
        assertEquals(0, vector.usageDurationMinutes)
        assertTrue(vector.lexicalFlagCount >= vector.flags.size)
        assertEquals("2026-10-22T10:05:00Z", vector.timeWindow.start)
    }

    @Test
    fun `signals in different five minute buckets are not merged`() {
        val now = at("2026-10-22T11:15:00Z")
        val signals = listOf(
            signal(1, "video", emptyList(), "2026-10-22T10:04:00Z"),
            signal(2, "video", listOf("violence"), "2026-10-22T10:06:00Z"),
        )

        val captures = FeatureComposer.composeSignals(deviceId, signals, now, 0, "1.0.0")

        assertEquals(2, captures.size)
        assertNotEquals(captures[0].eventId, captures[1].eventId)
        captures.forEach { capture ->
            val vector = json.decodeFromString<CaptureVector>(capture.payload)
            val start = Instant.parse(vector.timeWindow.start).toEpochMilli()
            val end = Instant.parse(vector.timeWindow.end).toEpochMilli()
            assertTrue(end > start)
            assertTrue(end - start <= 5L * 60L * 1000L)
        }
    }

    @Test
    fun `same category after five minutes becomes a new capture`() {
        val now = at("2026-10-22T10:30:00Z")
        val signals = listOf(
            signal(1, "mensajeria", listOf("threat"), "2026-10-22T10:05:00Z"),
            signal(2, "mensajeria", listOf("threat"), "2026-10-22T10:11:00Z"),
        )

        val captures = FeatureComposer.composeSignals(deviceId, signals, now, 0, "1.0.0")

        assertEquals(2, captures.size)
        assertNotEquals(captures[0].eventId, captures[1].eventId)
        val vectors = captures.map { json.decodeFromString<CaptureVector>(it.payload) }
        assertTrue(vectors.all { it.flags == listOf("threat") })
        assertTrue(vectors.all { it.notificationCount == 1 })
    }

    @Test
    fun `signals older than seven days are excluded`() {
        val now = at("2026-10-22T10:30:00Z")
        val signals = listOf(
            signal(1, "social", listOf("gambling"), "2026-10-13T10:00:00Z"),
            signal(2, "social", listOf("drugs"), "2026-10-22T10:10:00Z"),
        )

        val captures = FeatureComposer.composeSignals(deviceId, signals, now, 0, "1.0.0")

        assertEquals(1, captures.size)
        val vector = json.decodeFromString<CaptureVector>(captures.single().payload)
        assertEquals(listOf("drugs"), vector.flags)
    }

    @Test
    fun `different categories in the same five minute bucket stay separate`() {
        val now = at("2026-10-22T10:30:00Z")
        val signals = listOf(
            signal(1, "social", listOf("gambling"), "2026-10-22T10:05:00Z"),
            signal(2, "mensajeria", listOf("bullying"), "2026-10-22T10:06:00Z"),
        )

        val captures = FeatureComposer.composeSignals(deviceId, signals, now, 0, "1.0.0")

        assertEquals(2, captures.size)
        val categories = captures.map {
            json.decodeFromString<CaptureVector>(it.payload).category
        }.toSet()
        assertEquals(setOf("social", "mensajeria"), categories)
    }

    @Test
    fun `usage capture is deterministic and reports only aggregates`() {
        val start = at("2026-10-22T09:00:00Z")
        val end = at("2026-10-22T10:00:00Z")

        val first = FeatureComposer.composeUsage(deviceId, 47, start, end, 0, "1.0.0")
        val second = FeatureComposer.composeUsage(deviceId, 47, start, end, 0, "1.0.0")

        assertEquals(first.eventId, second.eventId)
        val vector = json.decodeFromString<CaptureVector>(first.payload)
        assertEquals("uso", vector.category)
        assertEquals(47, vector.usageDurationMinutes)
        assertEquals(0, vector.notificationCount)
        assertTrue(vector.flags.isEmpty())
        assertEquals("2026-10-22T09:00:00Z", vector.timeWindow.start)
        assertEquals("2026-10-22T10:00:00Z", vector.timeWindow.end)
    }
}
