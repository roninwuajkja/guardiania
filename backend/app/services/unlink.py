"""Código de desvinculación del tutor.

El tutor define un PIN de 6 dígitos por vinculación. El teléfono lo pide al
desvincularse, así que un menor no puede cortar el monitoreo desde la app con un
par de toques. El código se guarda como HMAC y nunca se devuelve.

Límite honesto: esto no impide desinstalar la app, borrar sus datos desde
Ajustes ni restablecer el teléfono. El panel siempre puede revocar sin el
código.
"""

from __future__ import annotations

import uuid
from datetime import timedelta

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import utcnow
from ..models import Binding, EstadoVinculacion, OfflineNotice, UnlinkCode
from ..security import hash_unlink_code, verify_unlink_code
from .audit import record
from .outbox import pairing_event

LOCK_MINUTES = 15


def _error(code: str, message: str, *, status_code: int = status.HTTP_403_FORBIDDEN, **extra) -> HTTPException:
    detail = {"code": code, "message": message}
    detail.update(extra)
    return HTTPException(status_code=status_code, detail=detail)


async def _binding_for_tutor(db: AsyncSession, tutor_id: uuid.UUID, binding_id: uuid.UUID) -> Binding:
    binding = await db.scalar(
        select(Binding).where(Binding.id == binding_id, Binding.tutor_id == tutor_id)
    )
    if binding is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Vinculación no encontrada")
    return binding


async def set_unlink_code(
    db: AsyncSession,
    tutor_id: uuid.UUID,
    binding_id: uuid.UUID,
    code: str,
) -> None:
    binding = await _binding_for_tutor(db, tutor_id, binding_id)
    if binding.estado != EstadoVinculacion.ACTIVO.value:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Solo se puede proteger una vinculación activa",
        )

    row = await db.get(UnlinkCode, binding_id)
    if row is None:
        row = UnlinkCode(binding_id=binding_id, codigo_hash=hash_unlink_code(binding_id, code))
        db.add(row)
    else:
        row.codigo_hash = hash_unlink_code(binding_id, code)
        row.intentos = 0
        row.bloqueado_hasta = None

    await record(
        db,
        "unlink.code_set",
        usuario_id=tutor_id,
        entidad="vinculacion",
        entidad_id=binding_id,
        detalle={"binding_id": str(binding_id)},
    )
    await db.commit()


async def clear_unlink_code(db: AsyncSession, tutor_id: uuid.UUID, binding_id: uuid.UUID) -> None:
    await _binding_for_tutor(db, tutor_id, binding_id)
    row = await db.get(UnlinkCode, binding_id)
    if row is not None:
        await db.delete(row)
        await record(
            db,
            "unlink.code_cleared",
            usuario_id=tutor_id,
            entidad="vinculacion",
            entidad_id=binding_id,
            detalle={"binding_id": str(binding_id)},
        )
        await db.commit()


async def unlink_from_device(db: AsyncSession, ctx, code: str | None) -> None:
    """Desvincula el teléfono si el código es correcto (o no hay código)."""

    binding = ctx.binding
    device = ctx.device
    if binding.estado != EstadoVinculacion.ACTIVO.value:
        # Ya está revocada: la app puede limpiar lo local sin error.
        return

    row = await db.get(UnlinkCode, binding.id)
    now = utcnow()

    if row is not None:
        if row.bloqueado_hasta is not None and row.bloqueado_hasta > now:
            seconds = max(1, int((row.bloqueado_hasta - now).total_seconds()))
            raise _error(
                "pin_locked",
                f"Demasiados intentos. Espera {max(1, seconds // 60)} minuto(s).",
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                retry_after_seconds=seconds,
            )
        if not code:
            raise _error(
                "pin_required",
                "Ingresa el código que definió tu tutor para desvincular.",
                attempts_left=max(0, row.max_intentos - row.intentos),
            )
        if not verify_unlink_code(binding.id, code, row.codigo_hash):
            row.intentos += 1
            locked = row.intentos >= row.max_intentos
            if locked:
                row.bloqueado_hasta = now + timedelta(minutes=LOCK_MINUTES)
                row.intentos = 0
            attempts_left = max(0, row.max_intentos - row.intentos)
            await record(
                db,
                "unlink.failed",
                usuario_id=binding.tutor_id,
                entidad="vinculacion",
                entidad_id=binding.id,
                detalle={"intentos": row.intentos, "bloqueado": locked},
            )
            await db.commit()
            raise _error(
                "pin_invalid",
                "Código incorrecto. Te quedan "
                + (f"{attempts_left} intento(s)." if not locked else "0 intentos; espera 15 minutos."),
                status_code=status.HTTP_403_FORBIDDEN,
                attempts_left=attempts_left,
                locked=locked,
            )

    binding.estado = EstadoVinculacion.REVOCADO.value
    binding.revocada_en = now
    binding.actualizado_en = now
    binding.motivo_revocacion = "Desvinculación desde el teléfono"
    device.estado = "REVOCADO"
    device.refresh_token_hash = None
    device.refresh_expira_en = None

    notice = await db.get(OfflineNotice, device.id)
    if notice is not None:
        await db.delete(notice)
    if row is not None:
        await db.delete(row)

    db.add(
        pairing_event(
            "binding_revoked",
            session_id=binding.sesion_id,
            binding_id=binding.id,
            tutor_id=binding.tutor_id,
            device_alias=None,
            severity="REVOCADO",
        )
    )
    await record(
        db,
        "unlink.self",
        usuario_id=binding.tutor_id,
        entidad="vinculacion",
        entidad_id=binding.id,
        detalle={"device_id": str(device.id), "con_codigo": row is not None},
    )
    await db.commit()
