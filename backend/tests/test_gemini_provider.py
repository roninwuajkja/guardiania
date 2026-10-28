import asyncio
import json
import uuid
from datetime import datetime, timedelta, timezone

import httpx
import pytest

from app.config import settings
from app.schemas import DerivedFeatureVector
from app.services.ai_evaluator import (
    AIEvaluatorService,
    AIProviderError,
    GeminiProvider,
    GEMINI_RESPONSE_SCHEMA,
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


def gemini_payload() -> dict:
    return {
        "nivel_riesgo": "ALTO",
        "categoria": "Conducta_Riesgo",
        "descripcion_narrativa": "Señal de apuestas detectada en la ventana analizada.",
        "consejo_educativo": "Conversa con calma y acuerden límites claros.",
    }


def gemini_response(payload: dict | None = None, finish: str = "STOP") -> dict:
    text = json.dumps(payload or gemini_payload(), ensure_ascii=False)
    return {
        "candidates": [
            {
                "content": {"role": "model", "parts": [{"text": text}]},
                "finishReason": finish,
            }
        ],
        "usageMetadata": {"promptTokenCount": 180, "candidatesTokenCount": 90},
    }


def provider_with(handler) -> GeminiProvider:
    return GeminiProvider(
        "test-key",
        "gemini-flash-latest",
        base_url="https://example.test/v1beta",
        thinking_budget=0,
        transport=httpx.MockTransport(handler),
    )


def test_gemini_sends_vector_only_and_parses_json():
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        seen["url"] = str(request.url)
        seen["key_header"] = request.headers.get("x-goog-api-key")
        seen["body"] = body
        return httpx.Response(200, json=gemini_response())

    result = asyncio.run(
        provider_with(handler).classify(make_vector(flags=["gambling"]), timeout_seconds=2)
    )

    assert result.provider == "gemini"
    assert result.nivel_riesgo == "ALTO"
    assert result.categoria == "Conducta_Riesgo"
    assert "apuestas" in result.descripcion_narrativa

    url = str(seen["url"])
    assert url.endswith("/models/gemini-flash-latest:generateContent")
    assert "key=" not in url
    assert seen["key_header"] == "test-key"

    body = seen["body"]
    assert body["generationConfig"]["responseMimeType"] == "application/json"
    assert body["generationConfig"]["responseSchema"] == GEMINI_RESPONSE_SCHEMA
    assert body["generationConfig"]["thinkingConfig"] == {"thinkingBudget": 0}
    assert body["systemInstruction"]["parts"][0]["text"]

    user_text = body["contents"][0]["parts"][0]["text"]
    sent = json.loads(user_text)
    assert set(sent) == set(vector_payload(make_vector()))
    assert sent["flags"] == ["gambling"]


def test_gemini_truncated_output_is_a_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=gemini_response(finish="MAX_TOKENS"))

    with pytest.raises(AIProviderError) as excinfo:
        asyncio.run(provider_with(handler).classify(make_vector(flags=["gambling"]), timeout_seconds=2))
    assert "MAX_TOKENS" in str(excinfo.value)
    assert excinfo.value.provider == "gemini"


def test_gemini_blocked_prompt_is_a_provider_error_without_content():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "promptFeedback": {"blockReason": "SAFETY"},
                "candidates": [],
            },
        )

    with pytest.raises(AIProviderError) as excinfo:
        asyncio.run(provider_with(handler).classify(make_vector(flags=["gambling"]), timeout_seconds=2))
    assert "block=SAFETY" in str(excinfo.value)


def test_gemini_retries_without_thinking_when_model_rejects_it():
    calls: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        if "thinkingConfig" in body["generationConfig"]:
            return httpx.Response(
                400,
                json={"error": {"message": "thinkingBudget is not supported for this model"}},
            )
        return httpx.Response(200, json=gemini_response())

    result = asyncio.run(
        provider_with(handler).classify(make_vector(flags=["gambling"]), timeout_seconds=2)
    )

    assert result.provider == "gemini"
    assert len(calls) == 2
    assert "thinkingConfig" in calls[0]["generationConfig"]
    assert "thinkingConfig" not in calls[1]["generationConfig"]


def test_gemini_invalid_json_content_is_a_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "candidates": [
                    {
                        "content": {"parts": [{"text": "no es json"}]},
                        "finishReason": "STOP",
                    }
                ]
            },
        )

    with pytest.raises(AIProviderError):
        asyncio.run(provider_with(handler).classify(make_vector(flags=["gambling"]), timeout_seconds=2))


def test_gemini_uses_thinking_level_when_configured():
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["generationConfig"]["thinkingConfig"] == {"thinkingLevel": "minimal"}
        return httpx.Response(200, json=gemini_response())

    provider = GeminiProvider(
        "test-key",
        "gemini-3.5-flash-lite",
        base_url="https://example.test/v1beta",
        thinking_budget=0,
        thinking_level="minimal",
        transport=httpx.MockTransport(handler),
    )
    result = asyncio.run(provider.classify(make_vector(flags=["gambling"]), timeout_seconds=2))
    assert result.provider == "gemini"


def test_gemini_http_error_degrades_to_heuristic():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, json={"error": {"message": "boom"}})

    service = AIEvaluatorService(
        primary=provider_with(handler),
        primary_timeout_ms=500,
        total_timeout_ms=1000,
    )
    result = asyncio.run(service.evaluate(make_vector(flags=["gambling"])))

    assert result.provider == "heuristic"
    assert result.nivel_riesgo == "MEDIO"


def test_blank_provider_key_is_treated_as_absent():
    from app.config import Settings

    config = Settings(
        gemini_api_key=None,
        openai_api_key="",
        anthropic_api_key="   ",
    )

    assert config.gemini_api_key is None
    assert config.openai_api_key is None
    assert config.anthropic_api_key is None


def test_auto_selects_gemini_then_openai(monkeypatch):
    monkeypatch.setattr(settings, "ai_enabled", True)
    monkeypatch.setattr(settings, "ai_provider", "auto")
    monkeypatch.setattr(settings, "gemini_api_key", "gemini-key")
    monkeypatch.setattr(settings, "openai_api_key", "openai-key")
    monkeypatch.setattr(settings, "anthropic_api_key", None)

    status = AIEvaluatorService().status()

    assert status["primary"] == "gemini"
    assert status["fallback"] == "openai"
    assert status["cloud_configured"] is True


def test_explicit_provider_goes_first(monkeypatch):
    monkeypatch.setattr(settings, "ai_enabled", True)
    monkeypatch.setattr(settings, "ai_provider", "anthropic")
    monkeypatch.setattr(settings, "gemini_api_key", "gemini-key")
    monkeypatch.setattr(settings, "openai_api_key", None)
    monkeypatch.setattr(settings, "anthropic_api_key", "anthropic-key")

    status = AIEvaluatorService().status()

    assert status["primary"] == "anthropic"
    assert status["fallback"] == "gemini"


def test_heuristic_setting_disables_cloud_without_disabling_ai(monkeypatch):
    monkeypatch.setattr(settings, "ai_enabled", True)
    monkeypatch.setattr(settings, "ai_provider", "heuristic")
    monkeypatch.setattr(settings, "gemini_api_key", "gemini-key")

    status = AIEvaluatorService().status()

    assert status["primary"] is None
    assert status["fallback"] is None
    assert status["cloud_configured"] is False
