from datetime import timedelta

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import utcnow
from ..models import FeatureCapture, RiskAlert, Severidad
from ..schemas import DerivedFeatureVector

# code -> (human label, severity, score, advice)
FLAG_RULES: dict[str, tuple[str, Severidad, int, str]] = {
    "self_harm": ("Posible autolesión", Severidad.CRITICA, 95, "Acércate y conversa hoy. Si hay riesgo inmediato, contacta a un adulto de confianza o a salud."),
    "grooming": ("Posible grooming", Severidad.CRITICA, 92, "Revisa con calma con quién conversa y recuerda que no estás solo en esto."),
    "sexual_content": ("Contenido sexual", Severidad.ALTA, 82, "Abre la conversación sin castigo y revisa la configuración de la app."),
    "violence": ("Violencia", Severidad.ALTA, 78, "Pregunta cómo está y qué pasó. Evita reaccionar en caliente."),
    "bullying": ("Acoso escolar", Severidad.ALTA, 74, "Escucha sin minimizar y considera avisar al colegio si se repite."),
    "drugs": ("Drogas o alcohol", Severidad.ALTA, 70, "Conversa sobre lo que vio y ofrece apoyo antes que sanción."),
    "gambling": ("Apuestas", Severidad.MEDIA, 58, "Revisa si hay dinero involucrado y pon límites claros."),
    "unknown_contact": ("Contacto desconocido", Severidad.MEDIA, 50, "Pregunta quién es y cómo se conocieron."),
}

ADVICE_GENERIC = "Revisa la situación con calma y conversa con tu hijo o hija."


def _summary(advice: str) -> str:
    return advice if len(advice) <= 280 else advice[:277] + "…"


async def _has_alert(db: AsyncSession, device_id, event_id, categoria: str) -> bool:
    found = await db.scalar(
        select(RiskAlert.id).where(
            RiskAlert.dispositivo_id == device_id,
            RiskAlert.event_id == event_id,
            RiskAlert.categoria == categoria,
        )
    )
    return found is not None


async def _create_alert(
    db: AsyncSession,
    *,
    ctx,
    vector: DerivedFeatureVector,
    categoria: str,
    label: str,
    severity: Severidad,
    score: int,
    advice: str,
    detail: dict,
) -> RiskAlert | None:
    if await _has_alert(db, ctx.device.id, vector.event_id, categoria):
        return None
    alert = RiskAlert(
        vinculacion_id=ctx.binding.id,
        dispositivo_id=ctx.device.id,
        event_id=vector.event_id,
        categoria=categoria,
        severidad=severity.value,
        score=score,
        reglas=[{"code": categoria, "label": label, "evidence": detail}],
        resumen=_summary(advice),
    )
    try:
        async with db.begin_nested():
            db.add(alert)
            await db.flush()
    except IntegrityError:
        return None
    return alert


async def evaluate_capture(db: AsyncSession, ctx, vector: DerivedFeatureVector) -> list[RiskAlert]:
    """Deterministic, explainable rules. Never reads message content."""
    created: list[RiskAlert] = []

    for flag in vector.flags:
        rule = FLAG_RULES.get(flag)
        if rule is None:
            continue
        label, severity, score, advice = rule
        alert = await _create_alert(
            db,
            ctx=ctx,
            vector=vector,
            categoria=flag,
            label=label,
            severity=severity,
            score=score,
            advice=advice,
            detail={"flag": flag, "ventana": vector.time_window.start.isoformat()},
        )
        if alert:
            created.append(alert)

    local_start = vector.time_window.start + timedelta(minutes=vector.time_window.tz_offset_minutes)
    if 0 <= local_start.hour < 6 and vector.usage_duration_minutes >= 20 and "uso_nocturno" not in vector.flags:
        alert = await _create_alert(
            db,
            ctx=ctx,
            vector=vector,
            categoria="uso_nocturno",
            label="Uso en horario nocturno",
            severity=Severidad.MEDIA,
            score=55,
            advice="Revisa por qué estuvo despierto y acuerden un horario de descanso.",
            detail={"hora_local": local_start.strftime("%H:%M"), "minutos": vector.usage_duration_minutes},
        )
        if alert:
            created.append(alert)

    if vector.usage_duration_minutes >= 180:
        severity = Severidad.ALTA if vector.usage_duration_minutes >= 300 else Severidad.MEDIA
        alert = await _create_alert(
            db,
            ctx=ctx,
            vector=vector,
            categoria="uso_prolongado",
            label="Uso prolongado",
            severity=severity,
            score=72 if severity is Severidad.ALTA else 60,
            advice="Propón una pausa y revisen juntos cuánto tiempo es razonable.",
            detail={"minutos": vector.usage_duration_minutes},
        )
        if alert:
            created.append(alert)

    if vector.hashed_contact_id:
        since = vector.time_window.start - timedelta(hours=24)
        repetitions = await db.scalar(
            select(func.count(func.distinct(FeatureCapture.event_id))).where(
                FeatureCapture.dispositivo_id == ctx.device.id,
                FeatureCapture.hashed_contact_id == vector.hashed_contact_id,
                FeatureCapture.time_window_start >= since,
            )
        )
        if (repetitions or 0) >= 3:
            alert = await _create_alert(
                db,
                ctx=ctx,
                vector=vector,
                categoria="contacto_recurrente",
                label="Contacto desconocido recurrente",
                severity=Severidad.ALTA,
                score=76,
                advice="Pregunta quién es y cómo se conocieron, sin interrogar.",
                detail={"ventanas_24h": int(repetitions or 0)},
            )
            if alert:
                created.append(alert)

    return created
