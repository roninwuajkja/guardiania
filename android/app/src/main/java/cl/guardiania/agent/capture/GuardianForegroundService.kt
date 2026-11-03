package cl.guardiania.agent.capture

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cl.guardiania.agent.GuardianApp
import cl.guardiania.agent.MainActivity
import cl.guardiania.agent.R
import cl.guardiania.agent.notifications.TutorNotifications

/**
 * Mantiene vivo el proceso del menor para que el listener de notificaciones y
 * el sync prioritario no se retrasen cuando el fabricante duerme la app.
 *
 * Es una notificación persistente de baja prioridad, sin sonido y sin datos.
 * Se detiene al desvincular.
 */
class GuardianForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        TutorNotifications.ensureChannels(this)
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val paired = (application as GuardianApp).container.store.pairing() != null
        if (!paired) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            7,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, TutorNotifications.GUARD_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_guardiania)
            .setContentTitle(getString(R.string.guard_notification_title))
            .setContentText(getString(R.string.guard_notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(getString(R.string.guard_notification_text)),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .setColor(0xFF0F3D2E.toInt())
            .build()
    }

    companion object {
        private const val NOTIF_ID = 7401

        fun start(context: Context) {
            val intent = Intent(context, GuardianForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GuardianForegroundService::class.java)) }
        }
    }
}
