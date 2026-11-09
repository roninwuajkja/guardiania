package cl.guardiania.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.guardiania.agent.data.TutorAlertDto
import cl.guardiania.agent.data.TutorConsentDto
import cl.guardiania.agent.data.TutorDeviceDto
import cl.guardiania.agent.data.TutorOverviewDto
import cl.guardiania.agent.data.TutorPairingDto
import cl.guardiania.agent.data.TutorPairingStatusDto
import cl.guardiania.agent.data.TutorSessionExpiredException
import cl.guardiania.agent.data.TutorSessionRecord
import cl.guardiania.agent.data.tutorMessage
import cl.guardiania.agent.notifications.TutorNotifications
import cl.guardiania.agent.realtime.TutorRealtimeAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TutorTab(val label: String) {
    INICIO("Inicio"),
    ALERTAS("Alertas"),
    EQUIPOS("Equipos"),
    AJUSTES("Ajustes"),
}

enum class TutorAuthMode { LOGIN, REGISTER }

data class TutorUiState(
    val ready: Boolean = false,
    val session: TutorSessionRecord? = null,
    val baseUrl: String = DEFAULT_BASE_URL,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val tab: TutorTab = TutorTab.INICIO,
    val authMode: TutorAuthMode = TutorAuthMode.LOGIN,
    val overview: TutorOverviewDto? = null,
    val alerts: List<TutorAlertDto> = emptyList(),
    val devices: List<TutorDeviceDto> = emptyList(),
    val consents: List<TutorConsentDto> = emptyList(),
    val pairing: TutorPairingDto? = null,
    val pairingStatus: TutorPairingStatusDto? = null,
    val consentChecked: Boolean = false,
    val registrationPending: String? = null,
)

class TutorViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as GuardianApp).container
    private val repo = container.tutorRepository
    private val store = container.store
    private val _ui = MutableStateFlow(TutorUiState())
    val ui = _ui.asStateFlow()
    private var pollJob: Job? = null
    private var alertRefreshJob: Job? = null
    private var dashboardLoading = false
    private var lastDashboardLoadAt = 0L

    init {
        viewModelScope.launch {
            container.alertEvents.collect { onRealtimeAlert(it) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val session = repo.session()
            val baseUrl = store.meta("base_url_hint") ?: DEFAULT_BASE_URL
            _ui.value = TutorUiState(ready = true, session = session, baseUrl = baseUrl)
            if (session != null) {
                registerPush()
                loadDashboard()
            }
        }
    }

    /** Inicia el stream de alertas; la actividad lo llama solo en primer plano. */
    fun startRealtime() {
        if (_ui.value.session == null) return
        container.alertStream.start()
    }

    fun stopRealtime() {
        container.alertStream.stop()
    }

    private suspend fun registerPush() {
        if (repo.session() == null) return
        container.pushRegistrar.register()
    }

    private fun onRealtimeAlert(event: TutorRealtimeAlert) {
        val alias = event.deviceAlias?.takeIf { it.isNotBlank() } ?: "un equipo"
        if (event.kind == "device_reconnected") {
            _ui.update { it.copy(message = "$alias volvió a conectarse") }
            TutorNotifications.showReconnected(getApplication<Application>(), alias)
        } else {
            _ui.update {
                it.copy(message = "Nueva alerta ${severityLabel(event.severity)} · $alias")
            }
        }
        alertRefreshJob?.cancel()
        alertRefreshJob = viewModelScope.launch {
            delay(400)
            runCatching {
                withContext(Dispatchers.IO) { Pair(repo.alerts(), repo.overview()) }
            }.onSuccess { (alerts, overview) ->
                lastDashboardLoadAt = System.currentTimeMillis()
                _ui.update { it.copy(alerts = alerts, overview = overview) }
            }
        }
    }

    private fun severityLabel(value: String?): String = when (value?.uppercase()) {
        "CRITICA" -> "crítica"
        "ALTA" -> "alta"
        "MEDIA" -> "media"
        "BAJA" -> "baja"
        else -> "de riesgo"
    }

    fun setAuthMode(mode: TutorAuthMode) = _ui.update { it.copy(authMode = mode, error = null, registrationPending = null) }

    fun setConsent(value: Boolean) = _ui.update { it.copy(consentChecked = value) }

    fun login(baseUrl: String, email: String, password: String) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, message = null) }
            try {
                val session = withContext(Dispatchers.IO) { repo.login(baseUrl, email, password) }
                _ui.update { it.copy(busy = false, session = session, baseUrl = session.baseUrl, message = "Sesión iniciada") }
                loadDashboard()
                viewModelScope.launch(Dispatchers.IO) { registerPush() }
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.tutorMessage()) }
            }
        }
    }

    fun register(baseUrl: String, email: String, password: String, alias: String) {
        if (_ui.value.busy) return
        if (!_ui.value.consentChecked) {
            _ui.update { it.copy(error = "Debes aceptar los términos y la política de privacidad.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, registrationPending = null) }
            try {
                val result = withContext(Dispatchers.IO) { repo.register(baseUrl, email, password, alias) }
                if (result.session != null) {
                    _ui.update {
                        it.copy(
                            busy = false,
                            session = result.session,
                            baseUrl = result.session.baseUrl,
                            message = "Cuenta creada",
                        )
                    }
                    loadDashboard()
                    viewModelScope.launch(Dispatchers.IO) { registerPush() }
                } else {
                    _ui.update {
                        it.copy(
                            busy = false,
                            registrationPending = result.message,
                            authMode = TutorAuthMode.LOGIN,
                        )
                    }
                }
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.tutorMessage()) }
            }
        }
    }

    fun refresh() {
        if (_ui.value.busy) return
        loadDashboard()
    }

    fun testPush() {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, message = null) }
            try {
                val result = withContext(Dispatchers.IO) { repo.testPush(delaySeconds = 10) }
                val text = when {
                    !result.enabled -> "FCM no está configurado en el servidor."
                    result.tokens == 0 -> "Este teléfono aún no tiene token. Abre la app de nuevo."
                    result.invalid > 0 && result.sent == 0 && !result.scheduled ->
                        "El token de este teléfono ya no es válido. Abre la app de nuevo."
                    result.scheduled -> "Push programada (${result.tokens}). Cierra la app; llega en ${result.delaySeconds} s."
                    result.sent > 0 -> "Push enviada (${result.sent}). Cierra la app para verla."
                    else -> "El servidor no pudo enviar la push."
                }
                _ui.update { it.copy(busy = false, message = text) }
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.tutorMessage()) }
            }
        }
    }

    /**
     * Refresca el panel sin mostrar el indicador de carga.
     *
     * Se usa al volver a primer plano y al tocar la pestaña Alertas. El push
     * de FCM entra por el mismo evento en vivo que el WebSocket. El umbral
     * evita ráfagas si la app entra y sale varias veces seguidas.
     */
    fun refreshIfStale(maxAgeMs: Long = 20_000) {
        if (_ui.value.session == null || dashboardLoading) return
        if (System.currentTimeMillis() - lastDashboardLoadAt < maxAgeMs) return
        loadDashboard(silent = true)
    }

    private fun loadDashboard(silent: Boolean = false) {
        if (dashboardLoading) return
        dashboardLoading = true
        viewModelScope.launch {
            if (!silent) _ui.update { it.copy(busy = true, error = null) }
            try {
                val (overview, alerts, devices) = withContext(Dispatchers.IO) {
                    Triple(repo.overview(), repo.alerts(), repo.devices())
                }
                lastDashboardLoadAt = System.currentTimeMillis()
                _ui.update {
                    it.copy(
                        busy = false,
                        overview = overview,
                        alerts = alerts,
                        devices = devices,
                        session = repo.session() ?: it.session,
                    )
                }
                // Consent history is a convenience view; a failure there must not kill the dashboard.
                runCatching { withContext(Dispatchers.IO) { repo.consents() } }
                    .onSuccess { consents -> _ui.update { it.copy(consents = consents) } }
            } catch (error: TutorSessionExpiredException) {
                _ui.update {
                    it.copy(
                        busy = false,
                        session = null,
                        overview = null,
                        alerts = emptyList(),
                        devices = emptyList(),
                        consents = emptyList(),
                        error = "La sesión expiró. Vuelve a entrar.",
                    )
                }
            } catch (error: Exception) {
                if (silent) {
                    _ui.update { it.copy(busy = false) }
                } else {
                    _ui.update { it.copy(busy = false, error = error.tutorMessage()) }
                }
            } finally {
                dashboardLoading = false
            }
        }
    }

    fun selectTab(tab: TutorTab) {
        _ui.update { it.copy(tab = tab) }
        if (tab == TutorTab.ALERTAS) refreshIfStale(10_000)
        if (tab == TutorTab.AJUSTES && _ui.value.consents.isEmpty()) loadConsents()
    }

    private fun loadConsents() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.consents() } }
                .onSuccess { consents -> _ui.update { it.copy(consents = consents) } }
        }
    }

    fun changePassword(currentPassword: String, newPassword: String) {
        if (_ui.value.busy) return
        if (newPassword.length < 12) {
            _ui.update { it.copy(error = "La contraseña nueva debe tener al menos 12 caracteres.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                withContext(Dispatchers.IO) {
                    container.pushRegistrar.unregister()
                    repo.changePassword(currentPassword, newPassword)
                }
                _ui.update {
                    it.copy(
                        busy = false,
                        session = null,
                        consents = emptyList(),
                        authMode = TutorAuthMode.LOGIN,
                        message = "Contraseña cambiada. Entra con la nueva contraseña.",
                        overview = null,
                        alerts = emptyList(),
                        devices = emptyList(),
                    )
                }
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.tutorMessage()) }
            }
        }
    }

    fun logoutAll() {
        viewModelScope.launch {
            pollJob?.cancel()
            alertRefreshJob?.cancel()
            stopRealtime()
            _ui.update { it.copy(busy = true, error = null) }
            try {
                withContext(Dispatchers.IO) {
                    container.pushRegistrar.unregister()
                    repo.logoutAll()
                }
                _ui.update {
                    it.copy(
                        busy = false,
                        session = null,
                        consents = emptyList(),
                        authMode = TutorAuthMode.LOGIN,
                        message = "Sesión cerrada en todos los dispositivos.",
                        overview = null,
                        alerts = emptyList(),
                        devices = emptyList(),
                    )
                }
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.tutorMessage()) }
            }
        }
    }

    fun resendVerification() {
        val email = _ui.value.session?.email
        if (email.isNullOrBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, message = null) }
            try {
                val message = withContext(Dispatchers.IO) {
                    repo.resendVerification(_ui.value.baseUrl, email)
                }
                _ui.update {
                    it.copy(
                        busy = false,
                        message = message.ifBlank { "Si corresponde, enviamos un nuevo enlace." },
                    )
                }
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.tutorMessage()) }
            }
        }
    }

    fun export(onReady: (String) -> Unit) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                val json = withContext(Dispatchers.IO) { repo.exportJson() }
                if (json.isBlank()) throw IllegalStateException("La exportación llegó vacía.")
                _ui.update { it.copy(busy = false, message = "Exportación lista") }
                onReady(json)
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.tutorMessage()) }
            }
        }
    }

    fun updateAlert(alertId: String, state: String) {
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { repo.updateAlert(alertId, state) }
                _ui.update {
                    it.copy(
                        alerts = it.alerts.map { alert -> if (alert.id == updated.id) updated else alert },
                        overview = it.overview?.copy(
                            alerts = it.overview.alerts.map { alert -> if (alert.id == updated.id) updated else alert },
                        ),
                        message = when (state) {
                            "DESCARTADA" -> "Alerta descartada"
                            "RECONOCIDA" -> "Alerta reconocida"
                            else -> "Alerta marcada como vista"
                        },
                    )
                }
            } catch (error: Exception) {
                _ui.update { it.copy(error = error.tutorMessage()) }
            }
        }
    }

    fun revokeDevice(bindingId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                withContext(Dispatchers.IO) { repo.revoke(bindingId) }
                loadDashboard()
                _ui.update { it.copy(busy = false, message = "Dispositivo revocado") }
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.tutorMessage()) }
            }
        }
    }

    fun setUnlinkCode(bindingId: String, code: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, message = null) }
            try {
                withContext(Dispatchers.IO) { repo.setUnlinkCode(bindingId, code) }
                loadDashboard()
                _ui.update { it.copy(busy = false, message = "Código de desvinculación activo") }
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.tutorMessage()) }
            }
        }
    }

    fun clearUnlinkCode(bindingId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, message = null) }
            try {
                withContext(Dispatchers.IO) { repo.clearUnlinkCode(bindingId) }
                loadDashboard()
                _ui.update { it.copy(busy = false, message = "Código de desvinculación eliminado") }
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.tutorMessage()) }
            }
        }
    }

    fun generatePairing(childLabel: String, declaresGuardianship: Boolean) {
        if (_ui.value.busy) return
        if (!declaresGuardianship) {
            _ui.update { it.copy(error = "Debes declarar que ejerces la tutoría legal del menor.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, pairing = null, pairingStatus = null) }
            try {
                val pairing = withContext(Dispatchers.IO) {
                    repo.generatePairing(childLabel, declaresGuardianship = true)
                }
                _ui.update { it.copy(busy = false, pairing = pairing, tab = TutorTab.EQUIPOS) }
                startPolling(pairing.sessionId)
            } catch (error: Exception) {
                _ui.update { it.copy(busy = false, error = error.tutorMessage()) }
            }
        }
    }

    private fun startPolling(sessionId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            repeat(150) {
                delay(2000)
                try {
                    val status = withContext(Dispatchers.IO) { repo.pairingStatus(sessionId) }
                    _ui.update { it.copy(pairingStatus = status) }
                    if (status.estado == "VERIFICADA" || status.estado == "ACTIVO") {
                        _ui.update { it.copy(message = "Dispositivo vinculado") }
                        loadDashboard()
                        return@launch
                    }
                    if (status.estado == "EXPIRADA" || status.estado == "CANCELADA") return@launch
                } catch (_: Exception) {
                    // Transient polling failures are retried until the pairing expires.
                }
            }
        }
    }

    fun cancelPairing() {
        val pairing = _ui.value.pairing ?: return
        viewModelScope.launch {
            pollJob?.cancel()
            try {
                withContext(Dispatchers.IO) { repo.cancelPairing(pairing.sessionId) }
            } catch (_: Exception) {
                // The code expires server-side even if cancel cannot reach it.
            }
            _ui.update { it.copy(pairing = null, pairingStatus = null) }
        }
    }

    fun clearPairing() {
        pollJob?.cancel()
        _ui.update { it.copy(pairing = null, pairingStatus = null) }
    }

    fun logout() {
        viewModelScope.launch {
            pollJob?.cancel()
            alertRefreshJob?.cancel()
            stopRealtime()
            withContext(Dispatchers.IO) {
                container.pushRegistrar.unregister()
                repo.logout()
            }
            _ui.update {
                TutorUiState(ready = true, baseUrl = it.baseUrl, authMode = TutorAuthMode.LOGIN)
            }
        }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun clearRegistrationPending() = _ui.update { it.copy(registrationPending = null) }

    override fun onCleared() {
        stopRealtime()
        super.onCleared()
    }
}
