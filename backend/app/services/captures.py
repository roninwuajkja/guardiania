from datetime import timedelta

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import utcnow
from ..models import FeatureCapture
from ..schemas import DerivedFeatureVector, RejectedItem, SyncRequest, SyncResponse
from .alerts import dispatch_rule_alerts
from .offline import mark_online, sweep_offline
from .risk import evaluate_capture


def _capture_row(ctx, vector: DerivedFeatureVector, now) -> FeatureCapture:
    return FeatureCapture(
        dispositivo_id=ctx.device.id,
        event_id=vector.event_id,
        vinculacion_id=ctx.binding.id,
        schema_version=vector.schema_version,
        time_window_start=vector.time_window.start,
        time_window_end=vector.time_window.end,
        tz_offset_minutes=vector.time_window.tz_offset_minutes,
        categoria=vector.category,
        lexical_flag_count=vector.lexical_flag_count,
        flags=vector.flags,
        hashed_contact_id=vector.hashed_contact_id,
        usage_duration_minutes=vector.usage_duration_minutes,
        recibido_en=now,
    )


async def persist_capture(db: AsyncSession, ctx, vector: DerivedFeatureVector, now) -> bool:
    """Insert the derived vector; return False when it was already stored."""

    try:
        async with db.begin_nested():
            db.add(_capture_row(ctx, vector, now))
            await db.flush()
    except IntegrityError:
        return False
    return True


async def sync_captures(db: AsyncSession, ctx, payload: SyncRequest) -> SyncResponse:
    if len(payload.captures) > settings.sync_max_batch:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"El lote supera el máximo de {settings.sync_max_batch} capturas",
        )

    now = utcnow()
    valid: list = []
    rejected: list[RejectedItem] = []
    duplicates = 0

    event_ids = [capture.event_id for capture in payload.captures]
    existing: set = set()
    if event_ids:
        existing = set(
            (
                await db.execute(
                    select(FeatureCapture.event_id).where(
                        FeatureCapture.dispositivo_id == ctx.device.id,
                        FeatureCapture.event_id.in_(event_ids),
                    )
                )
            ).scalars()
        )

    for vector in payload.captures:
        if vector.device_id != ctx.device.id:
            rejected.append(RejectedItem(event_id=vector.event_id, code="DEVICE_MISMATCH"))
            continue
        if vector.event_id in existing:
            duplicates += 1
            continue
        if not vector.integrity.attestation_ok:
            rejected.append(RejectedItem(event_id=vector.event_id, code="ATTESTATION_REQUIRED"))
            continue
        if vector.time_window.start < now - timedelta(days=7):
            rejected.append(RejectedItem(event_id=vector.event_id, code="WINDOW_TOO_OLD"))
            continue
        if vector.time_window.end > now + timedelta(minutes=10):
            rejected.append(RejectedItem(event_id=vector.event_id, code="WINDOW_IN_FUTURE"))
            continue

        if not await persist_capture(db, ctx, vector, now):
            duplicates += 1
            continue
        valid.append(vector)

    created_alerts = []
    for vector in valid:
        created_alerts.extend(await evaluate_capture(db, ctx, vector))

    ctx.device.ultima_conexion = now
    await db.commit()

    # Si el teléfono venía de un corte, se cierra el episodio y se avisa.
    await mark_online(db, ctx)
    await sweep_offline(db, tutor_id=ctx.binding.tutor_id)

    await dispatch_rule_alerts(
        db,
        tutor_id=ctx.binding.tutor_id,
        binding=ctx.binding,
        device=ctx.device,
        alerts=created_alerts,
    )

    clock_offset = int((now - payload.device_clock).total_seconds())
    return SyncResponse(
        accepted=len(valid),
        duplicates=duplicates,
        rejected=rejected,
        server_time=now,
        clock_offset_seconds=clock_offset,
    )
