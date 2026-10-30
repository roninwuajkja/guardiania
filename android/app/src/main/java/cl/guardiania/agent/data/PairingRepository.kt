package cl.guardiania.agent.data

import android.net.Uri
import android.os.Build

class PairingRepository(private val store: SecureStore) {

    suspend fun verifyWithQr(baseUrl: String, rawPayload: String, childAssent: Boolean): PairingRecord {
        val token = extractToken(rawPayload)
        return verify(baseUrl, VerifyRequest(qrToken = token, device = deviceInfo(), childAssent = childAssent))
    }

    suspend fun verifyWithCode(baseUrl: String, manualCode: String, childAssent: Boolean): PairingRecord {
        val digits = manualCode.filter(Char::isDigit)
        require(digits.length == 6) { "El código debe tener 6 dígitos." }
        return verify(baseUrl, VerifyRequest(manualCode = digits, device = deviceInfo(), childAssent = childAssent))
    }

    private suspend fun verify(baseUrl: String, request: VerifyRequest): PairingRecord {
        val normalized = ApiFactory.normalizeBaseUrl(baseUrl)
        val response = ApiFactory.create(normalized).verify(request)
        val record = PairingRecord(
            baseUrl = normalized,
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            deviceId = response.deviceId,
            bindingId = response.bindingId,
            deviceAlias = response.deviceAlias,
            tutorAlias = response.tutorAlias,
            linkedAt = System.currentTimeMillis(),
        )
        store.savePairing(record)
        return record
    }

    private fun deviceInfo(): DeviceInfo = DeviceInfo(
        apiLevel = Build.VERSION.SDK_INT,
        modelFamily = Build.MODEL
            .replace(Regex("[\\p{Cntrl}]"), " ")
            .trim()
            .ifBlank { "Android" }
            .take(60),
        installId = store.installId(),
    )

    private fun extractToken(raw: String): String {
        val value = raw.trim()
        val token = if (value.startsWith("guardiania://", ignoreCase = true)) {
            Uri.parse(value).getQueryParameter("t")
        } else if (TOKEN_ONLY.matches(value)) {
            value
        } else {
            null
        }
        // Acepta el token corto actual (uuid.HMAC) y el JWT anterior.
        require(!token.isNullOrBlank() && token.length >= 20 && TOKEN_ONLY.matches(token)) {
            "Ese QR no pertenece a una vinculación de IA Guardian."
        }
        return token
    }

    private companion object {
        val TOKEN_ONLY = Regex("^[A-Za-z0-9._-]{20,}$")
    }
}
