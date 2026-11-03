"""Contratos del cliente FCM: payload data-only y baja correcta de tokens.

Ninguna prueba toca la red: ``httpx.AsyncClient`` se reemplaza por un doble.
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest

from app.services import push as push_module
from app.services.push import FirebasePushService

CREDENTIALS = SimpleNamespace(project_id="demo-project")


class FakeResponse:
    def __init__(self, status_code: int, payload: dict | None = None) -> None:
        self.status_code = status_code
        self._payload = payload or {}

    def json(self) -> dict:
        return self._payload


class FakeClient:
    """Captura el JSON enviado y devuelve una respuesta programada."""

    captured: list[dict] = []
    response = FakeResponse(200)

    def __init__(self, *args, **kwargs) -> None:
        pass

    async def __aenter__(self) -> "FakeClient":
        return self

    async def __aexit__(self, *args) -> bool:
        return False

    async def post(self, url: str, json: dict | None = None, headers: dict | None = None) -> FakeResponse:
        FakeClient.captured.append(json or {})
        return FakeClient.response


@pytest.fixture
def service(monkeypatch):
    monkeypatch.setattr(push_module.settings, "fcm_enabled", True)
    monkeypatch.setattr(push_module.httpx, "AsyncClient", FakeClient)
    FakeClient.captured = []
    FakeClient.response = FakeResponse(200)

    instance = FirebasePushService()
    monkeypatch.setattr(instance, "_load", lambda: CREDENTIALS)

    async def access() -> str:
        return "access-token"

    monkeypatch.setattr(instance, "_access", access)
    return instance


def test_payload_uses_a_system_notification_without_content(service):
    result = asyncio.run(
        service.send(
            ["token-abc"],
            data={"tipo": "risk_alert", "alert_id": "alert-1", "severidad": "ALTA"},
        )
    )

    assert result.sent == 1
    message = FakeClient.captured[0]["message"]
    assert message["notification"]["title"] == "IA Guardian · Señal alta"
    assert message["notification"]["body"] == "Señal alta en el teléfono"
    assert message["android"]["priority"] == "high"
    assert message["android"]["notification"]["channel_id"] == "guardiania_alerts"
    assert message["android"]["ttl"] == "300s"
    assert message["data"]["alert_id"] == "alert-1"
    assert message["data"]["open_alerts"] == "1"
    assert message["token"] == "token-abc"


def test_payload_error_does_not_deactivate_token(service):
    FakeClient.response = FakeResponse(
        400,
        {"error": {"status": "INVALID_ARGUMENT", "message": "Invalid JSON payload received"}},
    )

    result = asyncio.run(service.send(["token-abc"], data={"tipo": "risk_alert"}))

    assert result.sent == 0
    assert result.invalid_tokens == ()


def test_unregistered_token_is_deactivated(service):
    FakeClient.response = FakeResponse(
        404,
        {
            "error": {
                "status": "NOT_FOUND",
                "details": [{"errorCode": "UNREGISTERED"}],
            }
        },
    )

    result = asyncio.run(service.send(["token-abc"], data={"tipo": "risk_alert"}))

    assert result.sent == 0
    assert result.invalid_tokens == ("token-abc",)
