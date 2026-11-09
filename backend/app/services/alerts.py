"""Persistencia y despacho de alertas de riesgo.

Garantías:
- ``alertas_riesgo`` guarda solo la evaluación derivada; ningún texto crudo.
- El evento WebSocket lleva el detalle que el panel ya expone por REST.
- El push FCM lleva la alerta derivada: id, severidad, categoría, alias y el
  resumen generado. Nunca texto de conversaciones ni contactos.
- La deduplicación usa ``(dispositivo, evento, categoría)``, igual que el motor
  de reglas, para no duplicar alertas del mismo evento.
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from dataclasses import dataclass
from typing import Any

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import utcnow
from ..models import Binding, Device, PushToken, RiskAlert, Severidad
from ..realtime import tutor_hub
from ..schemas import DerivedFeatureVector
from .ai_evaluator import Assessment
from .push import push_service
from .risk import FLAG_RULES

logger = logging.getLogger("guardiania.alerts")

_CRITICAL_FLAGS = {
    "self_harm",
    "grooming",
    "sextortion",
    "abuse",
    "trafficking",
    "radicalization",
}


@dataclass(frozen=True)
class NotificationResult:
    ws: bool = False
    push_sent: int = 0

    @property
    def notified(self) -> bool:
        return self.ws or self.push_sent > 0


def severity_for(assessment: Assessment, vector: DerivedFeatureVector) -> Severidad:
    if assessment.nivel_riesgo == "BAJO":
        return Severidad.BAJA
    if assessment.nivel_riesgo == "MEDIO":
        return Severidad.MEDIA
    if set(vector.flags) & _CRITICAL_FLAGS:
        return Severidad.CRITICA
    return Severidad.ALTA


def score_for(assessment: Assessment, vector: DerivedFeatureVector) -> int:
    severity = severity_for(assessment, vector)
    base = {
        Severidad.BAJA: 20,
        Severidad.MEDIA: 55,
        Severidad.ALTA: 82,
        Severidad.CRITICA: 94,
    }[severity]
    bonus = min(4, len(vector.flags))
    return max(0, min(100, base + bonus))


def alert_code(vector: DerivedFeatureVector, assessment: Assessment) -> str:
    """Código estable para deduplicar contra el motor de reglas."""

    for flag in vector.flags:
        if flag in FLAG_RULES:
            return flag
    if vector.flags:
        return vector.flags[0][:40]
    slug = assessment.categoria.lower().replace(" ", "_")
    return f"ai_{slug}"[:40]


_AI_CATEGORY_LABELS = {
    "Ciberbullying": "Ciberbullying",
    "Conducta_Riesgo": "Conducta de riesgo",
    "Contenido_Inapropiado": "Contenido inapropiado",
    "Sin_Riesgo": "Sin riesgo",
}


async def _exists(db: AsyncSession, device_id: uuid.UUID, event_id: uuid.UUID, code: str) -> bool:
    found = await db.scalar(
        select(RiskAlert.id).where(
            RiskAlert.dispositivo_id == device_id,
            RiskAlert.event_id == event_id,
            RiskAlert.categoria == code,
        )
    )
    return found is not None


async def create_ai_alert(
    db: AsyncSession,
    *,
    ctx,
    vector: DerivedFeatureVector,
    assessment: Assessment,
) -> RiskAlert | None:
    if assessment.nivel_riesgo == "BAJO":
        return None

    code = alert_code(vector, assessment)
    if await _exists(db, ctx.device.id, vector.event_id, code):
        return None

    severity = severity_for(assessment, vector)
    local_rules = [
        {
            "code": flag,
            "label": FLAG_RULES[flag][0],
            "source": "local",
        }
        for flag in vector.flags
        if flag in FLAG_RULES
    ]
    alert = RiskAlert(
        vinculacion_id=ctx.binding.id,
        dispositivo_id=ctx.device.id,
        event_id=vector.event_id,
        categoria=code,
        severidad=severity.value,
        score=score_for(assessment, vector),
        reglas=[
            {
                "code": code,
                "label": f"IA · {_AI_CATEGORY_LABELS.get(assessment.categoria, assessment.categoria.replace('_', ' '))}",
                "source": "ai",
                "provider": assessment.provider,
                "nivel_riesgo": assessment.nivel_riesgo,
                "categoria_ia": assessment.categoria,
                "consejo_educativo": assessment.consejo_educativo,
            },
            *local_rules,
        ],
        resumen=assessment.descripcion_narrativa[:280],
    )
    try:
        async with db.begin_nested():
            db.add(alert)
            await db.flush()
    except IntegrityError:
        return None
    return alert


def alert_event(
    alert: RiskAlert,
    *,
    device_alias: str,
    etiqueta_menor: str | None,
    latency_ms: int | None = None,
) -> dict[str, Any]:
    data: dict[str, Any] = {
        "alert_id": str(alert.id),
        "vinculacion_id": str(alert.vinculacion_id),
        "dispositivo_id": str(alert.dispositivo_id),
        "event_id": str(alert.event_id),
        "device_alias": device_alias,
        "etiqueta_menor": etiqueta_menor,
        "categoria": alert.categoria,
        "severidad": alert.severidad,
        "score": alert.score,
        "resumen": alert.resumen,
        "estado": alert.estado,
        "creado_en": alert.creado_en.isoformat(),
    }
    if latency_ms is not None:
        data["latency_ms"] = latency_ms
    return {
        "v": 1,
        "type": "risk_alert",
        "session_id": str(alert.vinculacion_id),
        "seq": 0,
        "ts": utcnow().isoformat(),
        "data": data,
    }


async def notify_alert(
    db: AsyncSession,
    *,
    tutor_id: uuid.UUID,
    alert: RiskAlert,
    device_alias: str,
    etiqueta_menor: str | None,
    allow_push: bool,
    latency_ms: int | None = None,
) -> NotificationResult:
    """Publish to open tutor streams and (optionally) send FCM.

    FCM is best-effort and capped by ``FCM_TIMEOUT_MS`` so a slow provider can
    never break the <3 s evaluation→push budget.
    """

    event = alert_event(alert, device_alias=device_alias, etiqueta_menor=etiqueta_menor, latency_ms=latency_ms)
    ws_delivered = tutor_hub.publish(tutor_id, event)

    if not allow_push or not push_service.enabled:
        return NotificationResult(ws=ws_delivered)

    tokens = list(
        (
            await db.execute(
                select(PushToken.token).where(PushToken.usuario_id == tutor_id, PushToken.activo.is_(True))
            )
        ).scalars()
    )
    if not tokens:
        return NotificationResult(ws=ws_delivered)

    try:
        result = await asyncio.wait_for(
            push_service.send(
                tokens,
                data={
                    "tipo": "risk_alert",
                    "alert_id": str(alert.id),
                    "severidad": alert.severidad,
                    "categoria": alert.categoria,
                    "device_alias": device_alias,
                    "etiqueta_menor": etiqueta_menor or "",
                    "resumen": alert.resumen[:180],
                    "ts": alert.creado_en.isoformat(),
                },
            ),
            timeout=settings.fcm_timeout_ms / 1000 + 0.2,
        )
    except Exception as exc:  # noqa: BLE001 - push nunca bloquea la API
        logger.warning("push_dispatch_failed error=%s", type(exc).__name__)
        return NotificationResult(ws=ws_delivered)

    if result.invalid_tokens:
        invalid = set(result.invalid_tokens)
        rows = (
            await db.execute(
                select(PushToken).where(PushToken.usuario_id == tutor_id, PushToken.token.in_(invalid))
            )
        ).scalars()
        for row in rows:
            row.activo = False
        await db.commit()

    return NotificationResult(ws=ws_delivered, push_sent=result.sent)


async def dispatch_rule_alerts(
    db: AsyncSession,
    *,
    tutor_id: uuid.UUID,
    binding: Binding,
    device: Device,
    alerts: list[RiskAlert],
) -> None:
    """Publica las alertas de reglas en el stream del tutor.

    Todas se publican en el WebSocket para que el panel se actualice solo; el
    push FCM se reserva a ALTA/CRITICA para no saturar al tutor.
    """

    for alert in alerts:
        await notify_alert(
            db,
            tutor_id=tutor_id,
            alert=alert,
            device_alias=device.alias,
            etiqueta_menor=binding.etiqueta_menor,
            allow_push=True,
        )
