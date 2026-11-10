"""Evaluación de riesgo multi-proveedor con degradación segura.

Regla Cero: este módulo solo recibe ``DerivedFeatureVector``. No existe ningún
camino de código que envíe texto, nombres, teléfonos o contenido de
notificaciones a un proveedor externo. ``AI_SEND_SNIPPET`` es una compuerta
cerrada y sin llamadores en 1.7.14.

Orden de ejecución:
    1. caché en memoria por hash del vector (5 min)
    2. proveedor primario (Groq, Gemini, OpenAI o Anthropic) con circuit breaker
    3. proveedor fallback (el siguiente configurado) con circuit breaker
    4. heurística local (siempre disponible)

El presupuesto total nunca supera ``AI_TOTAL_TIMEOUT_MS`` (1500 ms por
defecto). Un fallo de proveedor jamás hace fallar la petición del dispositivo.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
import time
from collections import OrderedDict
from dataclasses import dataclass, replace
from datetime import timedelta
from typing import Any, Protocol

import httpx

from ..config import settings
from ..schemas import DerivedFeatureVector, RiskEvaluationResult
from .risk import FLAG_RULES

logger = logging.getLogger("guardiania.ai")

RISK_LEVELS = ("BAJO", "MEDIO", "ALTO")
AI_CATEGORIES = ("Ciberbullying", "Conducta_Riesgo", "Contenido_Inapropiado", "Sin_Riesgo")

# Orden de gravedad usado por la heurística local. Debe coincidir con
# ``risk.FLAG_RULES`` para que el panel no muestre criterios contradictorios.
_FLAG_PRIORITY = (
    "self_harm",
    "grooming",
    "sextortion",
    "abuse",
    "trafficking",
    "radicalization",
    "sexual_content",
    "sexual_harassment",
    "violence",
    "threat",
    "weapons",
    "coercion",
    "bullying",
    "hate_speech",
    "insult_severe",
    "insult_moderate",
    "insult_mild",
    "eating_disorder",
    "doxxing",
    "stalking",
    "runaway",
    "dangerous_challenge",
    "drugs",
    "gambling",
    "malestar",
    "scam_points",
    "scam_casino",
    "scam_financial",
    "scam_account",
    "scam_link",
    "scam_prize",
    "scam_delivery",
    "scam_job",
    "scam_romance",
    "scam",
    "unknown_contact",
)

_FLAG_LABELS = {
    "self_harm": "Posible autolesión",
    "grooming": "Posible grooming",
    "sextortion": "Sextorsión o chantaje con imágenes",
    "abuse": "Posible abuso o maltrato",
    "trafficking": "Posible trata o explotación",
    "radicalization": "Posible radicalización",
    "sexual_content": "Contenido sexual",
    "sexual_harassment": "Acoso sexual",
    "violence": "Violencia o amenazas",
    "threat": "Amenaza",
    "weapons": "Armas o amenazas con armas",
    "coercion": "Presión o chantaje",
    "bullying": "Acoso escolar",
    "hate_speech": "Discurso de odio o discriminación",
    "insult_severe": "Insulto grave",
    "insult_moderate": "Insulto moderado",
    "insult_mild": "Insulto leve",
    "eating_disorder": "Señales de trastorno alimentario",
    "doxxing": "Exposición de datos personales",
    "stalking": "Acecho o seguimiento",
    "runaway": "Riesgo de fuga o desaparición",
    "dangerous_challenge": "Reto viral peligroso",
    "drugs": "Drogas o alcohol",
    "gambling": "Apuestas",
    "malestar": "Malestar emocional",
    "scam_points": "Canje de puntos sospechoso",
    "scam_casino": "Invitación a casino o apuestas",
    "scam_financial": "Inversión o cripto sospechosa",
    "scam_account": "Suplantación o phishing",
    "scam_link": "Enlace sospechoso",
    "scam_prize": "Premio o sorteo sospechoso",
    "scam_delivery": "Paquete o aduana sospechosa",
    "scam_job": "Oferta de trabajo sospechosa",
    "scam_romance": "Posible estafa romántica",
    "scam": "Posible estafa o phishing",
    "unknown_contact": "Contacto desconocido",
}

_SEXUAL_CATEGORY_FLAGS = {
    "grooming",
    "sextortion",
    "sexual_content",
    "sexual_harassment",
    "trafficking",
}

_BULLYING_CATEGORY_FLAGS = {
    "bullying",
    "hate_speech",
    "insult_severe",
    "insult_moderate",
    "insult_mild",
}


def _ordered_flags(flags: list[str]) -> list[str]:
    """Deduplica y ordena por la misma prioridad que usa la heurística."""

    seen: set[str] = set()
    ordered: list[str] = []
    for flag in _FLAG_PRIORITY:
        if flag in flags and flag not in seen:
            ordered.append(flag)
            seen.add(flag)
    for flag in flags:
        if flag not in seen:
            ordered.append(flag)
            seen.add(flag)
    return ordered


def _primary_flag(flags: list[str]) -> str | None:
    ordered = _ordered_flags(flags)
    return ordered[0] if ordered else None


def _category_for_flag(flag: str | None) -> str:
    if flag in _SEXUAL_CATEGORY_FLAGS:
        return "Contenido_Inapropiado"
    if flag in _BULLYING_CATEGORY_FLAGS:
        return "Ciberbullying"
    return "Conducta_Riesgo"


def _signal_labels(flags: list[str]) -> list[str]:
    return [_FLAG_LABELS.get(flag, "Señal local") for flag in _ordered_flags(flags)]

# Señales que por sí solas justifican nivel ALTO.
_SERIOUS_FLAGS = {
    "self_harm",
    "grooming",
    "sextortion",
    "abuse",
    "trafficking",
    "radicalization",
    "sexual_content",
    "sexual_harassment",
    "violence",
    "threat",
    "weapons",
    "coercion",
    "bullying",
    "hate_speech",
    "insult_severe",
    "eating_disorder",
    "doxxing",
    "stalking",
    "runaway",
    "dangerous_challenge",
    "drugs",
}

# Señales que, combinadas con cualquier otra, elevan la alerta a CRITICA.
_CRITICAL_FLAGS = {
    "self_harm",
    "grooming",
    "sextortion",
    "abuse",
    "trafficking",
    "radicalization",
}

# Volumen de notificaciones que, sin señales léxicas, ya merece una revisión.
_VOLUME_THRESHOLD = 20
_NIGHT_VOLUME_THRESHOLD = 8

_HIGH_FLAGS = _SERIOUS_FLAGS
_MEDIUM_FLAGS = {
    "insult_moderate",
    "insult_mild",
    "gambling",
    "malestar",
    "scam_points",
    "scam_casino",
    "scam_financial",
    "scam_account",
    "scam_link",
    "scam_prize",
    "scam_delivery",
    "scam_job",
    "scam_romance",
    "scam",
    "unknown_contact",
}

_DEFAULT_NARRATIVE = {
    "ALTO": "Se detectaron señales de riesgo alto en la actividad monitoreada.",
    "MEDIO": "Se detectaron señales que conviene revisar con calma.",
    "BAJO": "Sin señales de riesgo relevantes en la ventana analizada.",
}

_DEFAULT_ADVICE = {
    "ALTO": "Acércate hoy, escucha sin castigar y considera apoyo profesional si se repite.",
    "MEDIO": "Conversa con calma sobre lo que vio y acuerden límites claros.",
    "BAJO": "Mantén una conversación abierta y revisa juntos el uso del teléfono.",
}

SYSTEM_PROMPT = """Eres un evaluador de riesgo de protección de menores para la app IA Guardian.
Recibes ÚNICAMENTE un vector de características derivadas: categoría de uso, señales
léxicas anonimizadas, duración, conteos y hora local. Nunca recibes texto, nombres,
teléfonos ni contenido de mensajes.

Responde EXCLUSIVAMENTE un objeto JSON válido, sin markdown, con esta forma:
{"nivel_riesgo":"BAJO|MEDIO|ALTO","categoria":"Ciberbullying|Conducta_Riesgo|Contenido_Inapropiado|Sin_Riesgo","descripcion_narrativa":"...","consejo_educativo":"..."}

Señales críticas: autolesión, grooming, sextorsión, abuso o maltrato, trata o
explotación, radicalización. Señales graves: contenido sexual, acoso sexual,
violencia o armas, amenaza, acecho o seguimiento, presión o chantaje, acoso,
insulto grave, discurso de odio, trastorno alimentario, exposición de datos,
fuga, retos peligrosos y drogas. Señales medias: insulto leve o moderado,
apuestas, malestar, enlaces sospechosos y estafas (puntos, casino, inversión,
cuenta, enlace, premio, envío, trabajo, romance) y contacto desconocido.

Cómo decidir (en este orden):
1. Si hay CUALQUIER señal, el nivel NUNCA es BAJO: como mínimo es MEDIO.
2. Si hay una señal crítica o grave, o dos o más señales distintas, el nivel es ALTO.
3. Si hay una sola señal media, el nivel es MEDIO.
4. Mucha actividad en la ventana (20+ notificaciones o 2+ por minuto) en
   categoría social o mensajería: como mínimo MEDIO; si además hay una señal, ALTO.
5. Horario nocturno (00:00–05:59) con señales o notificaciones abundantes:
   como mínimo MEDIO; si hay una señal grave, ALTO.
6. Contacto desconocido con otras señales o muchas notificaciones: como mínimo
   MEDIO; si hay señales graves, ALTO.
7. Solo responde BAJO cuando no haya ninguna señal y el uso sea razonable.
   Si `uso_sin_notificaciones` es true, considera que las notificaciones pueden
   estar silenciadas: no es grave por sí sola, pero súbela a MEDIO si hay otra
   señal o es de madrugada.

Si `flags_detalle` trae VARIAS señales, la narrativa debe nombrar todas las
señales críticas y graves (hasta 5), no solo la primera. Resume las medias.
No omitas una señal crítica por centrarte en otra. La categoría debe
corresponder a la señal principal más grave: Contenido_Inapropiado si hay
grooming, sextorsión, contenido sexual, acoso sexual o trata; Ciberbullying si
la principal es acoso, insulto o discurso de odio; Conducta_Riesgo para el resto.
Usa las etiquetas en español de `flags_detalle`; no menciones códigos internos
como `scam_link`, `self_harm` o `sexual_harassment`. No inventes señales que no
estén en el vector.

Categoría: Ciberbullying para acoso, insultos y discurso de odio;
Contenido_Inapropiado para grooming, sextorsión, contenido sexual, acoso sexual
y trata; Conducta_Riesgo para el resto. Sin_Riesgo solo si el nivel es BAJO.

Una estafa que pida dinero, códigos, contraseñas o datos, o un insulto grave
repetido, debe priorizarse como ALTO. Un insulto leve aislado no es ALTO por sí
solo. No minimices por tratarse de una sola notificación. No inventes hechos que no
estén en el vector. No repitas ni pidas datos personales. Si hay una señal crítica,
el consejo debe priorizar la seguridad y la ayuda inmediata. Español de Chile, tono
respetuoso, sin culpabilizar al menor. Máximo 280 caracteres en cada texto.
"""

_CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


def _error_detail(response: httpx.Response) -> str:
    """Resumen corto del error del proveedor, sin cuerpo completo ni secretos."""

    try:
        error = response.json().get("error") or {}
    except ValueError:
        return ""
    status = str(error.get("status") or "").strip()
    message = str(error.get("message") or "").strip()[:120]
    if not status and not message:
        return ""
    return f" {status}: {message}" if status else f" {message}"

# Esquema estricto para la API nativa de Gemini. Solo describe el contrato de
# salida; no contiene ni admite texto del dispositivo.
GEMINI_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "OBJECT",
    "properties": {
        "nivel_riesgo": {"type": "STRING", "enum": list(RISK_LEVELS)},
        "categoria": {"type": "STRING", "enum": list(AI_CATEGORIES)},
        "descripcion_narrativa": {"type": "STRING"},
        "consejo_educativo": {"type": "STRING"},
    },
    "required": ["nivel_riesgo", "categoria", "descripcion_narrativa", "consejo_educativo"],
    "propertyOrdering": [
        "nivel_riesgo",
        "categoria",
        "descripcion_narrativa",
        "consejo_educativo",
    ],
}

# Esquema estricto para APIs compatibles con OpenAI (Groq). A diferencia del de
# Gemini usa tipos en minúscula y ``additionalProperties: false``, que es lo que
# exige el modo ``strict``. Solo describe la salida: no admite texto del equipo.
JSON_SCHEMA_RESPONSE: dict[str, Any] = {
    "name": "evaluacion_riesgo",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {
            "nivel_riesgo": {"type": "string", "enum": list(RISK_LEVELS)},
            "categoria": {"type": "string", "enum": list(AI_CATEGORIES)},
            "descripcion_narrativa": {"type": "string"},
            "consejo_educativo": {"type": "string"},
        },
        "required": [
            "nivel_riesgo",
            "categoria",
            "descripcion_narrativa",
            "consejo_educativo",
        ],
        "additionalProperties": False,
    },
}

# Orden de preferencia cuando AI_PROVIDER=auto. Groq primero por latencia y
# cuota del free tier; Gemini queda como alternativa configurable.
PROVIDER_ORDER = ("groq", "gemini", "openai", "anthropic")


class AIProviderError(Exception):
    """Fallo recuperable de un proveedor; el pipeline degrada al siguiente."""

    def __init__(self, message: str, *, provider: str = "unknown") -> None:
        super().__init__(message)
        self.provider = provider


class AIProvider(Protocol):
    name: str

    async def classify(self, vector: DerivedFeatureVector, *, timeout_seconds: float) -> "Assessment":
        ...


@dataclass(frozen=True)
class Assessment:
    nivel_riesgo: str
    categoria: str
    descripcion_narrativa: str
    consejo_educativo: str
    provider: str
    cached: bool = False
    latency_ms: int = 0

    def to_result(self) -> RiskEvaluationResult:
        return RiskEvaluationResult(
            nivel_riesgo=self.nivel_riesgo,  # type: ignore[arg-type]
            categoria=self.categoria,  # type: ignore[arg-type]
            descripcion_narrativa=self.descripcion_narrativa,
            consejo_educativo=self.consejo_educativo,
        )


def _clean_text(value: Any, fallback: str) -> str:
    if not isinstance(value, str):
        return fallback
    text = _CONTROL_CHARS.sub(" ", value).strip()
    text = re.sub(r"\s+", " ", text)
    if not text:
        return fallback
    text = _humanize_codes(text)
    return text[:280]


_HUMANIZE_SKIP = {"malestar", "grooming"}
_HUMANIZE_PAIRS = sorted(
    [
        (variant, label)
        for code, label in _FLAG_LABELS.items()
        if code not in _HUMANIZE_SKIP
        for variant in {code, code.replace("_", " ")}
    ],
    key=lambda item: len(item[0]),
    reverse=True,
)


def _humanize_codes(text: str) -> str:
    """Evita que el tutor lea códigos internos en la narrativa de la IA."""

    for code, label in _HUMANIZE_PAIRS:
        pattern = rf"(?<![a-z0-9_]){re.escape(code)}(?![a-z0-9_])"
        text = re.sub(pattern, label, text, flags=re.IGNORECASE)
    return text


def _parse_payload(payload: Any, provider: str) -> Assessment:
    if not isinstance(payload, dict):
        raise AIProviderError("respuesta no es un objeto JSON", provider=provider)

    level = str(payload.get("nivel_riesgo", "")).strip().upper()
    if level not in RISK_LEVELS:
        raise AIProviderError("nivel_riesgo inválido", provider=provider)

    category = str(payload.get("categoria", "")).strip()
    if level == "BAJO":
        category = "Sin_Riesgo"
    elif category not in AI_CATEGORIES or category == "Sin_Riesgo":
        raise AIProviderError("categoria inválida", provider=provider)

    narrative = _clean_text(payload.get("descripcion_narrativa"), _DEFAULT_NARRATIVE[level])
    advice = _clean_text(payload.get("consejo_educativo"), _DEFAULT_ADVICE[level])
    return Assessment(
        nivel_riesgo=level,
        categoria=category,
        descripcion_narrativa=narrative,
        consejo_educativo=advice,
        provider=provider,
    )


def _local_start(vector: DerivedFeatureVector) -> datetime:
    return vector.time_window.start + timedelta(minutes=vector.time_window.tz_offset_minutes)


def _is_nocturnal(vector: DerivedFeatureVector) -> bool:
    return 0 <= _local_start(vector).hour < 6


def _high_volume(vector: DerivedFeatureVector) -> bool:
    return (
        vector.notification_count >= _VOLUME_THRESHOLD
        and vector.category in {"social", "mensajeria"}
    )


def _advice_for(flags: list[str], level: str) -> str:
    from .risk import FLAG_RULES

    for flag in _ordered_flags(flags):
        rule = FLAG_RULES.get(flag)
        if rule is not None:
            return rule[3]
    return _DEFAULT_ADVICE[level]


def apply_policy_floor(assessment: Assessment, vector: DerivedFeatureVector) -> Assessment:
    """Red de seguridad: la IA no puede minimizar señales ya detectadas.

    El modelo puede devolver BAJO ante una señal real (por prudencia excesiva o
    porque el proveedor degradó). Esta regla determinista garantiza el nivel
    mínimo que corresponde al vector, corrige la categoría para que coincida con
    la señal principal y reescribe el texto si contradecía la decisión.
    """

    order = {"BAJO": 0, "MEDIO": 1, "ALTO": 2}
    flags = list(dict.fromkeys(vector.flags))
    ordered = _ordered_flags(flags)
    primary = ordered[0] if ordered else None
    serious = [flag for flag in ordered if flag in _SERIOUS_FLAGS]

    if serious or len(flags) >= 2:
        minimum = "ALTO"
    elif flags:
        minimum = "MEDIO"
    elif _night_and_busy(vector) or _high_volume(vector):
        minimum = "MEDIO"
    else:
        minimum = "BAJO"

    level = assessment.nivel_riesgo
    final = level if order[level] >= order[minimum] else minimum

    category = assessment.categoria
    if final != "BAJO":
        if primary:
            category = _category_for_flag(primary)
        elif category == "Sin_Riesgo":
            category = "Conducta_Riesgo"

    category_changed = category != assessment.categoria

    if final == level and not category_changed:
        narrative = assessment.descripcion_narrativa
        advice = assessment.consejo_educativo
        if primary:
            primary_label = _FLAG_LABELS.get(primary, "")
            if primary_label and primary_label.lower() not in narrative.lower():
                narrative = f"{primary_label}. {narrative}"
        return Assessment(
            nivel_riesgo=final,
            categoria=category,
            descripcion_narrativa=narrative[:280],
            consejo_educativo=advice[:280],
            provider=assessment.provider,
        )

    labels = _signal_labels(flags)[:5]
    narrative = (
        f"Se detectaron señales de {', '.join(labels)}. "
        "Nivel ajustado según las señales derivadas de la ventana."
        if labels
        else _DEFAULT_NARRATIVE[final]
    )
    advice = _advice_for(ordered, final)
    return Assessment(
        nivel_riesgo=final,
        categoria=category,
        descripcion_narrativa=narrative[:280],
        consejo_educativo=advice[:280],
        provider=assessment.provider,
    )


def _night_and_busy(vector: DerivedFeatureVector) -> bool:
    return _is_nocturnal(vector) and vector.notification_count >= _NIGHT_VOLUME_THRESHOLD


def _extract_json(content: Any) -> Any:
    if isinstance(content, list):
        # Anthropic devuelve bloques; concatena solo los de texto.
        content = "".join(block.get("text", "") for block in content if isinstance(block, dict))
    if not isinstance(content, str):
        raise AIProviderError("contenido de respuesta inválido")
    text = content.strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        raise AIProviderError("respuesta sin JSON")
    try:
        return json.loads(text[start : end + 1])
    except json.JSONDecodeError as exc:
        raise AIProviderError("JSON malformado") from exc


def _flag_detail(flag: str) -> dict[str, Any]:
    rule = FLAG_RULES.get(flag)
    return {
        "codigo": flag,
        "etiqueta": _FLAG_LABELS.get(flag, rule[0] if rule else "Señal local"),
        "severidad": rule[1].value if rule else "MEDIA",
        "score": rule[2] if rule else 50,
    }


def vector_payload(vector: DerivedFeatureVector) -> dict[str, Any]:
    """Vista mínima y sin texto del vector, apta para un proveedor externo."""

    local_start = vector.time_window.start + timedelta(minutes=vector.time_window.tz_offset_minutes)
    window_minutes = max(
        1,
        int((vector.time_window.end - vector.time_window.start).total_seconds() // 60),
    )
    flags = _ordered_flags(list(vector.flags))
    primary = flags[0] if flags else None
    return {
        "categoria_uso": vector.category,
        "flags": flags,
        "flags_descripcion": [_FLAG_LABELS.get(flag, "Señal local") for flag in flags],
        "flags_detalle": [_flag_detail(flag) for flag in flags],
        "flags_criticos": [
            _FLAG_LABELS.get(flag, flag) for flag in flags if flag in _CRITICAL_FLAGS
        ],
        "flags_graves": [
            _FLAG_LABELS.get(flag, flag) for flag in flags if flag in _SERIOUS_FLAGS
        ],
        "senal_principal": _FLAG_LABELS.get(primary, "Señal local") if primary else None,
        "categoria_sugerida": _category_for_flag(primary) if primary else None,
        "cantidad_flags": len(flags),
        "lexical_flag_count": vector.lexical_flag_count,
        "contacto_desconocido_presente": bool(vector.hashed_contact_id),
        "usage_duration_minutes": vector.usage_duration_minutes,
        "notification_count": vector.notification_count,
        "notificaciones_por_minuto": round(vector.notification_count / window_minutes, 2),
        "event_count": vector.event_count,
        "ventana_minutos": window_minutes,
        "hora_local": local_start.strftime("%H:%M"),
        "dia_semana_local": local_start.strftime("%A"),
        "fin_de_semana": local_start.weekday() >= 5,
        "uso_sin_notificaciones": (
            vector.category in {"social", "mensajeria"}
            and vector.usage_duration_minutes >= 15
            and vector.notification_count == 0
        ),
    }


class OpenAIProvider:
    """Cliente para ``POST /chat/completions`` (OpenAI y compatibles)."""

    name = "openai"

    def __init__(
        self,
        api_key: str,
        model: str,
        base_url: str | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._api_key = api_key
        self._model = model
        self._base_url = (base_url or settings.openai_base_url).rstrip("/")
        self._transport = transport

    def _response_format(self) -> dict[str, Any]:
        """Formato JSON pedido al proveedor; las subclases pueden endurecerlo."""

        return {"type": "json_object"}

    def _max_tokens(self) -> int:
        return 320

    def _body(self, vector: DerivedFeatureVector, *, relaxed: bool) -> dict[str, Any]:
        response_format = (
            {"type": "json_object"} if relaxed else self._response_format()
        )
        return {
            "model": self._model,
            "temperature": 0,
            "max_tokens": self._max_tokens(),
            "response_format": response_format,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": json.dumps(vector_payload(vector), ensure_ascii=False)},
            ],
        }

    def _rejects_schema(self, response: httpx.Response) -> bool:
        """True solo si el 400 es por ``response_format`` y conviene relajarlo."""

        return False

    async def _post(
        self,
        vector: DerivedFeatureVector,
        *,
        timeout_seconds: float,
        relaxed: bool,
    ) -> httpx.Response:
        async with httpx.AsyncClient(
            timeout=timeout_seconds, transport=self._transport
        ) as client:
            return await client.post(
                f"{self._base_url}/chat/completions",
                json=self._body(vector, relaxed=relaxed),
                headers={"Authorization": f"Bearer {self._api_key}"},
            )

    async def classify(self, vector: DerivedFeatureVector, *, timeout_seconds: float) -> Assessment:
        response: httpx.Response | None = None
        # Solo se reintenta cuando el proveedor rechaza el esquema estricto:
        # se relaja a json_object una vez antes de degradar a heurística.
        for attempt in range(2):
            try:
                response = await self._post(
                    vector, timeout_seconds=timeout_seconds, relaxed=attempt > 0
                )
            except httpx.HTTPError as exc:
                raise AIProviderError(
                    f"{self.name} transporte: {type(exc).__name__}", provider=self.name
                ) from exc
            if attempt == 0 and response.status_code == 400 and self._rejects_schema(response):
                continue
            break

        if response is None:
            raise AIProviderError(f"{self.name} sin respuesta", provider=self.name)
        if response.status_code >= 400:
            raise AIProviderError(
                f"{self.name} status {response.status_code}{_error_detail(response)}",
                provider=self.name,
            )
        try:
            data = response.json()
            choice = data["choices"][0]
            content = choice["message"]["content"]
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise AIProviderError(
                f"{self.name} respuesta inesperada", provider=self.name
            ) from exc

        finish = str(choice.get("finish_reason") or "").lower()
        if finish == "length":
            raise AIProviderError(f"{self.name} finish=length", provider=self.name)
        if not isinstance(content, str) or not content.strip():
            raise AIProviderError(f"{self.name} respuesta vacía", provider=self.name)
        return _parse_payload(_extract_json(content), self.name)


class GroqProvider(OpenAIProvider):
    """Groq sobre ``/chat/completions`` con JSON schema estricto.

    ``gpt-oss`` emite tokens de razonamiento antes del JSON, así que el límite
    de salida es más alto que en OpenAI. ``reasoning_effort=low`` mantiene la
    latencia baja; si el modelo rechaza el esquema se reintenta una vez con
    ``json_object`` en lugar de perder la evaluación.
    """

    name = "groq"

    def __init__(
        self,
        api_key: str,
        model: str,
        base_url: str | None = None,
        reasoning_effort: str | None = "low",
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        super().__init__(
            api_key,
            model,
            base_url or settings.groq_base_url,
            transport=transport,
        )
        self._reasoning_effort = reasoning_effort

    def _response_format(self) -> dict[str, Any]:
        return {"type": "json_schema", "json_schema": JSON_SCHEMA_RESPONSE}

    def _max_tokens(self) -> int:
        return 1200

    def _body(self, vector: DerivedFeatureVector, *, relaxed: bool) -> dict[str, Any]:
        body = super()._body(vector, relaxed=relaxed)
        if self._reasoning_effort:
            body["reasoning_effort"] = self._reasoning_effort
        return body

    def _rejects_schema(self, response: httpx.Response) -> bool:
        detail = response.text[:500].lower()
        return any(token in detail for token in ("response_format", "json_schema", "schema"))


class GeminiProvider:
    """API nativa de Google Gemini con JSON schema y razonamiento opcional.

    Se usa la API nativa (no la capa OpenAI-compatible) porque permite fijar
    ``responseSchema`` y apagar el razonamiento, lo que mantiene la latencia
    dentro del presupuesto. La clave viaja en ``x-goog-api-key`` y nunca en la
    URL, para que no quede registrada en proxies ni logs.
    """

    name = "gemini"

    def __init__(
        self,
        api_key: str,
        model: str,
        base_url: str | None = None,
        thinking_budget: int | None = None,
        thinking_level: str | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._api_key = api_key
        self._model = model.removeprefix("models/").strip()
        self._base_url = (base_url or settings.gemini_base_url).rstrip("/")
        self._thinking_budget = thinking_budget
        self._thinking_level = thinking_level
        self._transport = transport

    def _body(self, vector: DerivedFeatureVector, *, with_thinking: bool) -> dict[str, Any]:
        generation: dict[str, Any] = {
            "temperature": 0,
            "maxOutputTokens": 420,
            "responseMimeType": "application/json",
            "responseSchema": GEMINI_RESPONSE_SCHEMA,
        }
        if with_thinking and self._thinking_level:
            generation["thinkingConfig"] = {"thinkingLevel": self._thinking_level}
        elif with_thinking and self._thinking_budget is not None:
            generation["thinkingConfig"] = {"thinkingBudget": self._thinking_budget}
        return {
            "systemInstruction": {"parts": [{"text": SYSTEM_PROMPT}]},
            "contents": [
                {
                    "role": "user",
                    "parts": [{"text": json.dumps(vector_payload(vector), ensure_ascii=False)}],
                }
            ],
            "generationConfig": generation,
        }

    async def classify(self, vector: DerivedFeatureVector, *, timeout_seconds: float) -> Assessment:
        url = f"{self._base_url}/models/{self._model}:generateContent"
        headers = {"x-goog-api-key": self._api_key, "Content-Type": "application/json"}
        with_thinking = self._thinking_level is not None or self._thinking_budget is not None

        response: httpx.Response | None = None
        for attempt in range(2):
            try:
                async with httpx.AsyncClient(
                    timeout=timeout_seconds, transport=self._transport
                ) as client:
                    response = await client.post(
                        url, json=self._body(vector, with_thinking=with_thinking), headers=headers
                    )
            except httpx.HTTPError as exc:
                raise AIProviderError(
                    f"gemini transporte: {type(exc).__name__}", provider=self.name
                ) from exc

            # Algunos modelos no aceptan thinkingBudget=0. Se reintenta una sola
            # vez sin ese campo en lugar de perder la evaluación.
            if (
                attempt == 0
                and with_thinking
                and response.status_code == 400
                and "thinking" in response.text.lower()
            ):
                with_thinking = False
                continue
            break

        if response is None:
            raise AIProviderError("gemini sin respuesta", provider=self.name)
        if response.status_code >= 400:
            raise AIProviderError(
                f"gemini status {response.status_code}{_error_detail(response)}",
                provider=self.name,
            )

        try:
            data = response.json()
        except ValueError as exc:
            raise AIProviderError("gemini respuesta no JSON", provider=self.name) from exc

        candidates = data.get("candidates") or []
        if not candidates:
            block = (data.get("promptFeedback") or {}).get("blockReason") or "unknown"
            raise AIProviderError(f"gemini sin candidatos block={block}", provider=self.name)

        candidate = candidates[0] or {}
        finish = str(candidate.get("finishReason") or "").upper()
        if finish and finish != "STOP":
            raise AIProviderError(f"gemini finish={finish}", provider=self.name)

        parts = (candidate.get("content") or {}).get("parts") or []
        content = "".join(
            str(part.get("text") or "") for part in parts if isinstance(part, dict)
        )
        if not content.strip():
            raise AIProviderError("gemini respuesta vacía", provider=self.name)
        return _parse_payload(_extract_json(content), self.name)


class AnthropicProvider:
    name = "anthropic"

    def __init__(self, api_key: str, model: str, base_url: str | None = None) -> None:
        self._api_key = api_key
        self._model = model
        self._base_url = (base_url or settings.anthropic_base_url).rstrip("/")

    async def classify(self, vector: DerivedFeatureVector, *, timeout_seconds: float) -> Assessment:
        body = {
            "model": self._model,
            "max_tokens": 320,
            "temperature": 0,
            "system": SYSTEM_PROMPT,
            "messages": [
                {"role": "user", "content": json.dumps(vector_payload(vector), ensure_ascii=False)},
            ],
        }
        try:
            async with httpx.AsyncClient(timeout=timeout_seconds) as client:
                response = await client.post(
                    f"{self._base_url}/messages",
                    json=body,
                    headers={
                        "x-api-key": self._api_key,
                        "anthropic-version": "2023-06-01",
                    },
                )
        except httpx.HTTPError as exc:
            raise AIProviderError(f"anthropic transporte: {type(exc).__name__}", provider=self.name) from exc
        if response.status_code >= 400:
            raise AIProviderError(f"anthropic status {response.status_code}", provider=self.name)
        try:
            content = response.json()["content"]
        except (KeyError, TypeError, ValueError) as exc:
            raise AIProviderError("anthropic respuesta inesperada", provider=self.name) from exc
        return _parse_payload(_extract_json(content), self.name)


class HeuristicProvider:
    """Modo degradado determinista, explicable y sin red."""

    name = "heuristic"

    async def classify(self, vector: DerivedFeatureVector, *, timeout_seconds: float = 0) -> Assessment:
        flags = list(dict.fromkeys(vector.flags))
        ordered = _ordered_flags(flags)
        selected = ordered[0] if ordered else None
        flag_set = set(flags)
        level = "BAJO"
        if flag_set & _HIGH_FLAGS or len(flags) >= 2:
            level = "ALTO"
        elif flag_set & _MEDIUM_FLAGS or vector.usage_duration_minutes >= 180:
            level = "MEDIO"

        local_start = vector.time_window.start + timedelta(minutes=vector.time_window.tz_offset_minutes)
        if 0 <= local_start.hour < 6 and vector.usage_duration_minutes >= 20:
            if level == "BAJO":
                level = "MEDIO"
        if vector.usage_duration_minutes >= 300:
            level = "ALTO"

        if level == "BAJO":
            category = "Sin_Riesgo"
            narrative = _DEFAULT_NARRATIVE["BAJO"]
            advice = _DEFAULT_ADVICE["BAJO"]
        else:
            category = _category_for_flag(selected)
            labels = _signal_labels(flags)[:5]
            if len(labels) == 1:
                narrative = f"{labels[0]}. Evaluación local por reglas, sin acceso al contenido."
            elif labels:
                narrative = (
                    f"Se detectaron señales de {', '.join(labels)}. "
                    "Evaluación local por reglas, sin acceso al contenido."
                )
            else:
                narrative = _DEFAULT_NARRATIVE[level]
            advice = _advice_for(ordered, level)

        return Assessment(
            nivel_riesgo=level,
            categoria=category,
            descripcion_narrativa=narrative[:280],
            consejo_educativo=advice[:280],
            provider=self.name,
        )


class CircuitBreaker:
    """Corta un proveedor caído para no gastar el presupuesto de 1500 ms."""

    def __init__(self, failures: int, cooldown_seconds: float, clock=time.monotonic) -> None:
        self._threshold = max(1, failures)
        self._cooldown = cooldown_seconds
        self._clock = clock
        self._failures = 0
        self._opened_at: float | None = None

    @property
    def is_open(self) -> bool:
        return self._opened_at is not None

    def allow(self) -> bool:
        if self._opened_at is None:
            return True
        if self._clock() - self._opened_at >= self._cooldown:
            self._opened_at = None
            self._failures = 0
            return True
        return False

    def record_success(self) -> None:
        self._failures = 0
        self._opened_at = None

    def record_failure(self) -> None:
        self._failures += 1
        if self._failures >= self._threshold:
            self._opened_at = self._clock()

    def snapshot(self) -> dict[str, Any]:
        return {
            "open": self.is_open,
            "failures": self._failures,
            "cooldown_seconds": self._cooldown,
        }


class AssessmentCache:
    """LRU acotada por hash del vector; nunca guarda texto."""

    def __init__(self, *, max_entries: int = 512, ttl_seconds: float = 300, clock=time.monotonic) -> None:
        self._max = max(1, max_entries)
        self._ttl = ttl_seconds
        self._clock = clock
        self._items: OrderedDict[str, tuple[float, Assessment]] = OrderedDict()

    def get(self, key: str) -> Assessment | None:
        hit = self._items.get(key)
        if hit is None:
            return None
        stored_at, assessment = hit
        if self._clock() - stored_at > self._ttl:
            self._items.pop(key, None)
            return None
        self._items.move_to_end(key)
        return assessment

    def set(self, key: str, assessment: Assessment) -> None:
        self._items[key] = (self._clock(), assessment)
        self._items.move_to_end(key)
        while len(self._items) > self._max:
            self._items.popitem(last=False)

    def clear(self) -> None:
        self._items.clear()

    def __len__(self) -> int:
        return len(self._items)


def cache_key(vector: DerivedFeatureVector) -> str:
    payload = vector_payload(vector)
    payload.pop("event_count", None)
    payload["flags"] = sorted(vector.flags)
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(canonical.encode()).hexdigest()


def _provider_from_settings(name: str) -> AIProvider | None:
    if name == "groq" and settings.groq_api_key:
        return GroqProvider(
            settings.groq_api_key,
            settings.groq_model,
            reasoning_effort=settings.groq_reasoning_effort,
        )
    if name == "gemini" and settings.gemini_api_key:
        return GeminiProvider(
            settings.gemini_api_key,
            settings.gemini_model,
            thinking_budget=settings.gemini_thinking_budget,
            thinking_level=settings.gemini_thinking_level,
        )
    if name == "openai" and settings.openai_api_key:
        return OpenAIProvider(settings.openai_api_key, settings.openai_model)
    if name == "anthropic" and settings.anthropic_api_key:
        return AnthropicProvider(settings.anthropic_api_key, settings.anthropic_model)
    return None


def select_providers() -> tuple[AIProvider | None, AIProvider | None]:
    """Resuelve primario y fallback según ``AI_PROVIDER`` y las claves presentes.

    ``auto`` respeta ``PROVIDER_ORDER``. Un valor explícito pasa al frente y los
    demás proveedores configurados quedan como respaldo. ``heuristic`` apaga la
    nube sin desactivar el motor.
    """

    choice = (settings.ai_provider or "auto").strip().lower()
    if choice == "heuristic":
        return None, None

    order = list(PROVIDER_ORDER)
    if choice in order:
        order.remove(choice)
        order.insert(0, choice)

    built = [
        provider
        for name in order
        if (provider := _provider_from_settings(name)) is not None
    ]
    if not built:
        return None, None
    return built[0], (built[1] if len(built) > 1 else None)


class AIEvaluatorService:
    def __init__(
        self,
        *,
        primary: AIProvider | None = None,
        fallback: AIProvider | None = None,
        heuristic: AIProvider | None = None,
        primary_timeout_ms: int | None = None,
        fallback_timeout_ms: int | None = None,
        total_timeout_ms: int | None = None,
        cache: AssessmentCache | None = None,
        breaker_failures: int | None = None,
        breaker_cooldown_seconds: float | None = None,
        clock=time.monotonic,
    ) -> None:
        # Solo se autoconfigura cuando no se inyectó ningún proveedor: así los
        # tests con dobles nunca tocan la red ni la clave real del .env.
        if primary is None and fallback is None:
            primary, fallback = select_providers()

        self._primary = primary
        self._fallback = fallback
        self._heuristic = heuristic or HeuristicProvider()
        self._primary_timeout = (primary_timeout_ms or settings.ai_primary_timeout_ms) / 1000
        self._fallback_timeout = (fallback_timeout_ms or settings.ai_fallback_timeout_ms) / 1000
        self._total_timeout = (total_timeout_ms or settings.ai_total_timeout_ms) / 1000
        self._cache = cache or AssessmentCache(
            max_entries=settings.ai_cache_max_entries,
            ttl_seconds=settings.ai_cache_ttl_seconds,
        )
        self._breaker_failures = breaker_failures or settings.ai_breaker_failures
        self._breaker_cooldown = (
            breaker_cooldown_seconds
            if breaker_cooldown_seconds is not None
            else settings.ai_breaker_cooldown_seconds
        )
        self._breakers: dict[str, CircuitBreaker] = {}
        self._clock = clock

    def _breaker_for(self, name: str) -> CircuitBreaker:
        breaker = self._breakers.get(name)
        if breaker is None:
            breaker = CircuitBreaker(
                self._breaker_failures,
                self._breaker_cooldown,
                self._clock,
            )
            self._breakers[name] = breaker
        return breaker

    @property
    def cloud_enabled(self) -> bool:
        return settings.ai_enabled and (self._primary is not None or self._fallback is not None)

    def status(self) -> dict[str, Any]:
        return {
            "enabled": settings.ai_enabled,
            "provider_setting": settings.ai_provider,
            "cloud_configured": self._primary is not None or self._fallback is not None,
            "primary": getattr(self._primary, "name", None),
            "fallback": getattr(self._fallback, "name", None),
            "cache_entries": len(self._cache),
            "breakers": {name: breaker.snapshot() for name, breaker in self._breakers.items()},
        }

    async def evaluate(self, vector: DerivedFeatureVector) -> Assessment:
        started = self._clock()

        if not settings.ai_enabled:
            local = apply_policy_floor(await self._heuristic.classify(vector), vector)
            return replace(local, latency_ms=self._elapsed_ms(started))

        key = cache_key(vector)
        cached = self._cache.get(key)
        if cached is not None:
            return replace(cached, cached=True, latency_ms=self._elapsed_ms(started))

        candidates: list[tuple[AIProvider, float]] = []
        if self._primary is not None:
            candidates.append((self._primary, self._primary_timeout))
        if self._fallback is not None:
            candidates.append((self._fallback, self._fallback_timeout))

        for provider, timeout_seconds in candidates:
            remaining = self._total_timeout - (self._clock() - started)
            if remaining <= 0.01:
                break
            breaker = self._breaker_for(provider.name)
            if not breaker.allow():
                logger.warning("ai_provider_skipped provider=%s reason=circuit_open", provider.name)
                continue
            budget = min(timeout_seconds, remaining)
            try:
                assessment = await asyncio.wait_for(
                    provider.classify(vector, timeout_seconds=budget),
                    timeout=budget + 0.05,
                )
                assessment = _parse_payload(
                    {
                        "nivel_riesgo": assessment.nivel_riesgo,
                        "categoria": assessment.categoria,
                        "descripcion_narrativa": assessment.descripcion_narrativa,
                        "consejo_educativo": assessment.consejo_educativo,
                    },
                    provider.name,
                )
                assessment = apply_policy_floor(assessment, vector)
            except asyncio.TimeoutError:
                breaker.record_failure()
                logger.warning("ai_provider_timeout provider=%s budget_ms=%d", provider.name, int(budget * 1000))
                continue
            except Exception as exc:  # noqa: BLE001 - un proveedor roto nunca tumba la API
                breaker.record_failure()
                logger.warning("ai_provider_failed provider=%s error=%s", provider.name, exc)
                continue

            breaker.record_success()
            result = replace(assessment, latency_ms=self._elapsed_ms(started))
            self._cache.set(key, result)
            logger.info(
                "ai_eval provider=%s level=%s cached=false latency_ms=%d",
                result.provider,
                result.nivel_riesgo,
                result.latency_ms,
            )
            return result

        local = apply_policy_floor(await self._heuristic.classify(vector), vector)
        result = replace(local, latency_ms=self._elapsed_ms(started))
        logger.warning(
            "ai_eval provider=heuristic level=%s latency_ms=%d degraded=true",
            result.nivel_riesgo,
            result.latency_ms,
        )
        return result

    def _elapsed_ms(self, started: float) -> int:
        return max(0, int((self._clock() - started) * 1000))


ai_evaluator = AIEvaluatorService()
