package cl.guardiania.agent.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import cl.guardiania.agent.GuardianApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Servicio de accesibilidad OPCIONAL y apagado por defecto.
 *
 * No lee texto, nodos, contraseñas ni contenido: solo observa el cambio de
 * ventana para registrar la categoría de la app en primer plano. El manifiesto
 * declara `canRetrieveWindowContent="false"` y el XML de configuración limita
 * los eventos a `typeWindowStateChanged`.
 *
 * Se incluye porque algunos fabricantes bloquean la lista de notificaciones en
 * segundo plano; el monitoreo base sigue funcionando sin este permiso.
 */
class GuardianAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastWindowKey: String? = null
    private var lastRecordedAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val current = event ?: return
        if (current.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = current.packageName?.toString() ?: return
        if (pkg == packageName) return

        val category = Sanitizer.categoryFor(pkg)
        if (category == "otras") return

        val now = System.currentTimeMillis()
        val key = "$pkg:$category"
        if (key == lastWindowKey && now - lastRecordedAt < MIN_INTERVAL_MS) return
        lastWindowKey = key
        lastRecordedAt = now

        val store = (application as GuardianApp).container.store
        scope.launch {
            // Sin flags: es contexto de uso, no contenido. El servidor lo trata
            // como vector benigno y no genera alerta por sí solo.
            store.addSignal(category, emptyList(), now)
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val MIN_INTERVAL_MS = 60_000L
    }
}
