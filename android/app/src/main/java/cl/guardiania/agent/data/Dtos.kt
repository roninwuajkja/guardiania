package cl.guardiania.agent.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val platform: String = "ANDROID",
    @SerialName("api_level") val apiLevel: Int,
    @SerialName("model_family") val modelFamily: String,
    @SerialName("install_id") val installId: String,
    @SerialName("attestation_token") val attestationToken: String? = null,
)

@Serializable
data class VerifyRequest(
    @SerialName("qr_token") val qrToken: String? = null,
    @SerialName("manual_code") val manualCode: String? = null,
    val device: DeviceInfo,
    @SerialName("child_assent") val childAssent: Boolean = false,
)

@Serializable
data class PolicySummary(
    @SerialName("retencion_dias") val retentionDays: Int = 90,
    @SerialName("captura_texto") val capturesText: Boolean = false,
    val categorias: List<String> = emptyList(),
)

@Serializable
data class VerifyResponse(
    val status: String,
    @SerialName("vinculacion_id") val bindingId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("tutor_alias") val tutorAlias: String,
    @SerialName("device_alias") val deviceAlias: String,
    val policy: PolicySummary = PolicySummary(),
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class UnlinkRequest(
    val codigo: String? = null,
)

@Serializable
data class DeviceStatusRequest(
    @SerialName("notificaciones_activas") val notificacionesActivas: Boolean,
    @SerialName("uso_activo") val usoActivo: Boolean,
    val dnd: Boolean,
    val accesibilidad: Boolean,
    @SerialName("ultima_notificacion_en") val ultimaNotificacionEn: String? = null,
)

@Serializable
data class RefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class TimeWindow(
    val start: String,
    val end: String,
    @SerialName("tz_offset_minutes") val tzOffsetMinutes: Int,
)

@Serializable
data class IntegrityInfo(
    @SerialName("attestation_ok") val attestationOk: Boolean = true,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("clock_skew_seconds") val clockSkewSeconds: Int? = null,
)

@Serializable
data class CaptureVector(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("event_id") val eventId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("time_window") val timeWindow: TimeWindow,
    val category: String,
    @SerialName("lexical_flag_count") val lexicalFlagCount: Int,
    val flags: List<String> = emptyList(),
    @SerialName("hashed_contact_id") val hashedContactId: String? = null,
    @SerialName("usage_duration_minutes") val usageDurationMinutes: Int = 0,
    @SerialName("notification_count") val notificationCount: Int = 0,
    @SerialName("event_count") val eventCount: Int = 0,
    val integrity: IntegrityInfo = IntegrityInfo(),
)

@Serializable
data class SyncRequest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("batch_id") val batchId: String,
    @SerialName("device_clock") val deviceClock: String,
    val captures: List<CaptureVector>,
)

@Serializable
data class RejectedItem(
    @SerialName("event_id") val eventId: String,
    val code: String,
)

@Serializable
data class SyncResponse(
    val accepted: Int,
    val duplicates: Int,
    val rejected: List<RejectedItem> = emptyList(),
    @SerialName("server_time") val serverTime: String,
    @SerialName("clock_offset_seconds") val clockOffsetSeconds: Int,
    @SerialName("next_sync_after_seconds") val nextSyncAfterSeconds: Int = 900,
)

@Serializable
data class RiskEvaluationResult(
    @SerialName("nivel_riesgo") val riskLevel: String,
    val categoria: String,
    @SerialName("descripcion_narrativa") val narrative: String,
    @SerialName("consejo_educativo") val advice: String,
)

@Serializable
data class EvaluationResponse(
    @SerialName("event_id") val eventId: String,
    val evaluation: RiskEvaluationResult,
    @SerialName("alert_id") val alertId: String? = null,
    val severidad: String,
    val provider: String,
    val cached: Boolean = false,
    @SerialName("latency_ms") val latencyMs: Int = 0,
    val notified: Boolean = false,
    @SerialName("server_time") val serverTime: String,
)
