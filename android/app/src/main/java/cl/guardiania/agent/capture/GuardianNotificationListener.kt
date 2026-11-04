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

    init {
        // Samsung puede dejar de entregar onNotificationPosted mientras la
        // pantalla está bloqueada. Cada pocos minutos se revisan las
        // notificaciones que siguen activas para no perderlas en silencio.
        scope.launch {
            while (true) {
                delay(SCAN_INTERVAL_MS)
                scanActiveNotifications()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        scope.launch {
            (application as GuardianApp).container.statusReporter.report()
            delay(1_500)
            scanActiveNotifications()
        }
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
        if (!seen.add(seenKey)) return
        if (seen.size > MAX_SEEN) seen.clear()

        val store = (application as GuardianApp).container.store
        // Un escaneo puede ver avisos ya procesados antes de reiniciar el
        // proceso: se descartan los anteriores al piso capturado al inicio.
        if (fromScan && posted.postTime > 0 && posted.postTime <= scanFloor) return

        val extras = posted.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val sanitized = Sanitizer.sanitize(posted.packageName, title, text)
        if (sanitized.category == "otras" && sanitized.flags.isEmpty()) return

        // Se conserva la hora real del aviso: sirve de marca para no repetirlo
        // tras un reinicio y evita que un escaneo adelante a los demás.
        val postedAt = if (posted.postTime > 0) posted.postTime else System.currentTimeMillis()
        scope.launch {
            store.addSignal(sanitized.category, sanitized.flags, postedAt)
            store.advanceLastNotification(postedAt)
            if (sanitized.flags.isNotEmpty() && store.pairing() != null) {
                SyncScheduler.requestPrioritySync(applicationContext)
            }
        }
    }

    companion object {
        @Volatile
        var connected: Boolean = false
            private set

        private const val SCAN_INTERVAL_MS = 3 * 60_000L
        private const val MAX_SEEN = 500
    }
}
