package cl.guardiania.agent.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Aviso en proceso de que una sincronización terminó. La pantalla de estado lo
 * escucha para refrescar "última sincronización" aunque el envío lo haya hecho
 * un worker en segundo plano.
 */
object SyncEvents {

    private val _completed = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val completed = _completed.asSharedFlow()

    fun notify(at: Long) {
        _completed.tryEmit(at)
    }
}
