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
    fun `emotional distress is flagged without alarmism`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            "Sofía",
            "No aguanto más, me siento muy sola y no quiero ir al colegio",
        )

        assertTrue(result.flags.contains("malestar"))
        assertFalse(result.flags.contains("self_harm"))
    }

    @Test
    fun `coercion and pressure are flagged`() {
        val result = Sanitizer.sanitize(
            "com.instagram.android",
            null,
            "Si no me mandas la foto te voy a contar a tus papás",
        )

        assertTrue(result.flags.contains("coercion"))
    }

    @Test
    fun `grooming question about age is flagged`() {
        val result = Sanitizer.sanitize(
            "org.telegram.messenger",
            "Desconocido",
            "¿Qué edad tienes? No le digas a tus papás",
        )

        assertTrue(result.flags.contains("grooming"))
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

    @Test
    fun `sextortion threat publishes intimate images`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            "Desconocido",
            "Si no me pagas, voy a publicar tus fotos",
        )

        assertTrue(result.flags.contains("sextortion"))
    }

    @Test
    fun `family abuse and isolation are detected`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            "Sofía",
            "Mi padrastro me pega y no me deja salir de la casa",
        )

        assertTrue(result.flags.contains("abuse"))
    }

    @Test
    fun `eating disorder behavior is detected`() {
        val result = Sanitizer.sanitize(
            "com.instagram.android",
            null,
            "No he comido en tres días y me provoco el vómito",
        )

        assertTrue(result.flags.contains("eating_disorder"))
    }

    @Test
    fun `hate speech against a group is detected`() {
        val result = Sanitizer.sanitize(
            "com.discord",
            null,
            "Muerte a los inmigrantes",
        )

        assertTrue(result.flags.contains("hate_speech"))
        assertTrue(result.flags.contains("radicalization"))
    }

    @Test
    fun `weapon mention is detected`() {
        val result = Sanitizer.sanitize(
            "org.telegram.messenger",
            null,
            "Tengo una pistola y balas",
        )

        assertTrue(result.flags.contains("weapons"))
    }

    @Test
    fun `dangerous challenge is detected`() {
        val result = Sanitizer.sanitize(
            "com.zhiliaoapp.musically",
            null,
            "Entra al reto de la asfixia, es el blackout challenge",
        )

        assertTrue(result.flags.contains("dangerous_challenge"))
    }

    @Test
    fun `scam message is detected`() {
        val result = Sanitizer.sanitize(
            "com.instagram.android",
            null,
            "Ganaste un premio, envía el código de verificación",
        )

        assertTrue(result.flags.contains("scam"))
    }

    @Test
    fun `doxxing threat is detected`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            null,
            "Sé dónde vives, voy a publicar tu dirección",
        )

        assertTrue(result.flags.contains("doxxing"))
    }

    @Test
    fun `runaway risk is detected`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            null,
            "Me voy a arrancar de casa, no vuelvo más",
        )

        assertTrue(result.flags.contains("runaway"))
    }

    @Test
    fun `trafficking threat is detected`() {
        val result = Sanitizer.sanitize(
            "org.telegram.messenger",
            null,
            "Te van a vender a otro país para trabajar",
        )

        assertTrue(result.flags.contains("trafficking"))
    }

    @Test
    fun `history homework is not radicalization`() {
        val result = Sanitizer.sanitize(
            "com.google.android.apps.docs",
            null,
            "Estudiamos la Segunda Guerra Mundial, Hitler y el nazismo",
        )

        assertTrue(result.flags.isEmpty())
    }

    @Test
    fun `turn taking is not abuse`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            "Matías",
            "Me tocó el turno de estudiar hoy",
        )

        assertFalse(result.flags.contains("abuse"))
    }

    @Test
    fun `family dinner is not drugs`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            "Mamá",
            "Vino mi abuela a comer pizza",
        )

        assertFalse(result.flags.contains("drugs"))
    }

    @Test
    fun `waking up early is not self harm`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            "Matías",
            "No quiero despertar temprano mañana",
        )

        assertFalse(result.flags.contains("self_harm"))
    }

    @Test
    fun `weather alert is not violence`() {
        val result = Sanitizer.sanitize(
            "com.google.android.youtube",
            null,
            "Hay amenaza de lluvia para mañana",
        )

        assertFalse(result.flags.contains("violence"))
    }

    @Test
    fun `mild insult is detected`() {
        val result = Sanitizer.sanitize("com.instagram.android", null, "Eres un tonto, no sabes nada")

        assertTrue(result.flags.contains("insult_mild"))
    }

    @Test
    fun `moderate insult is detected`() {
        val result = Sanitizer.sanitize("com.whatsapp", null, "Eres un idiota y no entiendes")

        assertTrue(result.flags.contains("insult_moderate"))
    }

    @Test
    fun `severe insult and death wish are detected`() {
        val result = Sanitizer.sanitize(
            "org.telegram.messenger",
            null,
            "Eres un conchetumadre, ojalá te mueras",
        )

        assertTrue(result.flags.contains("insult_severe"))
    }

    @Test
    fun `points redemption scam is detected`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            null,
            "Canjea tus puntos antes que venzan y recibe un premio",
        )

        assertTrue(result.flags.contains("scam_points"))
    }

    @Test
    fun `casino invitation scam is detected`() {
        val result = Sanitizer.sanitize(
            "com.instagram.android",
            null,
            "Casino online con bono de bienvenida y giros gratis",
        )

        assertTrue(result.flags.contains("scam_casino"))
        assertTrue(result.flags.contains("gambling"))
    }

    @Test
    fun `financial scam is detected`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            null,
            "Inversión garantizada en bitcoin, duplica tu dinero",
        )

        assertTrue(result.flags.contains("scam_financial"))
    }

    @Test
    fun `account phishing is detected`() {
        val result = Sanitizer.sanitize(
            "org.telegram.messenger",
            null,
            "Tu cuenta será suspendida, envía el código de verificación",
        )

        assertTrue(result.flags.contains("scam_account"))
    }

    @Test
    fun `prize scam is detected`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            null,
            "Ganaste un premio, reclama tu iPhone gratis",
        )

        assertTrue(result.flags.contains("scam_prize"))
    }

    @Test
    fun `delivery scam is detected`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            null,
            "Paquete retenido en aduana, paga los impuestos",
        )

        assertTrue(result.flags.contains("scam_delivery"))
    }

    @Test
    fun `job scam is detected`() {
        val result = Sanitizer.sanitize(
            "com.instagram.android",
            null,
            "Trabajo desde casa, gana dinero fácil sin experiencia",
        )

        assertTrue(result.flags.contains("scam_job"))
    }

    @Test
    fun `romance scam is detected`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            null,
            "Soldado en el extranjero necesita dinero para el pasaje",
        )

        assertTrue(result.flags.contains("scam_romance"))
    }

    @Test
    fun `circus clown is not an insult`() {
        val result = Sanitizer.sanitize(
            "com.google.android.youtube",
            null,
            "El payaso del circo hizo un truco",
        )

        assertFalse(result.flags.contains("insult_mild"))
    }

    @Test
    fun `sports points are not a points scam`() {
        val result = Sanitizer.sanitize(
            "com.whatsapp",
            "Matías",
            "El partido tiene tres puntos y vamos ganando",
        )

        assertFalse(result.flags.contains("scam_points"))
    }

    @Test
    fun `delayed project is not a severe insult`() {
        val result = Sanitizer.sanitize(
            "com.google.android.apps.docs",
            null,
            "El proyecto está retrasado para la entrega",
        )

        assertFalse(result.flags.contains("insult_severe"))
    }
}
