package cl.guardiania.agent.data

import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NotPairedException : IllegalStateException("Este teléfono no está vinculado")

class SessionRevokedException : IllegalStateException("La vinculación fue revocada")

fun Throwable.userMessage(): String = when (this) {
    is NotPairedException -> "Este teléfono todavía no está vinculado."
    is SessionRevokedException -> "La vinculación fue revocada. Vuelve a vincular el teléfono desde el panel."
    is UnknownHostException -> "No encontramos el panel. Revisa la dirección y la conexión."
    is SocketTimeoutException -> "El panel demoró demasiado en responder. Intenta otra vez."
    is IOException -> "Sin conexión con el panel. Revisa la red e inténtalo de nuevo."
    is HttpException -> when (code()) {
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
    is HttpException -> {
        val detail = runCatching {
            JSONObject(response()?.errorBody()?.string().orEmpty()).optString("detail")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
        detail ?: when (code()) {
            401, 403 -> "Tu sesión expiró o no tienes permiso. Vuelve a entrar."
            404 -> "No encontramos ese recurso en el panel."
            409 -> "Ese dispositivo ya está vinculado o la operación se repitió."
            422 -> "Revisa los datos ingresados."
            429 -> "Demasiados intentos. Espera unos minutos."
            in 500..599 -> "El panel tuvo un problema. Intenta en unos minutos."
            else -> "El panel rechazó la solicitud (${code()})."
        }
    }
    else -> "Ocurrió un error inesperado. Intenta otra vez."
}
