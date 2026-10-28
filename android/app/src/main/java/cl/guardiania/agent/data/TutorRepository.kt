package cl.guardiania.agent.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

class TutorSessionExpiredException : IllegalStateException("La sesión del tutor expiró")

data class TutorRegistrationResult(
    val session: TutorSessionRecord?,
    val message: String?,
)

/**
 * Tutor API client. Access tokens are short-lived; refresh tokens are rotated
 * server-side and stored in the SQLCipher database, never in plain preferences.
 */
class TutorRepository(private val store: SecureStore) {

    private val refreshMutex = Mutex()

    fun session(): TutorSessionRecord? = store.tutorSession()

    suspend fun login(baseUrl: String, email: String, password: String): TutorSessionRecord {
        val normalized = ApiFactory.normalizeBaseUrl(baseUrl)
        val response = ApiFactory.create(normalized).tutorLogin(
            TutorLoginRequest(email.trim().lowercase(), password),
        )
        return persist(normalized, response)
    }

    suspend fun register(baseUrl: String, email: String, password: String, alias: String): TutorRegistrationResult {
        val normalized = ApiFactory.normalizeBaseUrl(baseUrl)
        val response = ApiFactory.create(normalized).tutorRegister(
            TutorRegisterRequest(email.trim().lowercase(), password, alias.trim(), consent = true),
        )
        val tutor = response.tutor
        val access = response.accessToken
        val refresh = response.refreshToken
        if (tutor != null && access != null && refresh != null) {
            val record = TutorSessionRecord(
                baseUrl = normalized,
                accessToken = access,
                refreshToken = refresh,
                tutorId = tutor.id,
                email = tutor.email,
                alias = tutor.alias,
                emailVerified = tutor.emailVerified,
                expiresAt = System.currentTimeMillis() + (response.expiresIn ?: 1800) * 1000L,
            )
            store.saveTutorSession(record)
            return TutorRegistrationResult(record, null)
        }
        return TutorRegistrationResult(
            session = null,
            message = response.message ?: "Revisa tu correo para verificar la cuenta antes de entrar.",
        )
    }

    suspend fun logout() {
        val session = store.tutorSession()
        if (session != null) {
            try {
                ApiFactory.create(session.baseUrl).tutorLogout(
                    "Bearer ${session.accessToken}",
                    TutorRefreshRequest(session.refreshToken),
                )
            } catch (_: Exception) {
                // The local credentials are removed even if the network is down.
            }
        }
        store.clearTutorSession()
    }

    suspend fun me(): TutorProfileDto = authorized { api, token -> api.tutorMe("Bearer $token") }

    suspend fun overview(): TutorOverviewDto = authorized { api, token ->
        api.tutorOverview("Bearer $token")
    }

    suspend fun alerts(): List<TutorAlertDto> = authorized { api, token ->
        api.tutorAlerts("Bearer $token")
    }

    suspend fun devices(): List<TutorDeviceDto> = authorized { api, token ->
        api.tutorDevices("Bearer $token")
    }

    suspend fun updateAlert(alertId: String, state: String): TutorAlertDto = authorized { api, token ->
        api.tutorUpdateAlert("Bearer $token", alertId, AlertStateRequest(state))
    }

    suspend fun revoke(bindingId: String) {
        authorized { api, token ->
            val response = api.tutorRevoke("Bearer $token", bindingId)
            if (!response.isSuccessful) throw HttpException(response)
        }
    }

    suspend fun generatePairing(childLabel: String?, declaresGuardianship: Boolean): TutorPairingDto =
        authorized { api, token ->
            api.tutorGeneratePairing(
                "Bearer $token",
                TutorPairingRequest(
                    childLabel = childLabel?.trim()?.takeIf(String::isNotBlank),
                    declaresGuardianship = declaresGuardianship,
                ),
            )
        }

    suspend fun pairingStatus(sessionId: String): TutorPairingStatusDto = authorized { api, token ->
        api.tutorPairingStatus("Bearer $token", sessionId)
    }

    suspend fun cancelPairing(sessionId: String) {
        authorized { api, token ->
            val response = api.tutorCancelPairing("Bearer $token", sessionId)
            if (!response.isSuccessful) throw HttpException(response)
        }
    }

    suspend fun consents(): List<TutorConsentDto> = authorized { api, token ->
        api.tutorConsents("Bearer $token")
    }

    /**
     * Changing the password revokes every session server-side, so the local
     * credentials are cleared right after a successful call.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String) {
        authorized { api, token ->
            val response = api.tutorChangePassword(
                "Bearer $token",
                TutorChangePasswordRequest(currentPassword, newPassword),
            )
            if (!response.isSuccessful) throw HttpException(response)
        }
        store.clearTutorSession()
    }

    /** Revokes this and every other tutor session; the phone returns to the login screen. */
    suspend fun logoutAll() {
        authorized { api, token ->
            val response = api.tutorLogoutAll("Bearer $token")
            if (!response.isSuccessful) throw HttpException(response)
        }
        store.clearTutorSession()
    }

    /** Generic 202 answer; the server never reveals whether the email exists. */
    suspend fun resendVerification(baseUrl: String, email: String): String =
        ApiFactory.create(ApiFactory.normalizeBaseUrl(baseUrl))
            .tutorResendVerification(TutorEmailRequest(email.trim().lowercase()))
            .message.orEmpty()

    suspend fun exportJson(): String = authorized { api, token ->
        val response = api.tutorExport("Bearer $token")
        if (!response.isSuccessful) throw HttpException(response)
        response.body()?.string().orEmpty()
    }

    suspend fun realtimeTicket(): RealtimeTicketDto = authorized { api, token ->
        api.tutorRealtimeTicket("Bearer $token")
    }

    suspend fun registerPushToken(fcmToken: String, label: String?) {
        authorized { api, accessToken ->
            api.tutorRegisterPushToken(
                "Bearer $accessToken",
                PushTokenRequestDto(token = fcmToken, deviceLabel = label?.take(60)),
            )
        }
    }

    suspend fun unregisterPushToken(fcmToken: String) {
        authorized { api, accessToken ->
            val response = api.tutorUnregisterPushToken(
                "Bearer $accessToken",
                PushTokenRequestDto(token = fcmToken),
            )
            if (!response.isSuccessful && response.code() != 401) throw HttpException(response)
        }
    }

    private fun persist(baseUrl: String, response: TutorAuthDto): TutorSessionRecord {
        val refresh = response.refreshToken ?: throw IllegalStateException("El servidor no entregó una sesión renovable.")
        val record = TutorSessionRecord(
            baseUrl = baseUrl,
            accessToken = response.accessToken,
            refreshToken = refresh,
            tutorId = response.tutor.id,
            email = response.tutor.email,
            alias = response.tutor.alias,
            emailVerified = response.tutor.emailVerified,
            expiresAt = System.currentTimeMillis() + response.expiresIn * 1000L,
        )
        store.saveTutorSession(record)
        return record
    }

    private suspend fun <T> authorized(block: suspend (GuardianApi, String) -> T): T {
        val session = store.tutorSession() ?: throw TutorSessionExpiredException()
        val api = ApiFactory.create(session.baseUrl)
        return try {
            block(api, session.accessToken)
        } catch (error: HttpException) {
            if (error.code() != 401) throw error
            val next = refreshMutex.withLock {
                val current = store.tutorSession() ?: throw TutorSessionExpiredException()
                // Another request may have rotated the session while this one waited.
                if (current.accessToken != session.accessToken) {
                    current
                } else {
                    val refreshed = try {
                        ApiFactory.create(current.baseUrl)
                            .tutorRefresh(TutorRefreshRequest(current.refreshToken))
                    } catch (refreshError: HttpException) {
                        if (refreshError.code() == 401 || refreshError.code() == 403) {
                            store.clearTutorSession()
                            throw TutorSessionExpiredException()
                        }
                        throw refreshError
                    }
                    TutorSessionRecord(
                        baseUrl = current.baseUrl,
                        accessToken = refreshed.accessToken,
                        refreshToken = refreshed.refreshToken ?: current.refreshToken,
                        tutorId = refreshed.tutor.id,
                        email = refreshed.tutor.email,
                        alias = refreshed.tutor.alias,
                        emailVerified = refreshed.tutor.emailVerified,
                        expiresAt = System.currentTimeMillis() + refreshed.expiresIn * 1000L,
                    ).also(store::saveTutorSession)
                }
            }
            try {
                block(ApiFactory.create(next.baseUrl), next.accessToken)
            } catch (retryError: HttpException) {
                if (retryError.code() == 401) {
                    store.clearTutorSession()
                    throw TutorSessionExpiredException()
                }
                throw retryError
            }
        }
    }
}
