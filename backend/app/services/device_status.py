"""Estado de permisos reportado por el teléfono.

El sistema no permite impedir que el menor quite el acceso a notificaciones o
active No molestar. Lo que sí se puede es detectarlo y avisar al tutor: una
guardia sin notificaciones es una guardia incompleta.
"""

from __future__ import annotations

import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from ..db import utcnow
from ..models import AccessNotice, DeviceStatus, ListenerHeartbeat, RiskAlert, Severidad
from ..realtime import tutor_hub
from .alerts import notify_alert

ACCESS_CATEGORY = "acceso_desactivado"


async def report_status(db: AsyncSession, ctx, body) -> None:
    now = utcnow()
    row = await db.get(DeviceStatus, ctx.device.id)
    if row is None:
        row = DeviceStatus(device_id=ctx.device.id, vinculacion_id=ctx.binding.id)
        db.add(row)
    row.vinculacion_id = ctx.binding.id
    row.notificaciones_activas = body.notificaciones_activas
    row.uso_activo = body.uso_activo
    row.dnd = body.dnd
    row.accesibilidad = body.accesibilidad
    row.reportado_en = now

    heartbeat = await db.get(ListenerHeartbeat, ctx.device.id)
    if heartbeat is None:
        heartbeat = ListenerHeartbeat(
            device_id=ctx.device.id,
            ultima_notificacion_en=body.ultima_notificacion_en,
        )
        db.add(heartbeat)
    elif body.ultima_notificacion_en is not None:
        heartbeat.ultima_notificacion_en = body.ultima_notificacion_en
    heartbeat.actualizado_en = now

    notice = await db.get(AccessNotice, ctx.device.id)
    alert: RiskAlert | None = None
    restored = False

    if not body.notificaciones_activas and notice is None:
        alert = RiskAlert(
            vinculacion_id=ctx.binding.id,
            dispositivo_id=ctx.device.id,
            event_id=uuid.uuid5(uuid.NAMESPACE_URL, f"access:{ctx.device.id}:{now.isoformat()}"),
            categoria=ACCESS_CATEGORY,
            severidad=Severidad.ALTA.value,
            score=88,
            reglas=[
                {
                    "code": ACCESS_CATEGORY,
                    "label": "Acceso a notificaciones desactivado",
                    "source": "system",
                    "dnd": body.dnd,
                    "reported_at": now.isoformat(),
                }
            ],
            resumen=(
                "El acceso a notificaciones se desactivó en el teléfono. La guardia "
                "queda incompleta hasta que se reactive: puede ser un cambio en Ajustes "
                "o el sistema. Revisa el teléfono y vuelve a activarlo."
            ),
        )
        db.add(alert)
        db.add(AccessNotice(device_id=ctx.device.id, vinculacion_id=ctx.binding.id, avisado_en=now))
    elif body.notificaciones_activas and notice is not None:
        await db.delete(notice)
        restored = True

    await db.commit()

    if alert is not None:
        await notify_alert(
            db,
            tutor_id=ctx.binding.tutor_id,
            alert=alert,
            device_alias=ctx.device.alias,
            etiqueta_menor=ctx.binding.etiqueta_menor,
            allow_push=True,
        )

    if restored:
        tutor_hub.publish(
            ctx.binding.tutor_id,
            {
                "v": 1,
                "type": "device_status",
                "ts": now.isoformat(),
                "data": {
                    "device_id": str(ctx.device.id),
                    "device_alias": ctx.device.alias,
                    "etiqueta_menor": ctx.binding.etiqueta_menor,
                    "notificaciones_activas": True,
                    "dnd": body.dnd,
                },
            },
        )
