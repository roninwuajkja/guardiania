import asyncio
import json
import uuid
from datetime import datetime, timedelta, timezone

import httpx
import pytest

from app.config import Settings, settings
from app.schemas import DerivedFeatureVector
from app.services.ai_evaluator import (
    AIEvaluatorService,
    AIProviderError,
    GroqProvider,
    JSON_SCHEMA_RESPONSE,
    vector_payload,
)


def make_vector(*, flags: list[str] | None = None) -> DerivedFeatureVector:
    start = datetime.now(timezone.utc) - timedelta(minutes=15)
    return DerivedFeatureVector(
        event_id=uuid.uuid4(),
        device_id=uuid.uuid4(),
        time_window={
            "start": start,
            "end": start + timedelta(minutes=15),
            "tz_offset_minutes": -180,
        },
        category="social",
        lexical_flag_count=len(flags or []),
        flags=flags or [],
        usage_duration_minutes=65,
        notification_count=14,
        event_count=9,
    )


def groq_payload() -> dict:
    return {
        "nivel_riesgo": "MEDIO",
        "categoria": "Conducta_Riesgo",
        "descripcion_narrativa": "Señal de apuestas detectada en la ventana analizada.",
        "consejo_educativo": "Conversa con calma y acuerden límites claros.",
    }


def groq_response(payload: dict | None = None, finish: str = "stop") -> dict:
    return {
        "choices": [
            {
                "message": {
                    "role": "assistant",
                    "content": json.dumps(payload or groq_payload(), ensure_ascii=False),
                },
                "finish_reason": finish,
            }
        ],
        "usage": {"prompt_tokens": 180, "completion_tokens": 90},
    }


def provider_with(handler, **kwargs) -> GroqProvider:
    return GroqProvider(
        "test-key",
        "openai/gpt-oss-20b",
        base_url="https://api.groq.test/openai/v1",
        reasoning_effort=kwargs.pop("reasoning_effort", "low"),
        transport=httpx.MockTransport(handler),
        **kwargs,
    )


def test_groq_sends_vector_only_with_strict_schema_and_reasoning():
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        seen["url"] = str(request.url)
        seen["auth"] = request.headers.get("authorization")
        seen["body"] = body
        return httpx.Response(200, json=groq_response())

    result = asyncio.run(
        provider_with(handler).classify(make_vector(flags=["gambling"]), timeout_seconds=2)
    )

    assert result.provider == "groq"
    assert result.nivel_riesgo == "MEDIO"
    assert result.categoria == "Conducta_Riesgo"
    assert "apuestas" in result.descripcion_narrativa

    assert str(seen["url"]) == "https://api.groq.test/openai/v1/chat/completions"
    assert seen["auth"] == "Bearer test-key"

    body = seen["body"]
    assert body["model"] == "openai/gpt-oss-20b"
    assert body["temperature"] == 0
    assert body["reasoning_effort"] == "low"
    assert body["response_format"] == {
        "type": "json_schema",
        "json_schema": JSON_SCHEMA_RESPONSE,
    }
    assert JSON_SCHEMA_RESPONSE["strict"] is True
    assert JSON_SCHEMA_RESPONSE["schema"]["additionalProperties"] is False

    sent = json.loads(body["messages"][1]["content"])
    assert set(sent) == set(vector_payload(make_vector()))
    assert sent["flags"] == ["gambling"]
    # La Regla Cero: el mensaje del sistema no transporta datos del equipo.
    assert body["messages"][0]["role"] == "system"
    assert "gambling" not in body["messages"][0]["content"]


def test_groq_relaxes_schema_once_when_model_rejects_it():
    calls: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        if body["response_format"]["type"] == "json_schema":
            return httpx.Response(
                400,
                json={"error": {"message": "json_schema is not supported for this model"}},
            )
        return httpx.Response(200, json=groq_response())

    result = asyncio.run(
        provider_with(handler).classify(make_vector(flags=["gambling"]), timeout_seconds=2)
    )

    assert result.provider == "groq"
    assert len(calls) == 2
    assert calls[0]["response_format"]["type"] == "json_schema"
    assert calls[1]["response_format"] == {"type": "json_object"}
    # El reintento conserva el resto del contrato.
    assert calls[1]["reasoning_effort"] == "low"
    assert calls[1]["messages"] == calls[0]["messages"]


def test_groq_does_not_retry_on_unrelated_400():
    calls: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(json.loads(request.content))
        return httpx.Response(400, json={"error": {"message": "invalid api key"}})

    with pytest.raises(AIProviderError) as excinfo:
        asyncio.run(
            provider_with(handler).classify(make_vector(flags=["gambling"]), timeout_seconds=2)
        )

    assert len(calls) == 1
    assert "status 400" in str(excinfo.value)
    assert excinfo.value.provider == "groq"


def test_groq_exhausted_schema_retry_degrades_to_heuristic():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            400, json={"error": {"message": "response_format json_schema rejected"}}
        )

    service = AIEvaluatorService(
        primary=provider_with(handler),
        primary_timeout_ms=500,
        total_timeout_ms=1000,
    )
    result = asyncio.run(service.evaluate(make_vector(flags=["gambling"])))

    assert result.provider == "heuristic"
    assert result.nivel_riesgo == "MEDIO"


def test_groq_rate_limit_degrades_to_heuristic():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            429, json={"error": {"message": "Rate limit reached for gpt-oss-20b"}}
        )

    service = AIEvaluatorService(
        primary=provider_with(handler),
        primary_timeout_ms=500,
        total_timeout_ms=1000,
    )
    result = asyncio.run(service.evaluate(make_vector(flags=["gambling"])))

    assert result.provider == "heuristic"


def test_groq_empty_content_is_a_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": ""}, "finish_reason": "stop"}]},
        )

    with pytest.raises(AIProviderError) as excinfo:
        asyncio.run(
            provider_with(handler).classify(make_vector(flags=["gambling"]), timeout_seconds=2)
        )
    assert "vacía" in str(excinfo.value)


def test_groq_truncated_output_is_a_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=groq_response(finish="length"))

    with pytest.raises(AIProviderError) as excinfo:
        asyncio.run(
            provider_with(handler).classify(make_vector(flags=["gambling"]), timeout_seconds=2)
        )
    assert "finish=length" in str(excinfo.value)


def test_groq_invalid_json_is_a_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "no es json"}, "finish_reason": "stop"}]},
        )

    with pytest.raises(AIProviderError):
        asyncio.run(
            provider_with(handler).classify(make_vector(flags=["gambling"]), timeout_seconds=2)
        )


def test_auto_prefers_groq_then_gemini(monkeypatch):
    monkeypatch.setattr(settings, "ai_enabled", True)
    monkeypatch.setattr(settings, "ai_provider", "auto")
    monkeypatch.setattr(settings, "groq_api_key", "groq-key")
    monkeypatch.setattr(settings, "gemini_api_key", "gemini-key")
    monkeypatch.setattr(settings, "openai_api_key", "openai-key")
    monkeypatch.setattr(settings, "anthropic_api_key", None)

    status = AIEvaluatorService().status()

    assert status["primary"] == "groq"
    assert status["fallback"] == "gemini"
    assert status["cloud_configured"] is True


def test_explicit_groq_goes_first_with_gemini_fallback(monkeypatch):
    monkeypatch.setattr(settings, "ai_enabled", True)
    monkeypatch.setattr(settings, "ai_provider", "groq")
    monkeypatch.setattr(settings, "groq_api_key", "groq-key")
    monkeypatch.setattr(settings, "gemini_api_key", "gemini-key")
    monkeypatch.setattr(settings, "openai_api_key", None)
    monkeypatch.setattr(settings, "anthropic_api_key", None)

    status = AIEvaluatorService().status()

    assert status["primary"] == "groq"
    assert status["fallback"] == "gemini"


def test_blank_groq_key_is_treated_as_absent():
    config = Settings(groq_api_key="   ")

    assert config.groq_api_key is None
    assert config.ai_configured is False


def test_groq_key_alone_enables_cloud():
    config = Settings(
        groq_api_key="gsk_test",
        gemini_api_key=None,
        openai_api_key=None,
        anthropic_api_key=None,
    )

    assert config.ai_configured is True


def test_groq_provider_and_reasoning_are_accepted_by_runtime_validation():
    Settings(ai_provider="groq", groq_reasoning_effort="LOW").validate_runtime()

    with pytest.raises(RuntimeError):
        Settings(groq_reasoning_effort="extreme").validate_runtime()
