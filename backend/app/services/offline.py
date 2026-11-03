"""Vigilancia de teléfonos que dejan de enviar señales.

El servidor no puede distinguir «sin internet», «batería agotada», «app cerrada
por el sistema» o «app desinstalada»: todas se ven como silencio. Se avisa una
vez por corte, con un texto honesto, y se cierra el episodio al reconectar.
"""

from __future__ import annotations

import uuid
from datetime import timedelta

from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import utcnow
from ..models import (
    Binding,
    Device,
    EstadoVinculacion,
    HiddenDevice,
    OfflineNotice,
    RiskAlert,
    Severidad,
)
from ..realtime import tutor_hub
from .alerts import notify_alert

OFFLINE_CATEGORY = "sin_senal"


def _summary(minutes: int) -> str:
    hours = minutes // 60
    if hours >= 1:
        elapsed = f"{hours} h" if hours < 48 else f"{hours // 24} días"
    else:
        elapsed = f"{minutes} min"
    return (
        f"El teléfono lleva {elapsed} sin enviar señales. Puede ser falta de "
        "internet, batería agotada o que la app fue desinstalada; no es posible "
        "distinguirlo desde el servidor."
    )


async def sweep_offline(db: AsyncSession, tutor_id: uuid.UUID | None = None) -> list[RiskAlert]:
    """Crea una alerta por cada teléfono activo que superó el umbral."""

    now = utcnow()
    threshold = now - timedelta(minutes=settings.offline_alert_minutes)

    query = (
        select(Binding, Device)
        .join(Device, Binding.dispositivo_id == Device.id)
        .where(
            Binding.estado == EstadoVinculacion.ACTIVO.value,
            Device.estado == "ACTIVO",
            # Un equipo recién vinculado aún no tiene ultima_conexion: se le da
            # el mismo margen del umbral antes de avisar.
            Device.creado_en < threshold,
            or_(Device.ultima_conexion.is_(None), Device.ultima_conexion < threshold),
        )
    )
    if tutor_id is not None:
        query = query.where(Binding.tutor_id == tutor_id)

    rows = (await db.execute(query)).all()
    if not rows:
        return []

    hidden = set(
        (
            await db.execute(
                select(HiddenDevice.device_id).where(
                    HiddenDevice.device_id.in_([device.id for _, device in rows])
                )
            )
        ).scalars()
    )

    created: list[tuple[RiskAlert, Binding, Device]] = []
    for binding, device in rows:
        if device.id in hidden:
            continue

        notice = await db.get(OfflineNotice, device.id)
        if notice is not None and notice.ultima_conexion_avisada == device.ultima_conexion:
            continue

        stamp = device.ultima_conexion.isoformat() if device.ultima_conexion else "nunca"
        event_id = uuid.uuid5(uuid.NAMESPACE_URL, f"offline:{device.id}:{stamp}")
        exists = await db.scalar(
            select(RiskAlert.id).where(
                RiskAlert.dispositivo_id == device.id,
                RiskAlert.event_id == event_id,
                RiskAlert.categoria == OFFLINE_CATEGORY,
            )
        )

        if exists is None:
            minutes = settings.offline_alert_minutes
            if device.ultima_conexion is not None:
                minutes = max(1, int((now - device.ultima_conexion).total_seconds() // 60))
            alert = RiskAlert(
                vinculacion_id=binding.id,
                dispositivo_id=device.id,
                event_id=event_id,
                categoria=OFFLINE_CATEGORY,
                severidad=Severidad.MEDIA.value,
                score=62,
                reglas=[
                    {
                        "code": OFFLINE_CATEGORY,
                        "label": "Teléfono sin señal",
                        "source": "system",
                        "ultima_conexion": device.ultima_conexion.isoformat() if device.ultima_conexion else None,
                        "umbral_minutos": settings.offline_alert_minutes,
                    }
                ],
                resumen=_summary(minutes),
            )
            db.add(alert)
            created.append((alert, binding, device))

        if notice is None:
            db.add(
                OfflineNotice(
                    device_id=device.id,
                    vinculacion_id=binding.id,
                    ultima_conexion_avisada=device.ultima_conexion,
                )
            )
        else:
            notice.vinculacion_id = binding.id
            notice.ultima_conexion_avisada = device.ultima_conexion
            notice.avisado_en = now

    await db.commit()

    for alert, binding, device in created:
        await notify_alert(
            db,
            tutor_id=binding.tutor_id,
            alert=alert,
            device_alias=device.alias,
            etiqueta_menor=binding.etiqueta_menor,
            allow_push=True,
        )
    return [alert for alert, _, _ in created]


async def mark_online(db: AsyncSession, ctx) -> None:
    """Cierra el episodio de «sin señal» y avisa al tutor si hubo uno."""

    notice = await db.get(OfflineNotice, ctx.device.id)
    if notice is None:
        return
    await db.delete(notice)
    await db.commit()

    tutor_hub.publish(
        ctx.binding.tutor_id,
        {
            "v": 1,
            "type": "device_reconnected",
            "ts": utcnow().isoformat(),
            "data": {
                "device_id": str(ctx.device.id),
                "device_alias": ctx.device.alias,
                "etiqueta_menor": ctx.binding.etiqueta_menor,
                "ultima_conexion": ctx.device.ultima_conexion.isoformat()
                if ctx.device.ultima_conexion
                else None,
            },
        },
    )
