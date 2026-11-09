package cl.guardiania.agent.capture

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import cl.guardiania.agent.GuardianApp
import cl.guardiania.agent.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads notifications in memory, converts them to a category plus lexical
 * flags, and stores only that result. Notification text never touches disk,
 * logs, analytics or the network. No AccessibilityService is used.
 */
class GuardianNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        activeInstance = this
        scope.launch {
            (application as GuardianApp).container.statusReporter.report()
            delay(1_500)
            scanActiveNotifications()
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        // Samsung y algunos fabricantes desenlazan el listener sin volver a
        // enlazarlo. Se pide explícitamente al sistema que lo reconecte; si no,
        // las notificaciones se pierden en silencio hasta abrir la app.
        runCatching {
            requestRebind(ComponentName(this, GuardianNotificationListener::class.java))
        }
        scope.launch {
            (application as GuardianApp).container.statusReporter.report()
        }
        SyncScheduler.requestPrioritySync(applicationContext)
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) {
        process(notification, fromScan = false)
    }

    private fun scanActiveNotifications() {
        if (!connected) return
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val store = (application as GuardianApp).container.store
        // El piso se lee una sola vez: si se releyera por aviso, el primero
        // procesado elevaría la marca y los demás se saltarían.
        val floor = store.meta("last_notification_at")?.toLongOrNull() ?: 0L
        active.forEach { process(it, fromScan = true, scanFloor = floor) }
    }

    private fun process(
        notification: StatusBarNotification?,
        fromScan: Boolean,
        scanFloor: Long = 0L,
    ) {
        val posted = notification ?: return
        if (posted.packageName == packageName) return
        if (posted.isOngoing) return
        if (posted.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val seenKey = "${posted.key}:${posted.postTime}"
        if (seen.contains(seenKey) || !inFlight.add(seenKey)) return

        var handedOff = false
        try {
            val store = (application as GuardianApp).container.store
            // Un escaneo o una reconexión pueden reenviar avisos ya procesados:
            // se descartan los anteriores al piso capturado. En el escaneo el piso
            // se toma una vez por lote; en tiempo real, el valor vigente.
            val floor = if (fromScan) {
                scanFloor
            } else {
                store.meta("last_notification_at")?.toLongOrNull() ?: 0L
            }
            if (posted.postTime > 0 && posted.postTime <= floor) {
                markSeen(seenKey)
                return
            }

            val extras = posted.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            if (title.isNullOrBlank() && text.isNullOrBlank()) {
                markSeen(seenKey)
                return
            }

            val sanitized = Sanitizer.sanitize(posted.packageName, title, text)
            if (sanitized.category == "otras" && sanitized.flags.isEmpty()) {
                markSeen(seenKey)
                return
            }

            // Se conserva la hora real del aviso: sirve de marca para no repetirlo
            // tras un reinicio y evita que un escaneo adelante a los demás.
            val postedAt = if (posted.postTime > 0) posted.postTime else System.currentTimeMillis()
            handedOff = true
            scope.launch {
                try {
                    // Señal y marca se guardan juntas: si falla la escritura, el
                    // aviso queda fuera de "seen" y un escaneo posterior lo reintenta.
                    store.recordSignal(sanitized.category, sanitized.flags, postedAt)
                    markSeen(seenKey)
                    if (sanitized.flags.isNotEmpty() && store.pairing() != null) {
                        SyncScheduler.requestPrioritySync(applicationContext)
                    }
                } catch (_: Throwable) {
                    // La señal queda fuera de "seen" para que el próximo escaneo la reintente.
                } finally {
                    inFlight.remove(seenKey)
                }
            }
        } finally {
            if (!handedOff) inFlight.remove(seenKey)
        }
    }

    private fun markSeen(key: String) {
        if (seen.size >= MAX_SEEN) seen.clear()
        seen.add(key)
    }

    companion object {
        @Volatile
        var connected: Boolean = false
            private set

        @Volatile
        private var activeInstance: GuardianNotificationListener? = null

        /** Lo llama el servicio en primer plano, que no se recrea con cada reconexión. */
        fun scanActiveNow() {
            val service = activeInstance ?: return
            if (!connected) return
            service.scanActiveNotifications()
        }

        private const val MAX_SEEN = 500
    }
}
