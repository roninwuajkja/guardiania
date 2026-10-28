package cl.guardiania.agent.data

import retrofit2.HttpException

/**
 * Adds the device bearer token and refreshes it once when the API answers 401.
 * A revoked binding clears the local pairing so the person can pair again.
 */
class DeviceSession(private val store: SecureStore) {

    suspend fun <T> authorized(block: suspend (GuardianApi, String) -> T): T {
        val pairing = store.pairing() ?: throw NotPairedException()
        val api = ApiFactory.create(pairing.baseUrl)
        return try {
            block(api, pairing.accessToken)
        } catch (error: HttpException) {
            if (error.code() == 410) {
                store.clearPairing()
                throw SessionRevokedException()
            }
            if (error.code() != 401) throw error
            val refreshed = try {
                api.refresh(RefreshRequest(pairing.refreshToken))
            } catch (refreshError: HttpException) {
                if (refreshError.code() == 401 || refreshError.code() == 403) {
                    store.clearPairing()
                    throw SessionRevokedException()
                }
                throw refreshError
            }
            store.updateAccessToken(refreshed.accessToken)
            block(api, refreshed.accessToken)
        }
    }
}
