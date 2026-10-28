package cl.guardiania.agent.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SanitizerTest {

    @Test
    fun `gambling signal is detected from a social app`() {
        val result = Sanitizer.sanitize(
            "com.instagram.android",
            "Mira esto",
            "Hay una apuesta en el casino, entra a la ruleta",
        )

        assertEquals("social", result.category)
        assertTrue(result.flags.contains("gambling"))
    }

    @Test
    fun `self harm phrase with accents is normalized`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            "Sofía",
            "A veces siento que quiero morír, no sé qué hacer",
        )

        assertEquals("mensajeria", result.category)
        assertTrue(result.flags.contains("self_harm"))
    }

    @Test
    fun `grooming secret phrase is flagged`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            "Desconocido",
            "No le digas a tus papás, guardemos el secreto entre nosotros",
        )

        assertTrue(result.flags.contains("grooming"))
    }

    @Test
    fun `multiple rules are returned in a stable order`() {
        val result = Sanitizer.sanitize(
            "com.discord",
            null,
            "Quiero morir y además hay drogas y apuestas",
        )

        assertEquals(listOf("self_harm", "drugs", "gambling"), result.flags)
    }

    @Test
    fun `harmless study message has no flags`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            "Matías",
            "Nos vemos a las 6 para estudiar, lleva el cuaderno",
        )

        assertEquals("mensajeria", result.category)
        assertTrue(result.flags.isEmpty())
    }

    @Test
    fun `unknown packages fall back to otras`() {
        assertEquals("otras", Sanitizer.categoryFor("com.example.unknown"))
    }

    @Test
    fun `empty notification produces an empty result`() {
        val result = Sanitizer.sanitize("com.android.chrome", null, null)

        assertEquals("navegacion", result.category)
        assertTrue(result.flags.isEmpty())
        assertFalse(result.flags.contains(""))
    }
}
