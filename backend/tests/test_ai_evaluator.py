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
    apply_policy_floor,
    cache_key,
    vector_payload,
)


def make_vector(
    *,
    flags: list[str] | None = None,
    usage: int = 10,
    event_id: uuid.UUID | None = None,
    start: datetime | None = None,
    notifications: int = 0,
    category: str = "social",
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
        category=category,
        lexical_flag_count=len(flags or []),
        flags=flags or [],
        usage_duration_minutes=usage,
        notification_count=notifications,
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


def test_policy_floor_never_minimizes_a_signal():
    vector = make_vector(flags=["gambling"])
    floored = apply_policy_floor(assessment(level="BAJO", category="Sin_Riesgo"), vector)

    assert floored.nivel_riesgo == "MEDIO"
    assert floored.categoria != "Sin_Riesgo"
    assert "Apuestas" in floored.descripcion_narrativa


def test_policy_floor_raises_serious_signals_to_high():
    vector = make_vector(flags=["self_harm"])
    floored = apply_policy_floor(assessment(level="MEDIO"), vector)

    assert floored.nivel_riesgo == "ALTO"
    assert "autolesión" in floored.descripcion_narrativa.lower()


def test_policy_floor_keeps_high_levels_and_never_downgrades():
    vector = make_vector(flags=["malestar"])
    floored = apply_policy_floor(assessment(level="ALTO"), vector)
    assert floored.nivel_riesgo == "ALTO"

    benign = apply_policy_floor(assessment(level="BAJO", category="Sin_Riesgo"), make_vector())
    assert benign.nivel_riesgo == "BAJO"


def test_policy_floor_high_volume_is_medium():
    vector = make_vector(notifications=30)
    floored = apply_policy_floor(assessment(level="BAJO", category="Sin_Riesgo"), vector)

    assert floored.nivel_riesgo == "MEDIO"


def test_heuristic_knows_emotional_distress():
    result = asyncio.run(HeuristicProvider().classify(make_vector(flags=["malestar"])))
    assert result.nivel_riesgo == "MEDIO"


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
        "flags_descripcion",
        "flags_criticos",
        "flags_graves",
        "cantidad_flags",
        "lexical_flag_count",
        "contacto_desconocido_presente",
        "usage_duration_minutes",
        "notification_count",
        "notificaciones_por_minuto",
        "event_count",
        "ventana_minutos",
        "hora_local",
        "dia_semana_local",
        "fin_de_semana",
        "uso_sin_notificaciones",
    }
    assert not any("snippet" in key or "text" in key or "raw" in key for key in payload)
    assert payload["flags_descripcion"] == ["Acoso escolar"]


def test_vector_payload_separates_critical_and_serious_flags():
    payload = vector_payload(make_vector(flags=["sextortion", "gambling"]))

    assert payload["flags_criticos"] == ["sextortion"]
    assert payload["flags_graves"] == ["sextortion"]
    assert payload["cantidad_flags"] == 2


def test_new_critical_flags_are_high_risk():
    for flag in ("sextortion", "abuse", "trafficking", "radicalization"):
        result = asyncio.run(HeuristicProvider().classify(make_vector(flags=[flag])))
        assert result.nivel_riesgo == "ALTO", flag
        assert result.categoria != "Sin_Riesgo", flag


def test_new_serious_flags_are_high_risk():
    for flag in (
        "weapons",
        "hate_speech",
        "sexual_harassment",
        "threat",
        "stalking",
        "eating_disorder",
        "doxxing",
        "runaway",
        "dangerous_challenge",
    ):
        result = asyncio.run(HeuristicProvider().classify(make_vector(flags=[flag])))
        assert result.nivel_riesgo == "ALTO", flag


def test_scam_flag_is_medium_risk():
    result = asyncio.run(HeuristicProvider().classify(make_vector(flags=["scam"])))
    assert result.nivel_riesgo == "MEDIO"


def test_insult_severe_is_high_risk_and_others_are_medium():
    severe = asyncio.run(HeuristicProvider().classify(make_vector(flags=["insult_severe"])))
    moderate = asyncio.run(HeuristicProvider().classify(make_vector(flags=["insult_moderate"])))
    mild = asyncio.run(HeuristicProvider().classify(make_vector(flags=["insult_mild"])))

    assert severe.nivel_riesgo == "ALTO"
    assert moderate.nivel_riesgo == "MEDIO"
    assert mild.nivel_riesgo == "MEDIO"
    assert severe.categoria == "Ciberbullying"


def test_scam_families_are_medium_risk():
    for flag in (
        "scam_points",
        "scam_casino",
        "scam_financial",
        "scam_account",
        "scam_link",
        "scam_prize",
        "scam_delivery",
        "scam_job",
        "scam_romance",
    ):
        result = asyncio.run(HeuristicProvider().classify(make_vector(flags=[flag])))
        assert result.nivel_riesgo == "MEDIO", flag


def test_two_scam_signals_raise_the_floor_to_high():
    vector = make_vector(flags=["scam_points", "scam_casino"])
    floored = apply_policy_floor(assessment(level="BAJO", category="Sin_Riesgo"), vector)

    assert floored.nivel_riesgo == "ALTO"


def test_scam_link_is_medium_risk_and_conducta():
    result = asyncio.run(HeuristicProvider().classify(make_vector(flags=["scam_link"])))

    assert result.nivel_riesgo == "MEDIO"
    assert result.categoria == "Conducta_Riesgo"


def test_two_insult_levels_raise_the_floor_to_high():
    vector = make_vector(flags=["insult_moderate", "insult_mild"])
    floored = apply_policy_floor(assessment(level="BAJO", category="Sin_Riesgo"), vector)

    assert floored.nivel_riesgo == "ALTO"


def test_two_medium_flags_raise_the_floor_to_high():
    vector = make_vector(flags=["gambling", "malestar"])
    floored = apply_policy_floor(assessment(level="BAJO", category="Sin_Riesgo"), vector)

    assert floored.nivel_riesgo == "ALTO"
    assert floored.categoria != "Sin_Riesgo"


def test_critical_flags_are_reported_as_critical_severity():
    from app.services.alerts import severity_for

    for flag in ("self_harm", "grooming", "sextortion", "abuse", "trafficking", "radicalization"):
        vector = make_vector(flags=[flag])
        result = assessment(level="ALTO", provider="fake")
        assert severity_for(result, vector).value == "CRITICA", flag


def test_every_known_flag_has_a_server_rule():
    from app.services.risk import FLAG_RULES

    expected = {
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
    }
    assert expected <= set(FLAG_RULES)
    for flag in expected:
        label, severity, score, advice = FLAG_RULES[flag]
        assert label and advice
        assert 0 <= score <= 100
        assert severity.value in {"BAJA", "MEDIA", "ALTA", "CRITICA"}


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
