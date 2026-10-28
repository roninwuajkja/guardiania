package cl.guardiania.agent.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import cl.guardiania.agent.GuardianApp
import cl.guardiania.agent.notifications.TutorNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Recepción FCM del tutor.
 *
 * El backend envía un payload mínimo (`alert_id`, severidad, categoría y alias).
 * Aquí no se guarda nada: si el WebSocket está conectado (app en primer plano),
 * la alerta ya llegó por ese canal y no se duplica la notificación. Si no, se
 * muestra una notificación local sin narrativa ni contenido.
 */
class GuardianMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val container = (application as GuardianApp).container
        scope.launch {
            // El registro consulta de nuevo el token; así esta ruta no necesita
            // guardar el valor recibido ni exponerlo en logs.
            container.pushRegistrar.register()
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val container = (application as GuardianApp).container
        if (container.alertStream.isConnected) return
        val data = message.data
        if (data["tipo"] != null && data["tipo"] != "risk_alert") return
        TutorNotifications.showRiskAlert(
            context = this,
            alertId = data["alert_id"],
            severity = data["severidad"],
            deviceAlias = data["device_alias"],
            category = data["categoria"],
        )
    }
}
