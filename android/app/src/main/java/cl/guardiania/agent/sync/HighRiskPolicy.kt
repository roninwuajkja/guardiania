package cl.guardiania.agent.sync

import cl.guardiania.agent.data.CaptureVector

/**
 * Decide qué vectores merecen una evaluación de IA inmediata.
 *
 * Solo se adelanta la evaluación de señales MEDIA/ALTA; el resto viaja en el
 * lote normal para no gastar presupuesto de IA en eventos benignos. Si la
 * evaluación falla por red, el vector vuelve al lote y no se pierde.
 */
object HighRiskPolicy {

    /** Señales que el motor de reglas clasifica como MEDIA o superior. */
    val HIGH_RISK_FLAGS: Set<String> = setOf(
        "self_harm",
        "grooming",
        "sexual_content",
        "violence",
        "bullying",
        "drugs",
        "gambling",
        "unknown_contact",
    )

    fun shouldEvaluate(vector: CaptureVector): Boolean =
        vector.flags.any { it in HIGH_RISK_FLAGS }
}
