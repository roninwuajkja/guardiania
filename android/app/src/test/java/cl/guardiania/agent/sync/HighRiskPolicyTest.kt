package cl.guardiania.agent.sync

import cl.guardiania.agent.data.CaptureVector
import cl.guardiania.agent.data.TimeWindow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighRiskPolicyTest {

    private fun vector(vararg flags: String, category: String = "social"): CaptureVector =
        CaptureVector(
            eventId = "event-1",
            deviceId = "device-1",
            timeWindow = TimeWindow(
                start = "2026-10-28T10:00:00Z",
                end = "2026-10-28T10:15:00Z",
                tzOffsetMinutes = -180,
            ),
            category = category,
            lexicalFlagCount = flags.size,
            flags = flags.toList(),
        )

    @Test
    fun `benign vector is not evaluated individually`() {
        assertFalse(HighRiskPolicy.shouldEvaluate(vector()))
        assertFalse(HighRiskPolicy.shouldEvaluate(vector(category = "video")))
    }

    @Test
    fun `every medium and high signal is evaluated individually`() {
        HighRiskPolicy.HIGH_RISK_FLAGS.forEach { flag ->
            assertTrue("$flag debe adelantar la evaluación", HighRiskPolicy.shouldEvaluate(vector(flag)))
        }
    }

    @Test
    fun `one high signal among benign ones is enough`() {
        assertTrue(HighRiskPolicy.shouldEvaluate(vector("otra_senal", "bullying")))
    }
}
