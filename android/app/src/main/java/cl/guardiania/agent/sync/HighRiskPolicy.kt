package cl.guardiania.agent.sync

import cl.guardiania.agent.data.CaptureVector
import java.time.Instant
import java.time.ZoneOffset

/**
 * Decide qué vectores merecen una evaluación de IA inmediata.
 *
 * Se adelanta la evaluación de señales MEDIA/ALTA, de ventanas con muchas
 * notificaciones y de las nocturnas con actividad: son los contextos donde la
 * IA aporta más que una regla léxica aislada. El resto viaja en el lote normal.
 * Si la evaluación falla por red, el vector vuelve al lote y no se pierde.
 */
object HighRiskPolicy {

    /** Señales que el motor de reglas clasifica como MEDIA o superior. */
    val HIGH_RISK_FLAGS: Set<String> = setOf(
        "self_harm",
        "grooming",
        "sextortion",
        "abuse",
        "trafficking",
        "radicalization",
        "sexual_content",
        "sexual_harassment",
        "violence",
        "threat",
        "weapons",
        "coercion",
        "bullying",
        "hate_speech",
        "insult_severe",
        "insult_moderate",
        "insult_mild",
        "eating_disorder",
        "doxxing",
        "stalking",
        "runaway",
        "dangerous_challenge",
        "drugs",
        "gambling",
        "malestar",
        "scam_points",
        "scam_casino",
        "scam_financial",
        "scam_account",
        "scam_link",
        "scam_prize",
        "scam_delivery",
        "scam_job",
        "scam_romance",
        "scam",
        "unknown_contact",
    )

    /** Notificaciones en una ventana que ya justifican mirar el contexto. */
    private const val VOLUME_THRESHOLD = 20

    /** Menos notificaciones bastan si ocurren de madrugada. */
    private const val NIGHT_VOLUME_THRESHOLD = 8

    fun shouldEvaluate(vector: CaptureVector): Boolean {
        if (vector.flags.any { it in HIGH_RISK_FLAGS }) return true
        if (vector.notificationCount >= VOLUME_THRESHOLD) return true
        return isNight(vector) && vector.notificationCount >= NIGHT_VOLUME_THRESHOLD
    }

    private fun isNight(vector: CaptureVector): Boolean {
        return runCatching {
            val start = Instant.parse(vector.timeWindow.start)
            val offset = ZoneOffset.ofTotalSeconds(vector.timeWindow.tzOffsetMinutes * 60)
            start.atOffset(offset).hour in 0..5
        }.getOrDefault(false)
    }
}
