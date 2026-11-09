package cl.guardiania.agent.push

import cl.guardiania.agent.realtime.TutorRealtimeAlert

/**
 * Traduce el payload mínimo de FCM al mismo evento que entrega el WebSocket.
 *
 * El push no trae narrativa ni contenido: solo id, severidad, categoría y alias.
 * Devolver `null` deja pasar los pushes de prueba sin refrescar el panel.
 */
object PushAlert {

    fun from(data: Map<String, String>): TutorRealtimeAlert? {
        val kind = data["tipo"]
        if (kind != null && kind != "risk_alert") return null
        return TutorRealtimeAlert(
            alertId = data["alert_id"],
            severity = data["severidad"],
            deviceAlias = data["device_alias"]?.takeIf { it.isNotBlank() }
                ?: data["etiqueta_menor"]?.takeIf { it.isNotBlank() },
            category = data["categoria"],
            kind = "risk_alert",
        )
    }
}
