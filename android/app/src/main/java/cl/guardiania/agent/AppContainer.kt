package cl.guardiania.agent

import android.content.Context
import cl.guardiania.agent.capture.UsageCollector
import cl.guardiania.agent.core.SecretVault
import cl.guardiania.agent.data.DeviceSession
import cl.guardiania.agent.data.PairingRepository
import cl.guardiania.agent.data.SecureStore
import cl.guardiania.agent.data.TutorRepository
import cl.guardiania.agent.notifications.TutorNotifications
import cl.guardiania.agent.push.PushRegistrar
import cl.guardiania.agent.realtime.TutorAlertStream
import cl.guardiania.agent.realtime.TutorRealtimeAlert
import cl.guardiania.agent.sync.SyncEngine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val store: SecureStore by lazy {
        SecureStore.open(appContext, SecretVault(appContext).databasePassphrase())
    }

    val pairingRepository: PairingRepository by lazy { PairingRepository(store) }
    val tutorRepository: TutorRepository by lazy { TutorRepository(store) }
    val session: DeviceSession by lazy { DeviceSession(store) }
    val usageCollector: UsageCollector by lazy { UsageCollector(appContext) }
    val syncEngine: SyncEngine by lazy {
        SyncEngine(store, session, usageCollector, BuildConfig.VERSION_NAME)
    }

    /** Alertas del tutor recibidas en vivo; el ViewModel las consume. */
    val alertEvents = MutableSharedFlow<TutorRealtimeAlert>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val alertStream: TutorAlertStream by lazy {
        TutorAlertStream(tutorRepository) { event -> alertEvents.tryEmit(event) }
    }

    val pushRegistrar: PushRegistrar by lazy { PushRegistrar(tutorRepository) }

    fun ensureNotifications() {
        TutorNotifications.ensureChannels(appContext)
    }
}
