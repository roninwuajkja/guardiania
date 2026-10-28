package cl.guardiania.agent

import android.app.Application
import cl.guardiania.agent.sync.SyncScheduler

class GuardianApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.ensureNotifications()
        SyncScheduler.ensurePeriodic(this)
    }
}
