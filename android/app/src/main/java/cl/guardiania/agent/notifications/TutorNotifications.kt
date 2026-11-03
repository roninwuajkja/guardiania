package cl.guardiania.agent.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cl.guardiania.agent.MainActivity
import cl.guardiania.agent.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Notificaciones locales del tutor.
 *
 * La notificación nunca incluye narrativa, texto ni nombres de contacto: solo
 * severidad y alias del equipo. El detalle se consulta dentro de la app, que
 * exige sesión autenticada.
 */
object TutorNotifications {

    const val ALERT_CHANNEL = "guardiania_alerts"
    const val SYNC_CHANNEL = "guardiania_sync"
    const val GUARD_CHANNEL = "guardiania_guard"

    private val nextId = AtomicInteger(1000)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val alerts = NotificationChannel(
            ALERT_CHANNEL,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.alert_channel_description)
            enableVibration(true)
            setShowBadge(true)
        }

        val sync = NotificationChannel(
            SYNC_CHANNEL,
            context.getString(R.string.sync_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.sync_channel_description)
            setShowBadge(false)
        }

        val guard = NotificationChannel(
            GUARD_CHANNEL,
            context.getString(R.string.guard_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.guard_channel_description)
            setShowBadge(false)
            setSound(null, null)
        }

        manager.createNotificationChannels(listOf(alerts, sync, guard))
    }

    fun showReconnected(context: Context, deviceAlias: String?) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val alias = deviceAlias?.trim()?.takeIf { it.isNotEmpty() } ?: "tu equipo"
        val body = "$alias volvió a enviar señales al panel."
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            alias.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, SYNC_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_guardiania)
            .setContentTitle("IA Guardian · Teléfono reconectado")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setColor(0xFF0F3D2E.toInt())
            .build()

        try {
            manager.notify(nextId.incrementAndGet(), notification)
        } catch (_: SecurityException) {
            // Sin POST_NOTIFICATIONS: el aviso igual aparece dentro de la app.
        }
    }

    fun showRiskAlert(
        context: Context,
        alertId: String?,
        severity: String?,
        deviceAlias: String?,
        category: String?,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val alias = deviceAlias?.trim()?.takeIf { it.isNotEmpty() } ?: "tu equipo"
        val label = categoryLabel(category)
        val title = if (label != null) "IA Guardian · $label" else "IA Guardian"
        val body = "Señal ${severityLabel(severity)} en $alias"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ALERTS, true)
            alertId?.let { putExtra(EXTRA_ALERT_ID, it) }
        }
        val pending = PendingIntent.getActivity(
            context,
            (alertId ?: "guardiania").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_guardiania)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setColor(0xFF0F3D2E.toInt())
            .build()

        try {
            manager.notify(nextId.incrementAndGet(), notification)
        } catch (_: SecurityException) {
            // El tutor no concedió POST_NOTIFICATIONS: la alerta igual queda en el panel.
        }
    }

    fun severityLabel(value: String?): String = when (value?.uppercase()) {
        "CRITICA" -> "crítica"
        "ALTA" -> "alta"
        "MEDIA" -> "media"
        "BAJA" -> "baja"
        else -> "de riesgo"
    }

    fun categoryLabel(value: String?): String? = when (value?.lowercase()) {
        "self_harm" -> "Posible autolesión"
        "grooming" -> "Posible grooming"
        "sextortion" -> "Sextorsión"
        "abuse" -> "Abuso o maltrato"
        "trafficking" -> "Trata o explotación"
        "radicalization" -> "Radicalización"
        "sexual_content" -> "Contenido sexual"
        "violence" -> "Violencia o amenazas"
        "weapons" -> "Armas"
        "coercion" -> "Presión o chantaje"
        "bullying" -> "Acoso escolar"
        "hate_speech" -> "Discurso de odio"
        "insult_severe" -> "Insulto grave"
        "insult_moderate" -> "Insulto moderado"
        "insult_mild" -> "Insulto leve"
        "eating_disorder" -> "Trastorno alimentario"
        "doxxing" -> "Datos expuestos"
        "runaway" -> "Riesgo de fuga"
        "dangerous_challenge" -> "Reto peligroso"
        "drugs" -> "Drogas o alcohol"
        "gambling" -> "Apuestas"
        "malestar" -> "Malestar emocional"
        "scam_points" -> "Canje de puntos"
        "scam_casino" -> "Invitación a casino"
        "scam_financial" -> "Inversión sospechosa"
        "scam_account" -> "Phishing o suplantación"
        "scam_prize" -> "Premio sospechoso"
        "scam_delivery" -> "Envío sospechoso"
        "scam_job" -> "Trabajo sospechoso"
        "scam_romance" -> "Estafa romántica"
        "scam" -> "Posible estafa"
        "sin_senal" -> "Teléfono sin señal"
        "unknown_contact" -> "Contacto desconocido"
        else -> null
    }

    const val EXTRA_OPEN_ALERTS = "open_alerts"
    const val EXTRA_ALERT_ID = "alert_id"
}
