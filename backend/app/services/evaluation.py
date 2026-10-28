"""Caso de uso: evaluar un vector con IA y notificar al tutor.

La ruta REST solo aporta autenticación y rate limit; toda la lógica vive aquí
para que también pueda invocarse desde tests o desde un futuro consumidor
WebSocket sin duplicar reglas de privacidad.
"""

from __future__ import annotations

import time
import uuid
from datetime import timedelta

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import utcnow
from ..schemas import DerivedFeatureVector, EvaluationResponse
from .ai_evaluator import ai_evaluator
from .alerts import create_ai_alert, notify_alert, severity_for
from .audit import record
from .captures import persist_capture


def validate_vector(ctx, vector: DerivedFeatureVector, now) -> None:
    if vector.device_id != ctx.device.id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="device_id no coincide con el token del dispositivo",
        )
    if vector.time_window.start < now - timedelta(days=7):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="La ventana de captura es demasiado antigua",
        )
    if vector.time_window.end > now + timedelta(minutes=10):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="La ventana de captura está en el futuro",
        )


async def evaluate_vector(db: AsyncSession, ctx, vector: DerivedFeatureVector) -> EvaluationResponse:
    started = time.monotonic()
    now = utcnow()
    validate_vector(ctx, vector, now)

    await persist_capture(db, ctx, vector, now)

    assessment = await ai_evaluator.evaluate(vector)
    alert = await create_ai_alert(db, ctx=ctx, vector=vector, assessment=assessment)
    severity = severity_for(assessment, vector)

    await record(
        db,
        "ai.evaluate",
        usuario_id=ctx.binding.tutor_id,
        entidad="alerta" if alert else "captura",
        entidad_id=alert.id if alert else vector.event_id,
        detalle={
            "device_id": str(ctx.device.id),
            "event_id": str(vector.event_id),
            "provider": assessment.provider,
            "nivel_riesgo": assessment.nivel_riesgo,
            "categoria_ia": assessment.categoria,
            "cached": assessment.cached,
            "ai_latency_ms": assessment.latency_ms,
        },
    )

    ctx.device.ultima_conexion = now
    await db.commit()

    notified = False
    if alert is not None:
        result = await notify_alert(
            db,
            tutor_id=ctx.binding.tutor_id,
            alert=alert,
            device_alias=ctx.device.alias,
            etiqueta_menor=ctx.binding.etiqueta_menor,
            allow_push=assessment.nivel_riesgo == "ALTO",
        )
        notified = result.notified

    total_ms = max(0, int((time.monotonic() - started) * 1000))
    return EvaluationResponse(
        event_id=vector.event_id,
        evaluation=assessment.to_result(),
        alert_id=alert.id if alert else None,
        severidad=severity.value,
        provider=assessment.provider,
        cached=assessment.cached,
        latency_ms=total_ms,
        notified=notified,
        server_time=now,
    )
