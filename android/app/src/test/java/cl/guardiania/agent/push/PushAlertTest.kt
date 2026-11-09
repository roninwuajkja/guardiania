package cl.guardiania.agent.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushAlertTest {

    @Test
    fun `riesgo se traduce al evento del panel`() {
        val event = PushAlert.from(
            mapOf(
                "tipo" to "risk_alert",
                "alert_id" to "alert-1",
                "severidad" to "ALTA",
                "categoria" to "Ciberbullying",
                "device_alias" to "Sofía",
            ),
        )

        assertEquals("alert-1", event?.alertId)
        assertEquals("ALTA", event?.severity)
        assertEquals("Ciberbullying", event?.category)
        assertEquals("Sofía", event?.deviceAlias)
        assertEquals("risk_alert", event?.kind)
    }

    @Test
    fun `push de prueba no refresca el panel`() {
        assertNull(PushAlert.from(mapOf("tipo" to "test")))
    }

    @Test
    fun `alias vacío usa la etiqueta del menor`() {
        val event = PushAlert.from(
            mapOf(
                "tipo" to "risk_alert",
                "device_alias" to " ",
                "etiqueta_menor" to "Matías",
            ),
        )

        assertEquals("Matías", event?.deviceAlias)
    }

    @Test
    fun `sin alias el evento queda sin nombre`() {
        val event = PushAlert.from(
            mapOf(
                "tipo" to "risk_alert",
                "device_alias" to "",
                "etiqueta_menor" to "",
            ),
        )

        assertNull(event?.deviceAlias)
    }
}
