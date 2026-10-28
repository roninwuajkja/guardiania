import io
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta

import segno
from fastapi import HTTPException, status
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import utcnow
from ..models import (
    Binding,
    Device,
    EstadoSesion,
    EstadoVinculacion,
    PairingSession,
    User,
)
from ..schemas import DeviceInfo, PairingVerifyResponse, PolicySummary
from ..security import (
    create_device_token,
    create_pairing_token,
    create_ws_ticket,
    decode_token,
    generate_pairing_code,
    hash_install_id,
    hash_pairing_code,
    new_refresh_token,
)
from .outbox import pairing_event


@dataclass
class GeneratedPairing:
    session_id: uuid.UUID
    expires_at: datetime
    qr_payload: str
    qr_svg: str
    manual_code: str
    ws_ticket: str


def _render_qr(payload: str) -> str:
    qr = segno.make(payload, error="m")
    buffer = io.BytesIO()
    qr.save(
        buffer,
        kind="svg",
        scale=6,
        border=1,
        dark="#0B211D",
        light="#FFFFFF",
        xmldecl=False,
        svgclass=None,
        lineclass=None,
    )
    return buffer.getvalue().decode("utf-8")


async def generate_pairing(
    db: AsyncSession,
    tutor: User,
    etiqueta_menor: str | None,
    ttl_seconds: int,
) -> GeneratedPairing:
    now = utcnow()
    expires_at = now + timedelta(seconds=ttl_seconds)
    session_id = uuid.uuid4()

    code = ""
    for _ in range(5):
        candidate = generate_pairing_code()
        existing = await db.scalar(
            select(PairingSession.id).where(
                PairingSession.codigo_hash == hash_pairing_code(candidate),
                PairingSession.estado == EstadoSesion.PENDIENTE.value,
            )
        )
        if existing is None:
            code = candidate
            break
    if not code:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="No se pudo generar un código único. Intenta nuevamente.",
        )

    session = PairingSession(
        id=session_id,
        tutor_id=tutor.id,
        codigo_hash=hash_pairing_code(code),
        expira_en=expires_at,
        max_intentos=settings.pairing_max_attempts,
    )
    db.add(session)
    await db.flush()
    db.add(
        Binding(
            tutor_id=tutor.id,
            sesion_id=session_id,
            etiqueta_menor=(etiqueta_menor or "").strip() or None,
            politica={"retencion_dias": 90, "captura_texto": False},
        )
    )
    await db.commit()

    qr_token = create_pairing_token(tutor.id, session_id, ttl_seconds)
    return GeneratedPairing(
        session_id=session_id,
        expires_at=expires_at,
        qr_payload=f"guardiania://pair?v=1&t={qr_token}",
        qr_svg=_render_qr(f"guardiania://pair?v=1&t={qr_token}"),
        manual_code=code,
        ws_ticket=create_ws_ticket(session_id, tutor.id),
    )


async def _resolve_session(db: AsyncSession, body) -> PairingSession:
    if body.qr_token:
        claims = decode_token(body.qr_token, settings.pairing_audience)
        if claims.get("scope") != "pairing:verify" or claims.get("typ") != "pairing":
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token de vinculación inválido")
        try:
            session_id = uuid.UUID(claims["jti"])
            tutor_id = uuid.UUID(claims["sub"])
        except (KeyError, ValueError) as exc:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token de vinculación inválido") from exc
        session = await db.scalar(
            select(PairingSession).where(PairingSession.id == session_id, PairingSession.tutor_id == tutor_id)
        )
    else:
        session = await db.scalar(
            select(PairingSession)
            .where(
                PairingSession.codigo_hash == hash_pairing_code(body.manual_code),
                PairingSession.estado == EstadoSesion.PENDIENTE.value,
            )
            .order_by(PairingSession.creado_en.desc())
        )
    if session is None:
        # Deliberately generic: never reveal whether a code exists.
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Código inválido")
    return session


async def verify_pairing(db: AsyncSession, body) -> PairingVerifyResponse:
    if not getattr(body, "child_assent", False):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="La persona menor debe comprender y aceptar la vinculación en este dispositivo.",
        )
    session = await _resolve_session(db, body)
    session_id = session.id
    now = utcnow()

    if session.estado != EstadoSesion.PENDIENTE.value:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Esta vinculación ya fue utilizada")
    if session.expira_en <= now:
        await expire_session(db, session)
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="El código expiró. Genera uno nuevo.")

    attempts = await db.scalar(
        update(PairingSession)
        .where(
            PairingSession.id == session.id,
            PairingSession.estado == EstadoSesion.PENDIENTE.value,
            PairingSession.intentos < PairingSession.max_intentos,
        )
        .values(intentos=PairingSession.intentos + 1)
        .returning(PairingSession.intentos)
    )
    await db.commit()
    if attempts is None:
        raise HTTPException(status_code=status.HTTP_423_LOCKED, detail="Demasiados intentos. Genera un código nuevo.")

    binding = await db.scalar(select(Binding).where(Binding.sesion_id == session.id))
    if binding is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Sesión de vinculación no encontrada")

    fingerprint = hash_install_id(str(body.device.install_id))
    already_bound = await db.scalar(
        select(Binding.id)
        .join(Device, Binding.dispositivo_id == Device.id)
        .where(Device.fingerprint_hmac == fingerprint, Binding.estado == EstadoVinculacion.ACTIVO.value)
    )
    if already_bound is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Este dispositivo ya está vinculado. Revoca la vinculación anterior primero.",
        )

    tutor = await db.get(User, binding.tutor_id)
    if tutor is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tutor no encontrado")

    device_alias = binding.etiqueta_menor or _default_device_alias(body.device)
    binding_id = binding.id
    tutor_id = tutor.id
    tutor_alias = tutor.alias
    refresh_token, refresh_hash = new_refresh_token()
    device = Device(
        usuario_id=tutor.id,
        alias=device_alias,
        api_level=body.device.api_level,
        model_family=body.device.model_family,
        fingerprint_hmac=fingerprint,
        refresh_token_hash=refresh_hash,
        refresh_expira_en=now + timedelta(days=settings.refresh_token_days),
        ultima_conexion=now,
    )

    try:
        db.add(device)
        await db.flush()
        activated = (
            await db.execute(
                update(Binding)
                .where(Binding.sesion_id == session_id, Binding.estado == EstadoVinculacion.PENDIENTE.value)
                .values(
                    estado=EstadoVinculacion.ACTIVO.value,
                    dispositivo_id=device.id,
                    activada_en=now,
                    actualizado_en=now,
                )
                .returning(Binding.id)
            )
        ).scalar_one_or_none()
        if activated is None:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Esta vinculación ya fue utilizada")
        await db.execute(
            update(PairingSession)
            .where(PairingSession.id == session_id)
            .values(
                estado=EstadoSesion.VERIFICADA.value,
                verificado_en=now,
                dispositivo_menor_id=device.id,
            )
        )
        db.add(
            pairing_event(
                "pairing_success",
                session_id=session_id,
                binding_id=binding_id,
                tutor_id=tutor_id,
                device_alias=device_alias,
            )
        )
        await db.commit()
    except HTTPException:
        await db.rollback()
        raise
    except Exception as exc:  # unique partial index race
        await db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="No se pudo completar la vinculación. Intenta nuevamente.",
        ) from exc

    access_token = create_device_token(device.id, binding_id)
    return PairingVerifyResponse(
        status="ACTIVO",
        vinculacion_id=binding_id,
        device_id=device.id,
        access_token=access_token,
        refresh_token=refresh_token,
        expires_in=settings.device_token_minutes * 60,
        tutor_alias=tutor_alias,
        device_alias=device_alias,
        policy=PolicySummary(retencion_dias=90, categorias=["estudio", "social", "juego", "video", "mensajeria"]),
    )


def _default_device_alias(device: DeviceInfo) -> str:
    return f"Android {device.model_family}"[:60]


async def cancel_pairing(db: AsyncSession, tutor: User, session_id: uuid.UUID) -> None:
    session = await db.scalar(
        select(PairingSession).where(PairingSession.id == session_id, PairingSession.tutor_id == tutor.id)
    )
    if session is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Sesión no encontrada")
    if session.estado != EstadoSesion.PENDIENTE.value:
        return
    binding = await db.scalar(select(Binding).where(Binding.sesion_id == session.id))
    now = utcnow()
    session.estado = EstadoSesion.CANCELADA.value
    if binding is not None:
        binding.estado = EstadoVinculacion.REVOCADO.value
        binding.revocada_en = now
        binding.motivo_revocacion = "Cancelada por el tutor"
    await db.commit()


async def expire_session(db: AsyncSession, session: PairingSession) -> None:
    now = utcnow()
    session.estado = EstadoSesion.EXPIRADA.value
    binding = await db.scalar(select(Binding).where(Binding.sesion_id == session.id))
    if binding is not None and binding.estado == EstadoVinculacion.PENDIENTE.value:
        binding.estado = EstadoVinculacion.EXPIRADO.value
        binding.actualizado_en = now
        db.add(
            pairing_event(
                "pairing_expired",
                session_id=session.id,
                binding_id=binding.id,
                tutor_id=session.tutor_id,
            )
        )
    await db.commit()


async def expire_stale_pairings(db: AsyncSession) -> int:
    now = utcnow()
    sessions = (
        await db.execute(
            select(PairingSession).where(
                PairingSession.estado == EstadoSesion.PENDIENTE.value,
                PairingSession.expira_en <= now,
            )
        )
    ).scalars().all()
    for session in sessions:
        await expire_session(db, session)
    return len(sessions)
