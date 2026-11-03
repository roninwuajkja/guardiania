package cl.guardiania.agent.data

import android.app.NotificationManager
import android.content.Context
import cl.guardiania.agent.ui.readPermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Informa al panel si el teléfono conserva el acceso a notificaciones, el uso y
 * si está en No molestar. No envía contenido: solo el estado de los permisos.
 *
 * El sistema no permite impedir que el menor desactive el acceso; el objetivo es
 * que el tutor lo sepa y que la guardia no parezca completa cuando no lo está.
 */
class DeviceStatusReporter(
    private val context: Context,
    private val store: SecureStore,
    private val session: DeviceSession,
) {
    suspend fun report() {
        store.pairing() ?: return
        val permissions = readPermissionStatus(context)
        val filter = runCatching {
            context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        }.getOrNull()
        val dnd = filter != null &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN &&
            filter != NotificationManager.INTERRUPTION_FILTER_ALL

        val body = DeviceStatusRequest(
            notificacionesActivas = permissions.notificationAccess,
            usoActivo = permissions.usageAccess,
            dnd = dnd,
            accesibilidad = permissions.accessibility,
        )

        withContext(Dispatchers.IO) {
            runCatching {
                session.authorized { api, token -> api.reportStatus("Bearer $token", body) }
            }
        }
    }
}
