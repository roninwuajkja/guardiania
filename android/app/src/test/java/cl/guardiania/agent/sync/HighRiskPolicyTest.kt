package cl.guardiania.agent.sync

import cl.guardiania.agent.data.CaptureVector
import cl.guardiania.agent.data.TimeWindow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighRiskPolicyTest {

    private fun vector(
        vararg flags: String,
        category: String = "social",
        notifications: Int = 0,
        start: String = "2026-10-28T10:00:00Z",
        tzOffsetMinutes: Int = -180,
    ): CaptureVector = CaptureVector(
        eventId = "event-1",
        deviceId = "device-1",
        timeWindow = TimeWindow(
            start = start,
            end = start,
            tzOffsetMinutes = tzOffsetMinutes,
        ),
        category = category,
        lexicalFlagCount = flags.size,
        flags = flags.toList(),
        notificationCount = notifications,
    )

    @Test
    fun `benign vector is not evaluated individually`() {
        assertFalse(HighRiskPolicy.shouldEvaluate(vector()))
        assertFalse(HighRiskPolicy.shouldEvaluate(vector(category = "video")))
        assertFalse(HighRiskPolicy.shouldEvaluate(vector(notifications = 5)))
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

    @Test
    fun `many notifications in the window are evaluated even without flags`() {
        assertTrue(HighRiskPolicy.shouldEvaluate(vector(notifications = 25)))
    }

    @Test
    fun `night activity with fewer notifications is evaluated`() {
        // 02:00 en Chile (UTC-3): 05:00Z.
        val night = vector(notifications = 10, start = "2026-10-28T05:00:00Z", tzOffsetMinutes = -180)
        assertTrue(HighRiskPolicy.shouldEvaluate(night))

        val day = vector(notifications = 10, start = "2026-10-28T15:00:00Z", tzOffsetMinutes = -180)
        assertFalse(HighRiskPolicy.shouldEvaluate(day))
    }

    @Test
    fun `malformed timestamps do not crash the policy`() {
        assertFalse(HighRiskPolicy.shouldEvaluate(vector(notifications = 3, start = "no-es-fecha")))
    }
}
