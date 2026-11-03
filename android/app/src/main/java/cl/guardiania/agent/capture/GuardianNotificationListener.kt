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
import kotlinx.coroutines.launch

/**
 * Reads notifications in memory, converts them to a category plus lexical
 * flags, and stores only that result. Notification text never touches disk,
 * logs, analytics or the network. No AccessibilityService is used.
 */
class GuardianNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        scope.launch {
            (application as GuardianApp).container.statusReporter.report()
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
        val posted = notification ?: return
        if (posted.packageName == packageName) return
        if (posted.isOngoing) return
        if (posted.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = posted.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val sanitized = Sanitizer.sanitize(posted.packageName, title, text)
        if (sanitized.category == "otras" && sanitized.flags.isEmpty()) return

        val store = (application as GuardianApp).container.store
        val postedAt = System.currentTimeMillis()
        scope.launch {
            store.addSignal(sanitized.category, sanitized.flags, postedAt)
            store.setMeta("last_notification_at", postedAt.toString())
            if (sanitized.flags.isNotEmpty() && store.pairing() != null) {
                SyncScheduler.requestPrioritySync(applicationContext)
            }
        }
    }

    companion object {
        @Volatile
        var connected: Boolean = false
            private set
    }
}
