import uuid
from datetime import datetime
from typing import Literal

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator, model_validator


class TutorOut(BaseModel):
    id: uuid.UUID
    email: str
    alias: str
    email_verified: bool = True
    legal_version: str = "2026-10-22"
    session_id: uuid.UUID | None = None
    session_expires_at: datetime | None = None


class RegisterRequest(BaseModel):
    email: str = Field(min_length=5, max_length=255)
    password: str = Field(min_length=12, max_length=128)
    alias: str = Field(min_length=2, max_length=60)
    consent: bool

    @field_validator("email")
    @classmethod
    def validate_email(cls, value: str) -> str:
        normalized = value.strip().lower()
        if "@" not in normalized or "." not in normalized.split("@")[-1]:
            raise ValueError("Correo inválido")
        return normalized

    @model_validator(mode="after")
    def validate_consent(self):
        if not self.consent:
            raise ValueError("Debes aceptar el consentimiento informado")
        return self


class LoginRequest(BaseModel):
    email: str = Field(min_length=5, max_length=255)
    password: str = Field(min_length=1, max_length=128)

    @field_validator("email")
    @classmethod
    def normalize_email(cls, value: str) -> str:
        return value.strip().lower()


class AuthResponse(BaseModel):
    access_token: str
    refresh_token: str | None = None
    token_type: str = "bearer"
    expires_in: int
    tutor: TutorOut


class RegisterPendingResponse(BaseModel):
    status: Literal["VERIFICAR_CORREO"] = "VERIFICAR_CORREO"
    message: str


class TutorRefreshRequest(BaseModel):
    refresh_token: str | None = Field(default=None, min_length=20, max_length=256)


class VerifyEmailRequest(BaseModel):
    token: str = Field(min_length=20, max_length=256)


class ForgotPasswordRequest(BaseModel):
    email: str = Field(min_length=5, max_length=255)

    @field_validator("email")
    @classmethod
    def normalize_email(cls, value: str) -> str:
        return value.strip().lower()


class ResetPasswordRequest(BaseModel):
    token: str = Field(min_length=20, max_length=256)
    new_password: str = Field(min_length=12, max_length=128)


class ChangePasswordRequest(BaseModel):
    current_password: str = Field(min_length=1, max_length=128)
    new_password: str = Field(min_length=12, max_length=128)


class PairingGenerateRequest(BaseModel):
    etiqueta_menor: str | None = Field(default=None, max_length=40)
    ttl_seconds: int = Field(default=600, ge=120, le=600)
    tutor_declara_tutoria: bool = False


class PairingGenerateResponse(BaseModel):
    session_id: uuid.UUID
    qr_payload: str
    qr_svg: str
    manual_code: str
    expires_at: datetime
    ws_url: str
    ws_ticket: str


class DeviceInfo(BaseModel):
    model_config = ConfigDict(extra="forbid")

    platform: Literal["ANDROID"]
    api_level: int = Field(ge=26, le=36)
    model_family: str = Field(min_length=1, max_length=60)
    install_id: uuid.UUID
    attestation_token: str | None = Field(default=None, max_length=4096)


class PairingVerifyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    qr_token: str | None = Field(default=None, max_length=4096)
    manual_code: str | None = Field(default=None, pattern=r"^\d{6}$")
    device: DeviceInfo
    child_assent: bool = False

    @model_validator(mode="after")
    def exactly_one_credential(self):
        if bool(self.qr_token) == bool(self.manual_code):
            raise ValueError("Debes enviar exactamente uno de qr_token o manual_code")
        return self


class PolicySummary(BaseModel):
    retencion_dias: int = 90
    captura_texto: Literal[False] = False
    categorias: list[str] = Field(default_factory=list)


class PairingVerifyResponse(BaseModel):
    status: Literal["ACTIVO"]
    vinculacion_id: uuid.UUID
    device_id: uuid.UUID
    access_token: str
    refresh_token: str
    expires_in: int
    tutor_alias: str
    device_alias: str
    policy: PolicySummary


class RefreshRequest(BaseModel):
    refresh_token: str = Field(min_length=20, max_length=256)


class DeviceRefreshResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int


class TimeWindow(BaseModel):
    start: AwareDatetime
    end: AwareDatetime
    tz_offset_minutes: int = Field(ge=-840, le=840)

    @model_validator(mode="after")
    def coherent_window(self):
        if self.end <= self.start:
            raise ValueError("end debe ser posterior a start")
        if (self.end - self.start).total_seconds() > 3600:
            raise ValueError("La ventana no puede superar 60 minutos")
        return self


class IntegrityInfo(BaseModel):
    attestation_ok: bool = True
    app_version: str | None = Field(default=None, max_length=32)
    clock_skew_seconds: int | None = None


class DerivedFeatureVector(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal[1] = 1
    event_id: uuid.UUID
    device_id: uuid.UUID
    time_window: TimeWindow
    category: str = Field(min_length=1, max_length=40)
    lexical_flag_count: int = Field(ge=0, le=64)
    flags: list[str] = Field(default_factory=list, max_length=16)
    hashed_contact_id: str | None = Field(default=None, max_length=44)
    usage_duration_minutes: int = Field(ge=0, le=1440)
    notification_count: int = Field(default=0, ge=0, le=10_000)
    event_count: int = Field(default=0, ge=0, le=100_000)
    integrity: IntegrityInfo = Field(default_factory=IntegrityInfo)

    @field_validator("flags")
    @classmethod
    def clean_flags(cls, value: list[str]) -> list[str]:
        cleaned: list[str] = []
        for flag in value:
            candidate = flag.strip().lower()[:32]
            if candidate and candidate not in cleaned:
                cleaned.append(candidate)
        return cleaned[:16]

    @model_validator(mode="after")
    def flag_count_matches(self):
        if self.lexical_flag_count < len(self.flags):
            raise ValueError("lexical_flag_count no puede ser menor que la cantidad de flags")
        return self


class SyncRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal[1] = 1
    batch_id: uuid.UUID
    device_clock: AwareDatetime
    captures: list[DerivedFeatureVector] = Field(default_factory=list, max_length=200)


class RejectedItem(BaseModel):
    event_id: uuid.UUID
    code: str


class SyncResponse(BaseModel):
    accepted: int
    duplicates: int
    rejected: list[RejectedItem]
    server_time: datetime
    clock_offset_seconds: int
    next_sync_after_seconds: int = 900


class RiskEvaluationResult(BaseModel):
    """Contrato que consume Android y el panel del tutor.

    `Sin_Riesgo` es una extensión local para nivel BAJO: la especificación solo
    define las tres categorías de alerta, pero una evaluación benigna también
    necesita una categoría estable y no alarmante.
    """

    model_config = ConfigDict(extra="forbid")

    nivel_riesgo: Literal["BAJO", "MEDIO", "ALTO"]
    categoria: Literal["Ciberbullying", "Conducta_Riesgo", "Contenido_Inapropiado", "Sin_Riesgo"]
    descripcion_narrativa: str = Field(min_length=1, max_length=280)
    consejo_educativo: str = Field(min_length=1, max_length=280)


class EvaluationResponse(BaseModel):
    event_id: uuid.UUID
    evaluation: RiskEvaluationResult
    alert_id: uuid.UUID | None = None
    severidad: str
    provider: str
    cached: bool = False
    latency_ms: int
    notified: bool = False
    server_time: datetime


class RealtimeTicketResponse(BaseModel):
    ws_url: str
    ws_ticket: str
    expires_in: int


class PushTokenRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    token: str = Field(min_length=20, max_length=4096)
    plataforma: Literal["ANDROID", "WEB"] = "ANDROID"
    device_label: str | None = Field(default=None, max_length=60)

    @field_validator("token")
    @classmethod
    def reject_credentials(cls, value: str) -> str:
        """Un JWT de sesión no es un token de entrega y nunca debe persistirse."""

        candidate = value.strip()
        if candidate.startswith("eyJ") and candidate.count(".") == 2:
            raise ValueError("El token no parece un identificador de FCM")
        return candidate


class PushTokenOut(BaseModel):
    id: uuid.UUID
    plataforma: str
    activo: bool
    device_label: str | None
    creado_en: datetime
    actualizado_en: datetime


class AlertOut(BaseModel):
    id: uuid.UUID
    vinculacion_id: uuid.UUID
    dispositivo_id: uuid.UUID
    device_alias: str
    etiqueta_menor: str | None
    categoria: str
    severidad: str
    score: int
    resumen: str
    reglas: list[dict]
    estado: str
    creado_en: datetime


class AlertUpdateRequest(BaseModel):
    estado: Literal["VISTA", "RECONOCIDA", "DESCARTADA"]


class DeviceOut(BaseModel):
    id: uuid.UUID
    alias: str
    etiqueta_menor: str | None
    plataforma: str
    model_family: str | None
    api_level: int
    estado: str
    vinculacion_id: uuid.UUID | None
    vinculacion_estado: str | None
    ultima_conexion: datetime | None
    creado_en: datetime


class GuardMark(BaseModel):
    at: datetime
    kind: Literal["alerta", "actividad"]
    severity: str | None = None
    label: str


class DayPoint(BaseModel):
    date: str
    minutes: int
    alerts: int


class OverviewOut(BaseModel):
    generated_at: datetime
    status_line: str
    status_tone: Literal["calma", "atencion", "critico"]
    devices_active: int
    minutes_today: int
    open_alerts: int
    critical_alerts: int
    guard_marks: list[GuardMark]
    week: list[DayPoint]
    alerts: list[AlertOut]
    devices: list[DeviceOut]


class ConsentOut(BaseModel):
    id: uuid.UUID
    tipo: str
    version: str
    aceptado: bool
    vinculacion_id: uuid.UUID | None
    creado_en: datetime


class ConsentRequest(BaseModel):
    tipo: Literal["terminos", "privacidad", "tutoria", "asentimiento_menor"]
    version: str | None = Field(default=None, max_length=20)
    vinculacion_id: uuid.UUID | None = None


class DeleteAccountRequest(BaseModel):
    password: str = Field(min_length=1, max_length=128)
    confirmation: str = Field(min_length=5, max_length=80)


class AccountExport(BaseModel):
    generated_at: datetime
    legal_version: str
    tutor: dict
    consents: list[dict]
    devices: list[dict]
    alerts: list[dict]
    captures: list[dict]
    audit: list[dict]
