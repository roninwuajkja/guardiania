package cl.guardiania.agent.push

import android.os.Build
import cl.guardiania.agent.data.TutorRepository
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registra el token FCM *del teléfono del tutor* en el backend.
 *
 * Sin `google-services.json` Firebase no está inicializado: `getInstance()`
 * lanza `IllegalStateException` y el registro devuelve `false` sin romper la
 * sesión. El WebSocket sigue siendo el canal en primer plano.
 *
 * El token es una credencial de entrega, no un dato del menor. Se reasigna al
 * tutor que inicia sesión y se desactiva al cerrar sesión.
 */
class PushRegistrar(private val repo: TutorRepository) {

    @Volatile
    private var lastToken: String? = null

    suspend fun register(): Boolean {
        if (repo.session() == null) return false
        val token = currentToken() ?: return false
        val label = deviceLabel()
        return runCatching {
            repo.registerPushToken(token, label)
            lastToken = token
            true
        }.getOrDefault(false)
    }

    /** Best-effort: si no hay red, el servidor desactiva el token al fallar el envío. */
    suspend fun unregister() {
        val token = lastToken ?: currentToken() ?: return
        runCatching { repo.unregisterPushToken(token) }
        lastToken = null
    }

    private suspend fun currentToken(): String? = withTimeoutOrNull(TIMEOUT_MS) {
        val deferred = CompletableDeferred<String?>()
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                deferred.complete(if (task.isSuccessful) task.result else null)
            }
        } catch (_: IllegalStateException) {
            deferred.complete(null)
        }
        deferred.await()
    }

    private fun deviceLabel(): String {
        val raw = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        return raw.ifBlank { "Teléfono del tutor" }.take(60)
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
