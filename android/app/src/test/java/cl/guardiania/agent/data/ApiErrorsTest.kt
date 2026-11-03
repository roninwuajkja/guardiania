package cl.guardiania.agent.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiErrorsTest {

    private fun httpError(status: Int, body: String): HttpException {
        val response = Response.error<Unit>(status, body.toResponseBody("application/json".toMediaType()))
        return HttpException(response)
    }

    @Test
    fun `pin required is parsed from the structured detail`() {
        val error = httpError(
            403,
            """{"detail":{"code":"pin_required","message":"Ingresa el código","attempts_left":5}}""",
        ).unlinkError()

        assertEquals("pin_required", error?.code)
        assertEquals(5, error?.attemptsLeft)
        assertNull(error?.retryAfterSeconds)
    }

    @Test
    fun `locked error includes the wait time`() {
        val error = httpError(
            429,
            """{"detail":{"code":"pin_locked","message":"Espera 15 minuto(s).","retry_after_seconds":900}}""",
        ).unlinkError()

        assertEquals("pin_locked", error?.code)
        assertEquals(900, error?.retryAfterSeconds)
    }

    @Test
    fun `plain detail is not a pin error`() {
        assertNull(httpError(403, """{"detail":"Sesión expirada"}""").unlinkError())
    }
}
