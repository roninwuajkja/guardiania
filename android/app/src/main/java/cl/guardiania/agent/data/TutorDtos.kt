package cl.guardiania.agent.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TutorLoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class TutorRegisterRequest(
    val email: String,
    val password: String,
    val alias: String,
    val consent: Boolean = true,
)

@Serializable
data class TutorRefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class TutorChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class TutorEmailRequest(val email: String)

@Serializable
data class TutorSimpleMessage(val message: String? = null)

@Serializable
data class TutorConsentDto(
    val id: String,
    val tipo: String,
    val version: String,
    val aceptado: Boolean,
    @SerialName("vinculacion_id") val vinculacionId: String? = null,
    @SerialName("creado_en") val creadoEn: String,
)

@Serializable
data class TutorProfileDto(
    val id: String,
    val email: String,
    val alias: String,
    @SerialName("email_verified") val emailVerified: Boolean = true,
    @SerialName("legal_version") val legalVersion: String = "2026-10-22",
    @SerialName("session_expires_at") val sessionExpiresAt: String? = null,
)

@Serializable
data class TutorAuthDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Int,
    val tutor: TutorProfileDto,
)

@Serializable
data class TutorRegisterDto(
    val status: String? = null,
    val message: String? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
    val tutor: TutorProfileDto? = null,
)

@Serializable
data class TutorAlertDto(
    val id: String,
    @SerialName("vinculacion_id") val bindingId: String,
    @SerialName("dispositivo_id") val deviceId: String,
    @SerialName("device_alias") val deviceAlias: String,
    @SerialName("etiqueta_menor") val childLabel: String? = null,
    val categoria: String,
    val severidad: String,
    val score: Int = 0,
    val resumen: String,
    val reglas: List<AlertRuleDto> = emptyList(),
    val estado: String,
    @SerialName("creado_en") val createdAt: String,
)

@Serializable
data class AlertRuleDto(
    val label: String? = null,
    val code: String? = null,
)

@Serializable
data class TutorDeviceDto(
    val id: String,
    val alias: String,
    @SerialName("etiqueta_menor") val childLabel: String? = null,
    val plataforma: String = "ANDROID",
    @SerialName("model_family") val modelFamily: String? = null,
    @SerialName("api_level") val apiLevel: Int = 0,
    val estado: String,
    @SerialName("vinculacion_id") val bindingId: String? = null,
    @SerialName("vinculacion_estado") val bindingState: String? = null,
    @SerialName("ultima_conexion") val lastSeen: String? = null,
    @SerialName("creado_en") val createdAt: String? = null,
    @SerialName("unlink_code_set") val unlinkCodeSet: Boolean = false,
    @SerialName("notificaciones_activas") val notificationsActive: Boolean? = null,
    val dnd: Boolean? = null,
    @SerialName("estado_reportado_en") val statusReportedAt: String? = null,
    @SerialName("ultima_notificacion_en") val lastNotificationAt: String? = null,
)

@Serializable
data class PushTestDto(
    val enabled: Boolean,
    val sent: Int = 0,
    val invalid: Int = 0,
    val tokens: Int = 0,
    val scheduled: Boolean = false,
    @SerialName("delay_seconds") val delaySeconds: Int = 0,
)

@Serializable
data class TutorOverviewDto(
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("status_line") val statusLine: String,
    @SerialName("status_tone") val statusTone: String,
    @SerialName("devices_active") val devicesActive: Int,
    @SerialName("minutes_today") val minutesToday: Int,
    @SerialName("open_alerts") val openAlerts: Int,
    @SerialName("critical_alerts") val criticalAlerts: Int,
    val week: List<DayPointDto> = emptyList(),
    val alerts: List<TutorAlertDto> = emptyList(),
    val devices: List<TutorDeviceDto> = emptyList(),
)

@Serializable
data class DayPointDto(
    val date: String,
    val minutes: Int,
    val alerts: Int,
)

@Serializable
data class AlertStateRequest(val estado: String)

@Serializable
data class TutorUnlinkCodeRequest(val codigo: String)

@Serializable
data class TutorPairingRequest(
    @SerialName("etiqueta_menor") val childLabel: String? = null,
    @SerialName("ttl_seconds") val ttlSeconds: Int = 600,
    @SerialName("tutor_declara_tutoria") val declaresGuardianship: Boolean = true,
)

@Serializable
data class TutorPairingDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("qr_payload") val qrPayload: String,
    @SerialName("qr_svg") val qrSvg: String? = null,
    @SerialName("manual_code") val manualCode: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("ws_url") val wsUrl: String? = null,
    @SerialName("ws_ticket") val wsTicket: String? = null,
)

@Serializable
data class TutorPairingStatusDto(
    @SerialName("session_id") val sessionId: String,
    val estado: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("vinculacion_id") val bindingId: String? = null,
    @SerialName("vinculacion_estado") val bindingState: String? = null,
    @SerialName("device_alias") val deviceAlias: String? = null,
)

@Serializable
data class RealtimeTicketDto(
    @SerialName("ws_url") val wsUrl: String,
    @SerialName("ws_ticket") val wsTicket: String,
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class PushTokenRequestDto(
    val token: String,
    val plataforma: String = "ANDROID",
    @SerialName("device_label") val deviceLabel: String? = null,
)

@Serializable
data class PushTokenDto(
    val id: String,
    val plataforma: String,
    val activo: Boolean,
    @SerialName("device_label") val deviceLabel: String? = null,
    @SerialName("creado_en") val createdAt: String,
    @SerialName("actualizado_en") val updatedAt: String,
)
