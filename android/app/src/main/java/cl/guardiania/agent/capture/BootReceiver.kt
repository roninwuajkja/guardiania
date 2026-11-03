package cl.guardiania.agent.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cl.guardiania.agent.GuardianApp

/** Reinicia la guardia tras un reinicio del teléfono o una actualización. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? GuardianApp ?: return
        if (app.container.store.pairing() != null) {
            GuardianForegroundService.start(context)
        }
    }
}
