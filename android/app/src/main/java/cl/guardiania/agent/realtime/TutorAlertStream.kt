package cl.guardiania.agent.realtime

import cl.guardiania.agent.data.TutorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Evento mínimo recibido por el stream del tutor. */
data class TutorRealtimeAlert(
    val alertId: String?,
    val severity: String?,
    val deviceAlias: String?,
    val category: String?,
    val kind: String = "risk_alert",
)

/**
 * Stream de alertas del tutor (`/ws/tutor`).
 *
 * Autenticación: ticket efímero con scope `alerts:subscribe` entregado en el
 * subprotocolo `Sec-WebSocket-Protocol: guardiania, <ticket>`. Nunca se usa la
 * query string, para no dejar el ticket en logs ni proxies.
 *
 * El cliente solo se conecta mientras el tutor está en primer plano; con la app
 * en segundo plano las alertas llegan por FCM. Se reconecta con backoff y pide
 * un ticket nuevo en cada intento.
 */
class TutorAlertStream(
    private val repo: TutorRepository,
    private val onAlert: (TutorRealtimeAlert) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var socket: WebSocket? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        val current = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = current
        job = current.launch { connectLoop() }
    }

    @Synchronized
    fun stop() {
        val current = scope
        val running = job
        socket?.close(1000, "tutor offline")
        socket = null
        isConnected = false
        job = null
        scope = null
        current?.launch { running?.cancelAndJoin() }
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val connected = runCatching { connectOnce() }.getOrDefault(false)
            if (!currentCoroutineContext().isActive) break
            attempt = if (connected) 1 else (attempt + 1).coerceAtMost(5)
            val waitMs = if (connected) 2_000L else minOf(30_000L, 2_000L * (1L shl (attempt - 1)))
            delay(waitMs)
        }
    }

    /** Returns true when the socket was opened (even if it closed later). */
    private suspend fun connectOnce(): Boolean {
        val session = repo.session() ?: return false
        val ticket = runCatching { repo.realtimeTicket() }.getOrNull() ?: return false
        val url = websocketUrl(session.baseUrl, ticket.wsUrl) ?: return false
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)

        val request = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", "$WS_PROTOCOL, ${ticket.wsTicket}")
            .build()

        suspendCancellableCoroutine<Unit> { continuation ->
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.set(true)
                    socket = webSocket
                    isConnected = true
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val alert = parseAlert(text) ?: return
                    onAlert(alert)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnected = false
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }

            val created = client.newWebSocket(request, listener)
            socket = created
            continuation.invokeOnCancellation {
                created.close(1000, "tutor offline")
            }
        }

        socket = null
        isConnected = false
        return opened.get()
    }

    private fun parseAlert(text: String): TutorRealtimeAlert? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val kind = root["type"]?.jsonPrimitive?.contentOrNull ?: return null
        if (kind != "risk_alert" && kind != "device_reconnected") return null
        val data = root["data"] as? JsonObject ?: return null
        fun field(name: String): String? =
            data[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return TutorRealtimeAlert(
            alertId = field("alert_id"),
            severity = field("severidad"),
            deviceAlias = field("etiqueta_menor") ?: field("device_alias"),
            category = field("categoria"),
            kind = kind,
        )
    }

    private fun websocketUrl(baseUrl: String, path: String): String? {
        val normalized = baseUrl.trim().trimEnd('/')
        val scheme = when {
            normalized.startsWith("https://") -> "wss://"
            normalized.startsWith("http://") -> "ws://"
            else -> return null
        }
        val host = normalized.removePrefix("https://").removePrefix("http://")
        val suffix = if (path.startsWith("/")) path else "/$path"
        return scheme + host + suffix
    }

    private companion object {
        const val WS_PROTOCOL = "guardiania"
    }
}
