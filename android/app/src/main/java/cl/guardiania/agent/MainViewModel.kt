package cl.guardiania.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.guardiania.agent.capture.GuardianForegroundService
import cl.guardiania.agent.data.PairingRecord
import cl.guardiania.agent.data.SessionRevokedException
import cl.guardiania.agent.data.UnlinkRequest
import cl.guardiania.agent.data.unlinkError
import cl.guardiania.agent.data.userMessage
import cl.guardiania.agent.sync.SyncEvents
import cl.guardiania.agent.sync.SyncOutcome
import cl.guardiania.agent.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

const val DEFAULT_BASE_URL = "https://guardiania-74l4.onrender.com/"

enum class AppRole { TUTOR, CHILD }

data class AgentUiState(
    val ready: Boolean = false,
    val pairing: PairingRecord? = null,
    val baseUrl: String = DEFAULT_BASE_URL,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val pending: Int = 0,
    val lastSyncAt: Long? = null,
    val role: AppRole? = null,
    val hasTutorSession: Boolean = false,
    val unlinkPinRequired: Boolean = false,
    val unlinkError: String? = null,
    val unlinkAttemptsLeft: Int? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val container = (application as GuardianApp).container
    private val store = container.store
    private val _ui = MutableStateFlow(AgentUiState())
    val ui = _ui.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val pairing = store.pairing()
            val tutorSession = store.tutorSession()
            val lastRole = store.meta("last_role")
            val role = when {
                tutorSession != null && lastRole != AppRole.CHILD.name -> AppRole.TUTOR
                pairing != null && lastRole == AppRole.CHILD.name -> AppRole.CHILD
                pairing != null -> AppRole.CHILD
                tutorSession != null -> AppRole.TUTOR
                else -> null
            }
            val baseUrl = store.meta("base_url_hint") ?: DEFAULT_BASE_URL
            _ui.value = AgentUiState(
                ready = true,
                pairing = pairing,
                baseUrl = baseUrl,
                pending = store.pendingCount(),
                lastSyncAt = store.lastSyncAt(),
                role = role,
                hasTutorSession = tutorSession != null,
            )
            if (pairing != null) {
                // Al abrir la app se reporta el estado: el panel lo ve al instante.
                runCatching { container.statusReporter.report() }
                GuardianForegroundService.start(app)
            } else {
                GuardianForegroundService.stop(app)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            SyncEvents.completed.collect { at ->
                val pending = store.pendingCount()
                _ui.update { it.copy(lastSyncAt = at, pending = pending) }
            }
        }
    }

    fun chooseRole(role: AppRole) {
        viewModelScope.launch(Dispatchers.IO) {
            store.setMeta("last_role", role.name)
        }
        _ui.update { it.copy(role = role, error = null) }
    }

    fun showRoleChooser() {
        _ui.update { it.copy(role = null, error = null) }
    }

    fun setTutorSessionAvailable(available: Boolean) {
        _ui.update { it.copy(hasTutorSession = available) }
    }

    fun verifyWithQr(baseUrl: String, payload: String, childAssent: Boolean) {
        verify { container.pairingRepository.verifyWithQr(baseUrl, payload, childAssent) }
    }

    fun verifyWithCode(baseUrl: String, code: String, childAssent: Boolean) {
        verify { container.pairingRepository.verifyWithCode(baseUrl, code, childAssent) }
    }

    private fun verify(block: suspend () -> PairingRecord) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, message = null) }
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { pairing ->
                    SyncScheduler.ensurePeriodic(app)
                    withContext(Dispatchers.IO) { store.setMeta("last_role", AppRole.CHILD.name) }
                    refreshLocal()
                    _ui.update {
                        it.copy(
                            busy = false,
                            pairing = pairing,
                            baseUrl = pairing.baseUrl,
                            role = AppRole.CHILD,
                            message = "Vinculación activa · ${pairing.deviceAlias}",
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(busy = false, error = error.userMessage()) }
                }
        }
    }

    fun syncNow() {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, message = null) }
            try {
                val outcome = withContext(Dispatchers.IO) { container.syncEngine.syncNow() }
                refreshLocal()
                _ui.update {
                    it.copy(
                        busy = false,
                        message = outcome.message(),
                        pending = outcome.pending,
                        lastSyncAt = it.lastSyncAt,
                    )
                }
            } catch (error: SessionRevokedException) {
                SyncScheduler.cancel(app)
                refreshLocal()
                _ui.update {
                    it.copy(busy = false, pairing = null, error = error.userMessage())
                }
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.userMessage()) }
            }
        }
    }

    fun sendTestSignal() {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, message = null) }
            try {
                val outcome = withContext(Dispatchers.IO) {
                    store.addSignal("social", listOf("gambling"), System.currentTimeMillis())
                    container.syncEngine.syncNow()
                }
                refreshLocal()
                _ui.update {
                    it.copy(
                        busy = false,
                        message = if (outcome.accepted > 0) {
                            "Señal de prueba enviada. Revisa las alertas del panel."
                        } else {
                            outcome.message()
                        },
                        pending = outcome.pending,
                    )
                }
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.userMessage()) }
            }
        }
    }

    fun unpair(codigo: String? = null) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, unlinkError = null) }
            try {
                withContext(Dispatchers.IO) {
                    container.session.authorized { api, token ->
                        val response = api.unlinkSelf("Bearer $token", UnlinkRequest(codigo))
                        if (!response.isSuccessful) throw HttpException(response)
                    }
                }
                withContext(Dispatchers.IO) { store.clearPairing() }
                SyncScheduler.cancel(app)
                GuardianForegroundService.stop(app)
                _ui.update {
                    it.copy(
                        busy = false,
                        pairing = null,
                        pending = 0,
                        lastSyncAt = null,
                        role = if (it.hasTutorSession) AppRole.TUTOR else null,
                        error = null,
                        unlinkPinRequired = false,
                        unlinkError = null,
                        unlinkAttemptsLeft = null,
                        message = "Teléfono desvinculado. Se borraron los datos locales.",
                    )
                }
            } catch (error: HttpException) {
                val pin = error.unlinkError()
                if (pin != null && pin.code in setOf("pin_required", "pin_invalid", "pin_locked")) {
                    _ui.update {
                        it.copy(
                            busy = false,
                            error = null,
                            unlinkPinRequired = true,
                            unlinkError = pin.message,
                            unlinkAttemptsLeft = pin.attemptsLeft,
                        )
                    }
                } else {
                    _ui.update { it.copy(busy = false, error = error.userMessage()) }
                }
            } catch (error: SessionRevokedException) {
                // El servidor ya la había revocado: se limpia lo local igual.
                withContext(Dispatchers.IO) { store.clearPairing() }
                SyncScheduler.cancel(app)
                GuardianForegroundService.stop(app)
                _ui.update {
                    it.copy(
                        busy = false,
                        pairing = null,
                        pending = 0,
                        role = if (it.hasTutorSession) AppRole.TUTOR else null,
                        unlinkPinRequired = false,
                        message = "Teléfono desvinculado. Se borraron los datos locales.",
                    )
                }
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.userMessage()) }
            }
        }
    }

    fun cancelUnlinkPin() {
        _ui.update { it.copy(unlinkPinRequired = false, unlinkError = null, unlinkAttemptsLeft = null) }
    }

    fun clearError() {
        _ui.update { it.copy(error = null) }
    }

    fun clearMessage() {
        _ui.update { it.copy(message = null) }
    }

    private suspend fun refreshLocal() {
        val (pairing, pending, lastSync) = withContext(Dispatchers.IO) {
            Triple(store.pairing(), store.pendingCount(), store.lastSyncAt())
        }
        if (pairing != null) {
            GuardianForegroundService.start(app)
        } else {
            GuardianForegroundService.stop(app)
        }
        _ui.update {
            it.copy(pairing = pairing, pending = pending, lastSyncAt = lastSync)
        }
    }

    private fun SyncOutcome.message(): String {
        warning?.let { return it }
        return when {
            accepted > 0 -> "Sincronizado: $accepted nuevas."
            duplicates > 0 -> "Sin novedades: $duplicates ya estaban en el panel."
            rejected > 0 -> "El panel rechazó $rejected capturas."
            else -> "Sin novedades para enviar."
        }
    }
}
