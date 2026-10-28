import asyncio
import time
import uuid
from datetime import datetime, timedelta, timezone

from app.config import settings
from app.schemas import DerivedFeatureVector
from app.services.ai_evaluator import (
    AIEvaluatorService,
    AIProviderError,
    Assessment,
    AssessmentCache,
    CircuitBreaker,
    HeuristicProvider,
    cache_key,
    vector_payload,
)


def make_vector(
    *,
    flags: list[str] | None = None,
    usage: int = 10,
    event_id: uuid.UUID | None = None,
    start: datetime | None = None,
) -> DerivedFeatureVector:
    start = start or (datetime.now(timezone.utc) - timedelta(minutes=15))
    return DerivedFeatureVector(
        event_id=event_id or uuid.uuid4(),
        device_id=uuid.uuid4(),
        time_window={
            "start": start,
            "end": start + timedelta(minutes=15),
            "tz_offset_minutes": 0,
        },
        category="social",
        lexical_flag_count=len(flags or []),
        flags=flags or [],
        usage_duration_minutes=usage,
    )


def assessment(level="ALTO", category="Conducta_Riesgo", provider="fake") -> Assessment:
    return Assessment(
        nivel_riesgo=level,
        categoria=category,
        descripcion_narrativa="Descripción de prueba sin contenido real.",
        consejo_educativo="Conversa con calma y revisen juntos.",
        provider=provider,
    )


class FakeProvider:
    def __init__(self, name, *, result=None, delay=0.0, error=None):
        self.name = name
        self.result = result or assessment(provider=name)
        self.delay = delay
        self.error = error
        self.calls = 0

    async def classify(self, vector, *, timeout_seconds):
        self.calls += 1
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error:
            raise self.error
        return self.result


def test_primary_provider_wins_and_is_not_cached_twice():
    primary = FakeProvider("openai")
    fallback = FakeProvider("anthropic")
    service = AIEvaluatorService(primary=primary, fallback=fallback, total_timeout_ms=1500)
    start = datetime.now(timezone.utc) - timedelta(minutes=15)

    first = asyncio.run(service.evaluate(make_vector(flags=["self_harm"], start=start)))
    second = asyncio.run(service.evaluate(make_vector(flags=["self_harm"], start=start)))

    assert first.nivel_riesgo == "ALTO"
    assert first.provider == "openai"
    assert first.cached is False
    assert second.cached is True
    assert primary.calls == 1
    assert fallback.calls == 0


def test_timeout_degrades_to_fallback_within_budget():
    primary = FakeProvider("openai", delay=1.0)
    fallback = FakeProvider("anthropic")
    service = AIEvaluatorService(
        primary=primary,
        fallback=fallback,
        primary_timeout_ms=50,
        fallback_timeout_ms=400,
        total_timeout_ms=1000,
    )

    result = asyncio.run(service.evaluate(make_vector(flags=["bullying"])))
    assert result.provider == "anthropic"
    assert primary.calls == 1
    assert fallback.calls == 1


def test_both_providers_fail_uses_local_heuristic():
    primary = FakeProvider("openai", error=AIProviderError("boom", provider="openai"))
    fallback = FakeProvider("anthropic", error=AIProviderError("boom", provider="anthropic"))
    service = AIEvaluatorService(primary=primary, fallback=fallback, total_timeout_ms=1000)

    result = asyncio.run(service.evaluate(make_vector(flags=["self_harm"])))
    assert result.provider == "heuristic"
    assert result.nivel_riesgo == "ALTO"
    assert result.categoria == "Conducta_Riesgo"


def test_invalid_provider_payload_falls_back_to_heuristic():
    primary = FakeProvider("openai", result=assessment(level="ALTÍSIMO", provider="openai"))
    service = AIEvaluatorService(primary=primary, total_timeout_ms=1000)

    result = asyncio.run(service.evaluate(make_vector(flags=["unknown_contact"])))
    assert result.provider == "heuristic"
    assert result.nivel_riesgo == "MEDIO"


def test_heuristic_benign_vector_is_low_risk():
    result = asyncio.run(HeuristicProvider().classify(make_vector(usage=12)))
    assert result.nivel_riesgo == "BAJO"
    assert result.categoria == "Sin_Riesgo"


def test_heuristic_high_risk_categories():
    sexual = asyncio.run(HeuristicProvider().classify(make_vector(flags=["sexual_content"])))
    bullying = asyncio.run(HeuristicProvider().classify(make_vector(flags=["bullying"])))
    assert sexual.categoria == "Contenido_Inapropiado"
    assert bullying.categoria == "Ciberbullying"
    assert sexual.nivel_riesgo == bullying.nivel_riesgo == "ALTO"


def test_cache_key_ignores_event_id_but_not_flags():
    first = make_vector(flags=["bullying"])
    second = make_vector(flags=["bullying"])
    other = make_vector(flags=["drugs"])
    assert cache_key(first) == cache_key(second)
    assert cache_key(first) != cache_key(other)


def test_vector_payload_has_no_text_fields():
    payload = vector_payload(make_vector(flags=["bullying"]))
    assert set(payload) == {
        "categoria_uso",
        "flags",
        "lexical_flag_count",
        "contacto_desconocido_presente",
        "usage_duration_minutes",
        "notification_count",
        "event_count",
        "hora_local",
        "dia_semana_local",
    }
    assert not any("snippet" in key or "text" in key or "raw" in key for key in payload)


def test_circuit_breaker_skips_dead_provider_until_cooldown():
    now = [0.0]
    primary = FakeProvider("openai", error=AIProviderError("boom", provider="openai"))
    service = AIEvaluatorService(
        primary=primary,
        breaker_failures=2,
        breaker_cooldown_seconds=30,
        total_timeout_ms=1000,
        clock=lambda: now[0],
    )

    asyncio.run(service.evaluate(make_vector(flags=["violence"])))
    asyncio.run(service.evaluate(make_vector(flags=["violence"])))
    assert primary.calls == 2

    # Abierto: no se vuelve a llamar; la heurística responde.
    result = asyncio.run(service.evaluate(make_vector(flags=["violence"])))
    assert result.provider == "heuristic"
    assert primary.calls == 2

    now[0] = 31.0
    asyncio.run(service.evaluate(make_vector(flags=["violence"])))
    assert primary.calls == 3


def test_total_budget_caps_primary_timeout():
    primary = FakeProvider("openai", delay=1.0)
    service = AIEvaluatorService(
        primary=primary,
        primary_timeout_ms=5000,
        total_timeout_ms=100,
    )
    started = time.monotonic()
    result = asyncio.run(service.evaluate(make_vector(flags=["gambling"])))
    elapsed = time.monotonic() - started
    assert result.provider == "heuristic"
    assert elapsed < 0.8


def test_ai_disabled_never_calls_cloud(monkeypatch):
    monkeypatch.setattr(settings, "ai_enabled", False)
    primary = FakeProvider("openai")
    service = AIEvaluatorService(primary=primary, total_timeout_ms=1000)
    result = asyncio.run(service.evaluate(make_vector(flags=["self_harm"])))
    assert result.provider == "heuristic"
    assert primary.calls == 0


def test_cache_expires_and_respects_max_entries():
    now = [0.0]
    cache = AssessmentCache(max_entries=1, ttl_seconds=10, clock=lambda: now[0])
    key_one, key_two = "a", "b"
    cache.set(key_one, assessment())
    assert cache.get(key_one) is not None
    cache.set(key_two, assessment())
    assert cache.get(key_one) is None
    now[0] = 11.0
    assert cache.get(key_two) is None


def test_circuit_breaker_state_snapshot():
    breaker = CircuitBreaker(1, 10, clock=lambda: 5.0)
    assert breaker.allow() is True
    breaker.record_failure()
    assert breaker.is_open is True
    assert breaker.snapshot()["open"] is True
    breaker.record_success()
    assert breaker.allow() is True
