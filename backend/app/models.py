import uuid
from datetime import date, datetime
from enum import Enum
from typing import Any

from sqlalchemy import (
    CheckConstraint,
    ForeignKey,
    Index,
    Integer,
    JSON,
    String,
    UniqueConstraint,
    Uuid,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base, UtcDateTime, utcnow, uuid_pk


class Rol(str, Enum):
    TUTOR = "TUTOR"
    MENOR = "MENOR"


class EstadoVinculacion(str, Enum):
    PENDIENTE = "PENDIENTE"
    ACTIVO = "ACTIVO"
    REVOCADO = "REVOCADO"
    EXPIRADO = "EXPIRADO"


class EstadoSesion(str, Enum):
    PENDIENTE = "PENDIENTE"
    VERIFICADA = "VERIFICADA"
    EXPIRADA = "EXPIRADA"
    CANCELADA = "CANCELADA"


class Severidad(str, Enum):
    BAJA = "BAJA"
    MEDIA = "MEDIA"
    ALTA = "ALTA"
    CRITICA = "CRITICA"


class EstadoAlerta(str, Enum):
    NUEVA = "NUEVA"
    VISTA = "VISTA"
    RECONOCIDA = "RECONOCIDA"
    DESCARTADA = "DESCARTADA"


class User(Base):
    __tablename__ = "usuarios"
    __table_args__ = (
        CheckConstraint("rol IN ('TUTOR','MENOR')", name="rol_valido"),
        CheckConstraint("rol <> 'TUTOR' OR (email IS NOT NULL AND password_hash IS NOT NULL)", name="tutor_credenciales"),
        CheckConstraint("rol <> 'MENOR' OR tutor_legal_id IS NOT NULL", name="menor_tutor"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid_pk)
    rol: Mapped[str] = mapped_column(String(16), nullable=False, default=Rol.TUTOR.value)
    email: Mapped[str | None] = mapped_column(String(255), unique=True, nullable=True)
    password_hash: Mapped[str | None] = mapped_column(String(255), nullable=True)
    alias: Mapped[str] = mapped_column(String(60), nullable=False)
    fecha_nacimiento: Mapped[date | None] = mapped_column(nullable=True)
    tutor_legal_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("usuarios.id"), nullable=True)
    consentimiento_version: Mapped[str | None] = mapped_column(String(20), nullable=True)
    consentimiento_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    creado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)
    eliminado_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)


class Device(Base):
    __tablename__ = "dispositivos"
    __table_args__ = (
        CheckConstraint("plataforma IN ('ANDROID')", name="plataforma_valida"),
        CheckConstraint("estado IN ('ACTIVO','REVOCADO')", name="estado_valido"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid_pk)
    usuario_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("usuarios.id"), nullable=False)
    alias: Mapped[str] = mapped_column(String(60), nullable=False)
    plataforma: Mapped[str] = mapped_column(String(16), default="ANDROID", nullable=False)
    api_level: Mapped[int] = mapped_column(Integer, nullable=False, default=26)
    model_family: Mapped[str | None] = mapped_column(String(60), nullable=True)
    fingerprint_hmac: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    estado: Mapped[str] = mapped_column(String(16), default="ACTIVO", nullable=False)
    refresh_token_hash: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    refresh_expira_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    ultima_conexion: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    creado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)


class PairingSession(Base):
    __tablename__ = "sesiones_vinculacion"
    __table_args__ = (
        CheckConstraint("estado IN ('PENDIENTE','VERIFICADA','EXPIRADA','CANCELADA')", name="estado_valido"),
        CheckConstraint("intentos >= 0 AND intentos <= max_intentos", name="intentos_validos"),
        Index(
            "ux_sesion_codigo_pendiente",
            "codigo_hash",
            unique=True,
            sqlite_where=text("estado = 'PENDIENTE'"),
            postgresql_where=text("estado = 'PENDIENTE'"),
        ),
        Index("ix_sesion_expira_pendiente", "expira_en", sqlite_where=text("estado = 'PENDIENTE'")),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid_pk)
    tutor_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("usuarios.id"), nullable=False)
    codigo_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    expira_en: Mapped[datetime] = mapped_column(UtcDateTime, nullable=False)
    intentos: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    max_intentos: Mapped[int] = mapped_column(Integer, default=5, nullable=False)
    estado: Mapped[str] = mapped_column(String(16), default=EstadoSesion.PENDIENTE.value, nullable=False)
    verificado_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    dispositivo_menor_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("dispositivos.id"), nullable=True)
    creado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)


class Binding(Base):
    __tablename__ = "vinculaciones"
    __table_args__ = (
        CheckConstraint("estado IN ('PENDIENTE','ACTIVO','REVOCADO','EXPIRADO')", name="estado_valido"),
        CheckConstraint("estado <> 'ACTIVO' OR activada_en IS NOT NULL", name="activa_con_fecha"),
        CheckConstraint("estado <> 'REVOCADO' OR revocada_en IS NOT NULL", name="revocada_con_fecha"),
        Index(
            "ux_vinculacion_activa_dispositivo",
            "dispositivo_id",
            unique=True,
            sqlite_where=text("estado = 'ACTIVO'"),
            postgresql_where=text("estado = 'ACTIVO'"),
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid_pk)
    tutor_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("usuarios.id"), nullable=False)
    menor_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("usuarios.id"), nullable=True)
    dispositivo_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("dispositivos.id"), nullable=True)
    sesion_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("sesiones_vinculacion.id"), unique=True, nullable=False)
    etiqueta_menor: Mapped[str | None] = mapped_column(String(40), nullable=True)
    estado: Mapped[str] = mapped_column(String(16), default=EstadoVinculacion.PENDIENTE.value, nullable=False)
    politica: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict, nullable=False)
    activada_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    revocada_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    motivo_revocacion: Mapped[str | None] = mapped_column(String(120), nullable=True)
    creado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)
    actualizado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, onupdate=utcnow, nullable=False)


class RiskAlert(Base):
    __tablename__ = "alertas_riesgo"
    __table_args__ = (
        CheckConstraint("severidad IN ('BAJA','MEDIA','ALTA','CRITICA')", name="severidad_valida"),
        CheckConstraint("estado IN ('NUEVA','VISTA','RECONOCIDA','DESCARTADA')", name="estado_valido"),
        CheckConstraint("score BETWEEN 0 AND 100", name="score_valido"),
        UniqueConstraint("dispositivo_id", "event_id", "categoria", name="alerta_evento_unica"),
        Index("ix_alertas_panel", "vinculacion_id", "estado", "creado_en"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid_pk)
    vinculacion_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("vinculaciones.id"), nullable=False)
    dispositivo_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("dispositivos.id"), nullable=False)
    event_id: Mapped[uuid.UUID] = mapped_column(Uuid, nullable=False)
    categoria: Mapped[str] = mapped_column(String(40), nullable=False)
    severidad: Mapped[str] = mapped_column(String(16), nullable=False)
    score: Mapped[int] = mapped_column(Integer, nullable=False)
    reglas: Mapped[list[dict[str, Any]]] = mapped_column(JSON, default=list, nullable=False)
    resumen: Mapped[str] = mapped_column(String(280), nullable=False)
    estado: Mapped[str] = mapped_column(String(16), default=EstadoAlerta.NUEVA.value, nullable=False)
    revisada_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    creado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)


class FeatureCapture(Base):
    __tablename__ = "capturas_features"
    __table_args__ = (
        CheckConstraint("lexical_flag_count BETWEEN 0 AND 64", name="flags_validos"),
        CheckConstraint("usage_duration_minutes BETWEEN 0 AND 1440", name="duracion_valida"),
        Index("ix_capturas_ventana", "vinculacion_id", "time_window_start"),
    )

    dispositivo_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("dispositivos.id"), primary_key=True)
    event_id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    vinculacion_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("vinculaciones.id"), nullable=False)
    schema_version: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    time_window_start: Mapped[datetime] = mapped_column(UtcDateTime, nullable=False)
    time_window_end: Mapped[datetime] = mapped_column(UtcDateTime, nullable=False)
    tz_offset_minutes: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    categoria: Mapped[str] = mapped_column(String(40), nullable=False)
    lexical_flag_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    flags: Mapped[list[str]] = mapped_column(JSON, default=list, nullable=False)
    hashed_contact_id: Mapped[str | None] = mapped_column(String(44), nullable=True)
    usage_duration_minutes: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    payload_sha256: Mapped[str | None] = mapped_column(String(64), nullable=True)
    recibido_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)


class OutboxEvent(Base):
    __tablename__ = "outbox_eventos"
    __table_args__ = (Index("ix_outbox_pendiente", "creado_en", sqlite_where=text("publicado_en IS NULL")),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    agregado: Mapped[str] = mapped_column(String(40), nullable=False)
    agregado_id: Mapped[uuid.UUID] = mapped_column(Uuid, nullable=False)
    tipo: Mapped[str] = mapped_column(String(40), nullable=False)
    payload: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict, nullable=False)
    publicado_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    creado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)


class AccountSecurity(Base):
    """Security state kept separate so existing databases gain it via create_all."""

    __tablename__ = "seguridad_cuenta"

    usuario_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("usuarios.id"), primary_key=True)
    token_version: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    email_verificado_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    password_cambiada_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    intentos_fallidos: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    bloqueado_hasta: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    actualizado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, onupdate=utcnow, nullable=False)


class TutorSession(Base):
    __tablename__ = "sesiones_tutor"
    __table_args__ = (
        CheckConstraint("revocada_en IS NULL OR revocada_en >= creado_en", name="revocacion_coherente"),
        Index("ix_sesion_tutor_refresh", "refresh_token_hash"),
        Index("ix_sesion_tutor_usuario", "usuario_id", "expira_en"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid_pk)
    usuario_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("usuarios.id"), nullable=False)
    refresh_token_hash: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    user_agent_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    ip_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    creado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)
    expira_en: Mapped[datetime] = mapped_column(UtcDateTime, nullable=False)
    ultimo_uso_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    revocada_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)


class AuthToken(Base):
    __tablename__ = "tokens_auth"
    __table_args__ = (
        UniqueConstraint("token_hash", name="token_hash_unico"),
        Index("ix_token_auth_usuario", "usuario_id", "proposito"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid_pk)
    usuario_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("usuarios.id"), nullable=False)
    proposito: Mapped[str] = mapped_column(String(32), nullable=False)
    token_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    expira_en: Mapped[datetime] = mapped_column(UtcDateTime, nullable=False)
    usado_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    creado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)


class ConsentRecord(Base):
    __tablename__ = "consentimientos"
    __table_args__ = (
        CheckConstraint(
            "tipo IN ('terminos','privacidad','tutoria','asentimiento_menor')",
            name="tipo_consentimiento_valido",
        ),
        Index("ix_consentimiento_tutor", "tutor_id", "creado_en"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid_pk)
    tutor_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("usuarios.id"), nullable=False)
    tipo: Mapped[str] = mapped_column(String(32), nullable=False)
    version: Mapped[str] = mapped_column(String(20), nullable=False)
    aceptado: Mapped[bool] = mapped_column(default=True, nullable=False)
    vinculacion_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("vinculaciones.id"), nullable=True)
    ip_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    detalle: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict, nullable=False)
    creado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)


class AuditEvent(Base):
    __tablename__ = "auditoria"
    __table_args__ = (
        Index("ix_auditoria_usuario", "usuario_id", "creado_en"),
        Index("ix_auditoria_accion", "accion", "creado_en"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid_pk)
    usuario_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("usuarios.id"), nullable=True)
    accion: Mapped[str] = mapped_column(String(64), nullable=False)
    entidad: Mapped[str | None] = mapped_column(String(40), nullable=True)
    entidad_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    ip_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    user_agent_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    detalle: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict, nullable=False)
    creado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)


class PushToken(Base):
    """Token FCM del dispositivo del tutor (nunca del menor).

    El token es una credencial de entrega, no un dato del menor. Se guarda para
    poder enviar la notificación y se desactiva al cerrar sesión o al fallar el
    envío de forma definitiva.
    """

    __tablename__ = "push_tokens"
    __table_args__ = (
        CheckConstraint("plataforma IN ('ANDROID','WEB')", name="plataforma_valida"),
        Index("ix_push_tokens_usuario", "usuario_id", "activo"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid_pk)
    usuario_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("usuarios.id"), nullable=False)
    token: Mapped[str] = mapped_column(String(4096), unique=True, nullable=False)
    plataforma: Mapped[str] = mapped_column(String(16), default="ANDROID", nullable=False)
    device_label: Mapped[str | None] = mapped_column(String(60), nullable=True)
    activo: Mapped[bool] = mapped_column(default=True, nullable=False)
    creado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)
    actualizado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, onupdate=utcnow, nullable=False)

class UnlinkCode(Base):
    """Código que el tutor define para autorizar la desvinculación.

    Se guarda solo el HMAC. Nunca se devuelve en claro. El bloqueo por intentos
    evita fuerza bruta sobre 6 dígitos.
    """

    __tablename__ = "codigos_desvinculacion"
    __table_args__ = (CheckConstraint("intentos >= 0", name="intentos_no_negativos"),)

    binding_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("vinculaciones.id"), primary_key=True)
    codigo_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    intentos: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    max_intentos: Mapped[int] = mapped_column(Integer, default=5, nullable=False)
    bloqueado_hasta: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    creado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)
    actualizado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, onupdate=utcnow, nullable=False)


class HiddenDevice(Base):
    """Dispositivo que el tutor quitó del panel sin borrar su historial."""

    __tablename__ = "dispositivos_ocultos"

    device_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("dispositivos.id"), primary_key=True)
    tutor_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("usuarios.id"), nullable=False, index=True)
    oculto_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)


class OfflineNotice(Base):
    """Marca el último aviso de «sin señal» para no repetirlo en el mismo corte."""

    __tablename__ = "avisos_sin_senal"

    device_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("dispositivos.id"), primary_key=True)
    vinculacion_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("vinculaciones.id"), nullable=False)
    ultima_conexion_avisada: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    avisado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)

class DeviceStatus(Base):
    """Último estado reportado por el teléfono (permisos y No molestar)."""

    __tablename__ = "estado_dispositivo"

    device_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("dispositivos.id"), primary_key=True)
    vinculacion_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("vinculaciones.id"), nullable=False)
    notificaciones_activas: Mapped[bool] = mapped_column(default=True, nullable=False)
    uso_activo: Mapped[bool] = mapped_column(default=True, nullable=False)
    dnd: Mapped[bool] = mapped_column(default=False, nullable=False)
    accesibilidad: Mapped[bool] = mapped_column(default=False, nullable=False)
    reportado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)


class AccessNotice(Base):
    """Evita repetir la alerta de acceso a notificaciones en el mismo corte."""

    __tablename__ = "avisos_acceso"

    device_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("dispositivos.id"), primary_key=True)
    vinculacion_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("vinculaciones.id"), nullable=False)
    avisado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)

class ListenerHeartbeat(Base):
    """Última notificación que el listener realmente recibió.

    Sirve para distinguir «no llegan notificaciones» de «el listener está
    ciego» (por ejemplo, si el fabricante lo bloquea en No molestar).
    """

    __tablename__ = "latidos_listener"

    device_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("dispositivos.id"), primary_key=True)
    ultima_notificacion_en: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    actualizado_en: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, nullable=False)
