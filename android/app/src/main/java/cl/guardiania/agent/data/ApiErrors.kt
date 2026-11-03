package cl.guardiania.agent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NotPairedException : IllegalStateException("Este teléfono no está vinculado")

class SessionRevokedException : IllegalStateException("La vinculación fue revocada")

/** Error de desvinculación con código del tutor (respuesta `detail` estructurada). */
data class UnlinkError(
    val code: String,
    val message: String,
    val attemptsLeft: Int?,
    val retryAfterSeconds: Int?,
)

private val errorJson = Json { ignoreUnknownKeys = true }

fun HttpException.unlinkError(): UnlinkError? = runCatching {
    val body = response()?.errorBody()?.string().orEmpty()
    val detail = errorJson.parseToJsonElement(body).jsonObject["detail"]?.jsonObject
        ?: return@runCatching null
    val code = detail["code"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
    UnlinkError(
        code = code,
        message = detail["message"]?.jsonPrimitive?.contentOrNull
            ?: "No se pudo desvincular el teléfono.",
        attemptsLeft = detail["attempts_left"]?.jsonPrimitive?.intOrNull,
        retryAfterSeconds = detail["retry_after_seconds"]?.jsonPrimitive?.intOrNull,
    )
}.getOrNull()

private fun HttpException.serverDetail(): String? = runCatching {
    val body = response()?.errorBody()?.string().orEmpty()
    val detail = JSONObject(body).opt("detail")
    (detail as? String)?.trim()?.takeIf { it.isNotEmpty() }
}.getOrNull()

fun Throwable.userMessage(): String = when (this) {
    is NotPairedException -> "Este teléfono todavía no está vinculado."
    is SessionRevokedException -> "La vinculación fue revocada. Vuelve a vincular el teléfono desde el panel."
    is UnknownHostException -> "No encontramos el panel. Revisa la dirección y la conexión."
    is SocketTimeoutException -> "El panel demoró demasiado en responder. Intenta otra vez."
    is IOException -> "Sin conexión con el panel. Revisa la red e inténtalo de nuevo."
    is HttpException -> serverDetail() ?: when (code()) {
        401, 403 -> "La sesión del teléfono expiró. Vuelve a vincularlo."
        404 -> "La dirección del panel no existe. Revisa la URL."
        409 -> "Este código ya fue utilizado. Genera uno nuevo en el panel."
        410 -> "El código expiró o la vinculación fue revocada."
        422 -> "Código inválido o expirado. Genera uno nuevo en el panel."
        429 -> "Demasiados intentos. Espera unos minutos."
        in 500..599 -> "El panel tuvo un problema. Intenta en unos minutos."
        else -> "El panel rechazó la solicitud (${code()})."
    }
    else -> "Ocurrió un error inesperado. Intenta otra vez."
}

fun Throwable.tutorMessage(): String = when (this) {
    is TutorSessionExpiredException -> "Tu sesión expiró. Vuelve a entrar."
    is UnknownHostException -> "No encontramos el panel. Revisa la dirección y la conexión."
    is SocketTimeoutException -> "El panel demoró demasiado en responder. Intenta otra vez."
    is IOException -> "Sin conexión con el panel. Revisa la red e inténtalo de nuevo."
    is HttpException -> serverDetail() ?: when (code()) {
        401, 403 -> "Tu sesión expiró o no tienes permiso. Vuelve a entrar."
        404 -> "No encontramos ese recurso en el panel."
        409 -> "Ese dispositivo ya está vinculado o la operación se repitió."
        422 -> "Revisa los datos ingresados."
        429 -> "Demasiados intentos. Espera unos minutos."
        in 500..599 -> "El panel tuvo un problema. Intenta en unos minutos."
        else -> "El panel rechazó la solicitud (${code()})."
    }
    else -> "Ocurrió un error inesperado. Intenta otra vez."
}
