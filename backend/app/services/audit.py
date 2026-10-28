import logging
import uuid
from typing import Any

from fastapi import Request
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import utcnow
from ..models import AuditEvent, ConsentRecord
from ..net import client_ip, user_agent
from ..security import hash_ip, hash_user_agent

logger = logging.getLogger("guardiania.audit")


async def record(
    db: AsyncSession,
    accion: str,
    *,
    usuario_id: uuid.UUID | None = None,
    request: Request | None = None,
    entidad: str | None = None,
    entidad_id: str | uuid.UUID | None = None,
    detalle: dict[str, Any] | None = None,
    commit: bool = False,
) -> AuditEvent:
    """Append an immutable security/business event without raw personal data."""
    ip = client_ip(request)
    agent = user_agent(request)
    event = AuditEvent(
        usuario_id=usuario_id,
        accion=accion[:64],
        entidad=entidad[:40] if entidad else None,
        entidad_id=str(entidad_id)[:64] if entidad_id else None,
        ip_hash=hash_ip(ip) if ip != "unknown" else None,
        user_agent_hash=hash_user_agent(agent) if agent else None,
        detalle=detalle or {},
    )
    db.add(event)
    if commit:
        await db.commit()
    return event


async def record_consent(
    db: AsyncSession,
    *,
    tutor_id: uuid.UUID,
    tipo: str,
    version: str,
    request: Request | None = None,
    vinculacion_id: uuid.UUID | None = None,
    detalle: dict[str, Any] | None = None,
    commit: bool = False,
) -> ConsentRecord:
    ip = client_ip(request)
    consent = ConsentRecord(
        tutor_id=tutor_id,
        tipo=tipo,
        version=version,
        aceptado=True,
        vinculacion_id=vinculacion_id,
        ip_hash=hash_ip(ip) if ip != "unknown" else None,
        detalle=detalle or {},
        creado_en=utcnow(),
    )
    db.add(consent)
    if commit:
        await db.commit()
    return consent
