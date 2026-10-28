import uuid
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import utcnow
from ..models import Binding, Device, FeatureCapture, RiskAlert, Severidad
from ..schemas import (
    AlertOut,
    DayPoint,
    DeviceOut,
    GuardMark,
    OverviewOut,
)
from .outbox import pairing_event

try:
    _LOCAL_ZONE = ZoneInfo("America/Santiago")
except ZoneInfoNotFoundError:  # pragma: no cover - Windows without tzdata
    _LOCAL_ZONE = timezone(timedelta(hours=-4))


def local_now() -> datetime:
    return utcnow().astimezone(_LOCAL_ZONE)


def _alert_out(alert: RiskAlert, device: Device, binding: Binding) -> AlertOut:
    return AlertOut(
        id=alert.id,
        vinculacion_id=alert.vinculacion_id,
        dispositivo_id=alert.dispositivo_id,
        device_alias=device.alias,
        etiqueta_menor=binding.etiqueta_menor,
        categoria=alert.categoria,
        severidad=alert.severidad,
        score=alert.score,
        resumen=alert.resumen,
        reglas=alert.reglas,
        estado=alert.estado,
        creado_en=alert.creado_en,
    )


async def list_alerts(
    db: AsyncSession,
    tutor_id: uuid.UUID,
    *,
    estado: str | None = None,
    severidad: str | None = None,
    limit: int = 50,
) -> list[AlertOut]:
    query = (
        select(RiskAlert, Device, Binding)
        .join(Device, RiskAlert.dispositivo_id == Device.id)
        .join(Binding, RiskAlert.vinculacion_id == Binding.id)
        .where(Binding.tutor_id == tutor_id)
        .order_by(RiskAlert.creado_en.desc())
        .limit(limit)
    )
    if estado:
        query = query.where(RiskAlert.estado == estado)
    if severidad:
        query = query.where(RiskAlert.severidad == severidad)
    rows = (await db.execute(query)).all()
    return [_alert_out(alert, device, binding) for alert, device, binding in rows]


async def list_devices(db: AsyncSession, tutor_id: uuid.UUID) -> list[DeviceOut]:
    rows = (
        await db.execute(
            select(Binding, Device)
            .join(Device, Binding.dispositivo_id == Device.id)
            .where(Binding.tutor_id == tutor_id)
            .order_by(Binding.creado_en.desc())
        )
    ).all()
    seen: set[uuid.UUID] = set()
    devices: list[DeviceOut] = []
    for binding, device in rows:
        if device.id in seen:
            continue
        seen.add(device.id)
        devices.append(
            DeviceOut(
                id=device.id,
                alias=device.alias,
                etiqueta_menor=binding.etiqueta_menor,
                plataforma=device.plataforma,
                model_family=device.model_family,
                api_level=device.api_level,
                estado=device.estado,
                vinculacion_id=binding.id,
                vinculacion_estado=binding.estado,
                ultima_conexion=device.ultima_conexion,
                creado_en=device.creado_en,
            )
        )
    return devices


async def update_alert_state(
    db: AsyncSession, tutor_id: uuid.UUID, alert_id: uuid.UUID, new_state: str
) -> AlertOut:
    row = (
        await db.execute(
            select(RiskAlert, Device, Binding)
            .join(Device, RiskAlert.dispositivo_id == Device.id)
            .join(Binding, RiskAlert.vinculacion_id == Binding.id)
            .where(RiskAlert.id == alert_id, Binding.tutor_id == tutor_id)
        )
    ).first()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Alerta no encontrada")
    alert, device, binding = row
    alert.estado = new_state
    alert.revisada_en = utcnow()
    await db.commit()
    return _alert_out(alert, device, binding)


async def revoke_binding(db: AsyncSession, tutor_id: uuid.UUID, binding_id: uuid.UUID) -> None:
    binding = await db.scalar(
        select(Binding).where(Binding.id == binding_id, Binding.tutor_id == tutor_id)
    )
    if binding is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Vinculación no encontrada")
    if binding.estado == "REVOCADO":
        return

    now = utcnow()
    binding.estado = "REVOCADO"
    binding.revocada_en = now
    binding.actualizado_en = now
    binding.motivo_revocacion = "Revocada por el tutor"
    if binding.dispositivo_id:
        device = await db.get(Device, binding.dispositivo_id)
        if device is not None:
            device.estado = "REVOCADO"
            device.refresh_token_hash = None
            device.refresh_expira_en = None
    db.add(
        pairing_event(
            "binding_revoked",
            session_id=binding.sesion_id,
            binding_id=binding.id,
            tutor_id=tutor_id,
            device_alias=None,
            severity="REVOCADO",
        )
    )
    await db.commit()


async def build_overview(db: AsyncSession, tutor_id: uuid.UUID) -> OverviewOut:
    now = utcnow()
    now_local = now.astimezone(_LOCAL_ZONE)
    today_start_local = datetime.combine(now_local.date(), time.min, tzinfo=_LOCAL_ZONE)
    today_start_utc = today_start_local.astimezone(timezone.utc)
    week_start_local = today_start_local - timedelta(days=6)
    week_start_utc = week_start_local.astimezone(timezone.utc)

    devices = await list_devices(db, tutor_id)
    alerts = await list_alerts(db, tutor_id, limit=8)
    open_alerts = await db.scalar(
        select(func.count(RiskAlert.id))
        .join(Binding, RiskAlert.vinculacion_id == Binding.id)
        .where(Binding.tutor_id == tutor_id, RiskAlert.estado.in_(["NUEVA", "VISTA"]))
    )
    critical_alerts = await db.scalar(
        select(func.count(RiskAlert.id))
        .join(Binding, RiskAlert.vinculacion_id == Binding.id)
        .where(
            Binding.tutor_id == tutor_id,
            RiskAlert.estado.in_(["NUEVA", "VISTA"]),
            RiskAlert.severidad == Severidad.CRITICA.value,
        )
    )
    minutes_today = await db.scalar(
        select(func.coalesce(func.sum(FeatureCapture.usage_duration_minutes), 0))
        .join(Binding, FeatureCapture.vinculacion_id == Binding.id)
        .where(Binding.tutor_id == tutor_id, FeatureCapture.time_window_start >= today_start_utc)
    )

    week_rows = (
        await db.execute(
            select(FeatureCapture.time_window_start, FeatureCapture.usage_duration_minutes)
            .join(Binding, FeatureCapture.vinculacion_id == Binding.id)
            .where(Binding.tutor_id == tutor_id, FeatureCapture.time_window_start >= week_start_utc)
        )
    ).all()
    week_alert_rows = (
        await db.execute(
            select(RiskAlert.creado_en)
            .join(Binding, RiskAlert.vinculacion_id == Binding.id)
            .where(Binding.tutor_id == tutor_id, RiskAlert.creado_en >= week_start_utc)
        )
    ).all()

    minutes_by_day: dict[str, int] = {}
    for started, duration in week_rows:
        key = started.astimezone(_LOCAL_ZONE).date().isoformat()
        minutes_by_day[key] = minutes_by_day.get(key, 0) + int(duration or 0)
    alerts_by_day: dict[str, int] = {}
    for (created,) in week_alert_rows:
        key = created.astimezone(_LOCAL_ZONE).date().isoformat()
        alerts_by_day[key] = alerts_by_day.get(key, 0) + 1

    week: list[DayPoint] = []
    for offset in range(6, -1, -1):
        key = (now_local.date() - timedelta(days=offset)).isoformat()
        week.append(DayPoint(date=key, minutes=minutes_by_day.get(key, 0), alerts=alerts_by_day.get(key, 0)))

    marks: list[GuardMark] = []
    recent_alerts = (
        await db.execute(
            select(RiskAlert)
            .join(Binding, RiskAlert.vinculacion_id == Binding.id)
            .where(Binding.tutor_id == tutor_id, RiskAlert.creado_en >= now - timedelta(hours=24))
        )
    ).scalars().all()
    for alert in recent_alerts:
        marks.append(
            GuardMark(
                at=alert.creado_en,
                kind="alerta",
                severity=alert.severidad,
                label=alert.resumen,
            )
        )
    for capture, device in (
        await db.execute(
            select(FeatureCapture, Device)
            .join(Binding, FeatureCapture.vinculacion_id == Binding.id)
            .join(Device, FeatureCapture.dispositivo_id == Device.id)
            .where(
                Binding.tutor_id == tutor_id,
                FeatureCapture.time_window_start >= now - timedelta(hours=24),
                FeatureCapture.lexical_flag_count > 0,
            )
            .limit(120)
        )
    ).all():
        marks.append(
            GuardMark(
                at=capture.time_window_start,
                kind="actividad",
                severity=None,
                label=f"{device.alias} · actividad con señales léxicas",
            )
        )
    marks.sort(key=lambda mark: mark.at)

    open_count = int(open_alerts or 0)
    critical_count = int(critical_alerts or 0)
    if critical_count > 0:
        status_line = "Una señal crítica necesita tu atención."
        tone = "critico"
    elif open_count > 0:
        status_line = f"{open_count} señal{'es' if open_count != 1 else ''} por revisar."
        tone = "atencion"
    else:
        status_line = "Hoy todo está en calma."
        tone = "calma"

    return OverviewOut(
        generated_at=now,
        status_line=status_line,
        status_tone=tone,
        devices_active=sum(1 for device in devices if device.estado == "ACTIVO"),
        minutes_today=int(minutes_today or 0),
        open_alerts=open_count,
        critical_alerts=critical_count,
        guard_marks=marks,
        week=week,
        alerts=alerts,
        devices=devices,
    )
