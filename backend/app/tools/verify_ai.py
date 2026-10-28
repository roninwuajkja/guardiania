"""Verificación local del motor de IA en la nube.

Uso (desde ``backend/``):
    python -m app.tools.verify_ai
    python -m app.tools.verify_ai --provider groq --repeat 5
    python -m app.tools.verify_ai --provider groq --list-models
    python -m app.tools.verify_ai --provider gemini --timeout-ms 4000

Envía un vector sintético (nunca texto del dispositivo), mide latencia real y
confirma que el proveedor responde JSON válido dentro del presupuesto. Nunca
imprime la API key: solo su longitud y un hash corto.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone

import httpx

from ..config import settings
from ..schemas import DerivedFeatureVector
from ..services.ai_evaluator import (
    AIEvaluatorService,
    AnthropicProvider,
    GeminiProvider,
    GroqProvider,
    HeuristicProvider,
    OpenAIProvider,
    vector_payload,
)

_MODELS = {
    "groq": lambda: settings.groq_model,
    "gemini": lambda: settings.gemini_model,
    "openai": lambda: settings.openai_model,
    "anthropic": lambda: settings.anthropic_model,
}

_KEYS = {
    "groq": lambda: settings.groq_api_key,
    "gemini": lambda: settings.gemini_api_key,
    "openai": lambda: settings.openai_api_key,
    "anthropic": lambda: settings.anthropic_api_key,
}


def synthetic_vector(offset_minutes: int = 0) -> DerivedFeatureVector:
    start = datetime.now(timezone.utc) - timedelta(minutes=15 + offset_minutes)
    return DerivedFeatureVector(
        event_id=uuid.uuid4(),
        device_id=uuid.uuid4(),
        time_window={
            "start": start,
            "end": start + timedelta(minutes=15),
            "tz_offset_minutes": -180,
        },
        category="social",
        lexical_flag_count=1,
        flags=["gambling"],
        usage_duration_minutes=65,
        notification_count=14,
        event_count=9,
    )


def mask_key(key: str | None) -> str:
    if not key:
        return "AUSENTE"
    digest = hashlib.sha256(key.encode()).hexdigest()[:8]
    return f"presente len={len(key)} sha256={digest}"


def _provider_key(provider: str) -> str:
    key = _KEYS[provider]() if provider in _KEYS else None
    if not key:
        raise SystemExit(f"{provider.upper()}_API_KEY no está definida en backend/.env")
    return key


def _provider_model(provider: str) -> str:
    return _MODELS[provider]() if provider in _MODELS else "desconocido"


def _gemini_key() -> str:
    return _provider_key("gemini")


def list_models(provider: str, timeout_ms: int) -> int:
    if provider in {"groq", "openai"}:
        base = settings.groq_base_url if provider == "groq" else settings.openai_base_url
        try:
            response = httpx.get(
                f"{base.rstrip('/')}/models",
                headers={"Authorization": f"Bearer {_provider_key(provider)}"},
                timeout=timeout_ms / 1000,
            )
        except httpx.HTTPError as exc:
            print(f"[FALLA] transporte: {type(exc).__name__}")
            return 1
        if response.status_code >= 400:
            print(f"[FALLA] status {response.status_code}")
            return 1

        models = sorted(
            str(model.get("id")) for model in (response.json().get("data") or [])
        )
        print(f"Modelos disponibles en {provider}: {len(models)}")
        for name in models:
            marker = "  <- configurado" if name == _provider_model(provider) else ""
            print(f"  - {name}{marker}")
        return 0

    if provider != "gemini":
        print(f"--list-models no está implementado para {provider}")
        return 1

    url = f"{settings.gemini_base_url.rstrip('/')}/models"
    try:
        response = httpx.get(
            url,
            headers={"x-goog-api-key": _gemini_key()},
            params={"pageSize": 1000},
            timeout=timeout_ms / 1000,
        )
    except httpx.HTTPError as exc:
        print(f"[FALLA] transporte: {type(exc).__name__}")
        return 1
    if response.status_code >= 400:
        print(f"[FALLA] status {response.status_code}")
        return 1

    models = response.json().get("models") or []
    usable = [
        model
        for model in models
        if "generateContent" in (model.get("supportedGenerationMethods") or [])
    ]
    print(f"Modelos con generateContent: {len(usable)}")
    for model in sorted(usable, key=lambda item: str(item.get("name"))):
        name = str(model.get("name") or "").removeprefix("models/")
        display = model.get("displayName") or ""
        print(
            f"  - {name:<48} {display:<28} "
            f"in={model.get('inputTokenLimit', '?')} out={model.get('outputTokenLimit', '?')}"
        )
    return 0


def build_service(args: argparse.Namespace) -> AIEvaluatorService:
    provider = args.provider
    if provider == "auto":
        if not args.model:
            # auto: respeta AI_PROVIDER y las claves del .env.
            return AIEvaluatorService(
                primary_timeout_ms=args.timeout_ms,
                total_timeout_ms=args.timeout_ms + 100,
            )
        provider = (settings.ai_provider or "").strip().lower()
        if provider not in _MODELS:
            raise SystemExit("--model requiere --provider groq|gemini|openai|anthropic")

    if provider == "groq":
        primary = GroqProvider(
            _provider_key("groq"),
            args.model or settings.groq_model,
            reasoning_effort=settings.groq_reasoning_effort,
        )
    elif provider == "gemini":
        primary = GeminiProvider(
            _gemini_key(),
            args.model or settings.gemini_model,
            thinking_budget=settings.gemini_thinking_budget,
            thinking_level=settings.gemini_thinking_level,
        )
    elif provider == "openai":
        primary = OpenAIProvider(
            _provider_key("openai"), args.model or settings.openai_model
        )
    elif provider == "anthropic":
        primary = AnthropicProvider(
            _provider_key("anthropic"), args.model or settings.anthropic_model
        )
    elif provider == "heuristic":
        primary = HeuristicProvider()
    else:
        raise SystemExit(f"proveedor desconocido: {provider}")

    return AIEvaluatorService(
        primary=primary,
        primary_timeout_ms=args.timeout_ms,
        total_timeout_ms=args.timeout_ms + 100,
    )


def _percentile(values: list[int], fraction: float) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round(fraction * (len(ordered) - 1))))
    return ordered[index]


async def run(args: argparse.Namespace) -> int:
    service = build_service(args)
    status = service.status()
    primary = status["primary"] or "heuristic"
    model = args.model or _provider_model(primary)
    print("IA Guardian - verificación del motor de IA")
    print(f"AI_ENABLED={settings.ai_enabled} AI_PROVIDER={settings.ai_provider}")
    print(f"primario={status['primary']} fallback={status['fallback']}")
    print(f"modelo={model}")
    print(f"clave {primary}: {mask_key(_KEYS[primary]() if primary in _KEYS else None)}")
    print(f"presupuesto: {args.timeout_ms} ms")
    print("-" * 64)

    payload = vector_payload(synthetic_vector())
    print(f"vector sintético: flags={payload['flags']} usage={payload['usage_duration_minutes']} min")
    print(f"campos enviados: {sorted(payload)}")
    print("-" * 64)

    latencies: list[int] = []
    failures = 0
    for attempt in range(1, args.repeat + 1):
        vector = synthetic_vector(offset_minutes=attempt - 1)
        started = time.perf_counter()
        assessment = await service.evaluate(vector)
        wall_ms = int((time.perf_counter() - started) * 1000)
        latencies.append(assessment.latency_ms)
        verdict = "OK" if assessment.provider != "heuristic" else "DEGRADADO"
        if assessment.provider == "heuristic":
            failures += 1
        print(
            f"[{attempt}/{args.repeat}] {verdict} provider={assessment.provider} "
            f"cached={assessment.cached} nivel={assessment.nivel_riesgo} "
            f"categoria={assessment.categoria} latency={assessment.latency_ms}ms wall={wall_ms}ms"
        )
        print(f"    narrativa: {assessment.descripcion_narrativa}")
        print(f"    consejo:   {assessment.consejo_educativo}")

    print("-" * 64)
    print(
        f"latencia ms: p50={_percentile(latencies, 0.50)} "
        f"p95={_percentile(latencies, 0.95)} max={max(latencies, default=0)}"
    )
    print(f"degradados a heurística: {failures}/{args.repeat}")
    if failures:
        print("Resultado: NO verificada. Revisa clave, cuota, modelo y red.")
        return 1
    print("Resultado: OK. El proveedor respondió JSON válido dentro del presupuesto.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verifica el proveedor de IA de IA Guardian")
    parser.add_argument(
        "--provider",
        choices=["auto", "groq", "gemini", "openai", "anthropic", "heuristic"],
        default="auto",
        help="Proveedor a probar; auto respeta AI_PROVIDER del .env",
    )
    parser.add_argument("--model", help="Fuerza el modelo del proveedor elegido")
    parser.add_argument("--timeout-ms", type=int, default=settings.ai_primary_timeout_ms)
    parser.add_argument("--repeat", type=int, default=1, help="Repeticiones para medir P50/P95")
    parser.add_argument("--list-models", action="store_true", help="Lista modelos disponibles")
    args = parser.parse_args(argv)

    if args.repeat < 1 or args.repeat > 10:
        print("--repeat debe estar entre 1 y 10")
        return 2
    if args.list_models:
        provider = args.provider
        if provider == "auto":
            provider = (settings.ai_provider or "").strip().lower()
            if provider not in _MODELS:
                provider = next(
                    (name for name in _MODELS if _KEYS[name]()), "groq"
                )
        return list_models(provider, args.timeout_ms)
    return asyncio.run(run(args))


if __name__ == "__main__":
    sys.exit(main())
