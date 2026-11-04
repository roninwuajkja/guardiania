from datetime import timedelta

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import utcnow
from ..models import FeatureCapture, RiskAlert, Severidad
from ..schemas import DerivedFeatureVector

# code -> (human label, severity, score, advice)
FLAG_RULES: dict[str, tuple[str, Severidad, int, str]] = {
    "self_harm": ("Posible autolesión", Severidad.CRITICA, 98, "Acércate y conversa hoy. Si hay riesgo inmediato, contacta a un adulto de confianza o a salud."),
    "grooming": ("Posible grooming", Severidad.CRITICA, 96, "Revisa con calma con quién conversa y recuerda que no estás solo en esto."),
    "sextortion": ("Sextorsión o chantaje con imágenes", Severidad.CRITICA, 97, "No borres la evidencia y busca apoyo urgente; no cedas al chantaje."),
    "abuse": ("Posible abuso o maltrato", Severidad.CRITICA, 97, "Prioriza la seguridad del menor y busca ayuda profesional o denuncia si hay riesgo inmediato."),
    "trafficking": ("Posible trata o explotación", Severidad.CRITICA, 96, "Actúa de inmediato y contacta a las autoridades o a un adulto de confianza."),
    "radicalization": ("Posible radicalización", Severidad.CRITICA, 92, "Conversa sin confrontar y busca orientación especializada."),
    "sexual_content": ("Contenido sexual", Severidad.ALTA, 86, "Abre la conversación sin castigo y revisa la configuración de la app."),
    "sexual_harassment": ("Acoso sexual", Severidad.ALTA, 86, "Escucha sin minimizar y busca apoyo; no permitas que se repita."),
    "threat": ("Amenaza", Severidad.ALTA, 76, "Pregunta cómo está y qué pasó; si hay riesgo inmediato, busca ayuda."),
    "stalking": ("Acecho o seguimiento", Severidad.ALTA, 82, "Toma en serio la situación y resguarda su seguridad."),
    "violence": ("Violencia o amenazas", Severidad.ALTA, 84, "Pregunta cómo está y qué pasó. Evita reaccionar en caliente."),
    "weapons": ("Armas o amenazas con armas", Severidad.ALTA, 86, "Verifica la situación y resguarda la seguridad; considera ayuda externa."),
    "coercion": ("Presión o chantaje", Severidad.ALTA, 82, "Habla con calma sobre esa relación y deja claro que puede pedir ayuda sin miedo."),
    "bullying": ("Acoso escolar", Severidad.ALTA, 80, "Escucha sin minimizar y considera avisar al colegio si se repite."),
    "hate_speech": ("Discurso de odio o discriminación", Severidad.ALTA, 78, "Conversa sobre el respeto y busca apoyo si el discurso se repite."),
    "insult_severe": ("Insulto grave", Severidad.ALTA, 78, "Escucha sin minimizar y busca apoyo si hay amenazas o se repite."),
    "insult_moderate": ("Insulto moderado", Severidad.MEDIA, 55, "Habla con calma sobre el respeto y revisa si se repite."),
    "insult_mild": ("Insulto leve", Severidad.MEDIA, 44, "Pregunta qué pasó y recuerda que los insultos también dañan."),
    "eating_disorder": ("Señales de trastorno alimentario", Severidad.ALTA, 80, "Evita comentar el cuerpo y busca orientación profesional."),
    "doxxing": ("Exposición de datos personales", Severidad.ALTA, 82, "Revisa qué se publicó y ayuda a proteger sus datos y cuentas."),
    "runaway": ("Riesgo de fuga o desaparición", Severidad.ALTA, 84, "Habla con calma y asegúrate de que esté en un lugar seguro."),
    "dangerous_challenge": ("Reto viral peligroso", Severidad.ALTA, 86, "Conversa sobre el riesgo real y revisa qué está viendo en redes."),
    "drugs": ("Drogas o alcohol", Severidad.ALTA, 74, "Conversa sobre lo que vio y ofrece apoyo antes que sanción."),
    "gambling": ("Apuestas", Severidad.MEDIA, 58, "Revisa si hay dinero involucrado y pon límites claros."),
    "malestar": ("Malestar emocional", Severidad.MEDIA, 54, "Pregúntale cómo se siente y qué necesita; no minimices lo que cuenta."),
    "scam_points": ("Canje de puntos sospechoso", Severidad.MEDIA, 58, "No canjees puntos ni entregues datos; verifica siempre en la app oficial."),
    "scam_casino": ("Invitación a casino o apuestas", Severidad.MEDIA, 60, "No ingreses dinero ni datos; conversa sobre apuestas y límites."),
    "scam_financial": ("Inversión o cripto sospechosa", Severidad.MEDIA, 62, "Desconfía de ganancias garantizadas y no transfieras dinero."),
    "scam_account": ("Suplantación o phishing", Severidad.MEDIA, 64, "No compartas códigos ni contraseñas; verifica por el canal oficial."),
    "scam_link": ("Enlace sospechoso", Severidad.MEDIA, 64, "No abras el enlace ni entregues datos; verifica el remitente por un canal oficial."),
    "scam_prize": ("Premio o sorteo sospechoso", Severidad.MEDIA, 58, "No pagues ni entregues datos para reclamar un premio."),
    "scam_delivery": ("Paquete o aduana sospechosa", Severidad.MEDIA, 55, "Verifica el envío en el sitio oficial y no pagues enlaces desconocidos."),
    "scam_job": ("Oferta de trabajo sospechosa", Severidad.MEDIA, 55, "Desconfía de trabajos fáciles que piden dinero o datos personales."),
    "scam_romance": ("Posible estafa romántica", Severidad.MEDIA, 60, "No envíes dinero ni datos a alguien que no conoces."),
    "scam": ("Posible estafa o phishing", Severidad.MEDIA, 56, "No compartas datos ni códigos; verifica el remitente por otro canal."),
    "unknown_contact": ("Contacto desconocido", Severidad.MEDIA, 50, "Pregunta quién es y cómo se conocieron."),
}

# Flags que el servidor calcula por contexto y que no deben generar una alerta
# genérica si llegan desde una versión nueva de la app.
_SERVER_FLAGS = {
    "uso_nocturno",
    "uso_prolongado",
    "uso_sin_notificaciones",
    "contacto_recurrente",
    "sin_senal",
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

    # Una app más nueva puede enviar una señal que este servidor aún no
    # conoce. No se ignora: se crea una alerta MEDIA genérica para que el tutor
    # revise el contexto. Las señales calculadas por el servidor se excluyen.
    for flag in vector.flags:
        if flag in FLAG_RULES or flag in _SERVER_FLAGS:
            continue
        alert = await _create_alert(
            db,
            ctx=ctx,
            vector=vector,
            categoria=flag[:40],
            label="Señal de riesgo no catalogada",
            severity=Severidad.MEDIA,
            score=50,
            advice="Revisa la situación con calma y conversa con tu hijo o hija.",
            detail={"flag": flag, "origen": "app"},
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
