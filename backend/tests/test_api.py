import os
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

DB_PATH = Path(__file__).resolve().parent / "test_guardiania.db"
if DB_PATH.exists():
    DB_PATH.unlink()

os.environ["DATABASE_URL"] = f"sqlite+aiosqlite:///{DB_PATH.as_posix()}"
os.environ["SEED_DEMO"] = "false"
os.environ["RATE_LIMIT_ENABLED"] = "false"
os.environ["ENVIRONMENT"] = "test"

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.main import app


@pytest.fixture(scope="session")
def client():
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def tutor(client):
    email = f"tutor-{uuid.uuid4().hex[:8]}@guardiania.cl"
    response = client.post(
        "/api/v1/auth/register",
        json={
            "email": email,
            "password": "ClaveSegura2026",
            "alias": "Tutora de prueba",
            "consent": True,
        },
    )
    assert response.status_code == 201, response.text
    data = response.json()
    return {"email": email, "token": data["access_token"], "id": data["tutor"]["id"]}


@pytest.fixture
def auth_headers(tutor):
    return {"Authorization": f"Bearer {tutor['token']}"}


def device_payload(install_id: uuid.UUID | None = None) -> dict:
    return {
        "platform": "ANDROID",
        "api_level": 34,
        "model_family": "Pixel 8",
        "install_id": str(install_id or uuid.uuid4()),
        "attestation_token": None,
    }


def generate_pairing(client, headers, etiqueta="Sofía"):
    response = client.post(
        "/api/v1/pairing/generate",
        json={"etiqueta_menor": etiqueta, "ttl_seconds": 600, "tutor_declara_tutoria": True},
        headers=headers,
    )
    assert response.status_code == 201, response.text
    return response.json()


def verify_manual(client, code, install_id=None):
    return client.post(
        "/api/v1/pairing/verify",
        json={"manual_code": code.replace(" ", ""), "device": device_payload(install_id), "child_assent": True},
    )


def test_health(client):
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_pairing_full_flow(client, auth_headers):
    generated = generate_pairing(client, auth_headers)
    assert len(generated["manual_code"].replace(" ", "")) == 6
    assert generated["qr_payload"].startswith("guardiania://pair?v=1&t=")
    assert generated["qr_svg"].startswith("<svg")

    install_id = uuid.uuid4()
    verified = verify_manual(client, generated["manual_code"], install_id)
    assert verified.status_code == 200, verified.text
    body = verified.json()
    assert body["status"] == "ACTIVO"
    assert body["expires_in"] == 900
    assert body["device_alias"] == "Sofía"

    status = client.get(f"/api/v1/pairing/{generated['session_id']}", headers=auth_headers)
    assert status.status_code == 200
    assert status.json()["estado"] == "VERIFICADA"
    assert status.json()["vinculacion_estado"] == "ACTIVO"

    reuse = verify_manual(client, generated["manual_code"], install_id)
    # Se responde genérico para no confirmar que el código existió.
    assert reuse.status_code == 422


def test_relinking_same_phone_replaces_previous_binding(client, auth_headers):
    install_id = uuid.uuid4()

    first = generate_pairing(client, auth_headers, etiqueta="Primera")
    first_verify = verify_manual(client, first["manual_code"], install_id)
    assert first_verify.status_code == 200, first_verify.text
    first_body = first_verify.json()

    second = generate_pairing(client, auth_headers, etiqueta="Segunda")
    second_verify = verify_manual(client, second["manual_code"], install_id)
    assert second_verify.status_code == 200, second_verify.text
    second_body = second_verify.json()

    assert first_body["device_id"] != second_body["device_id"]
    assert first_body["vinculacion_id"] != second_body["vinculacion_id"]

    activos = [
        device
        for device in client.get("/api/v1/devices", headers=auth_headers).json()
        if device["model_family"] == "Pixel 8" and device["estado"] == "ACTIVO"
    ]
    assert len(activos) == 1
    assert activos[0]["id"] == second_body["device_id"]

    # El token del teléfono anterior deja de servir de inmediato.
    stale = client.post(
        "/api/v1/captures/sync",
        json={"batch_id": str(uuid.uuid4()), "device_clock": "2026-01-01T00:00:00Z", "captures": []},
        headers={"Authorization": f"Bearer {first_body['access_token']}"},
    )
    assert stale.status_code in (401, 410)


def test_qr_token_cannot_be_reused(client, auth_headers):
    generated = generate_pairing(client, auth_headers)
    qr_token = generated["qr_payload"].split("t=", 1)[1]
    first = client.post("/api/v1/pairing/verify", json={"qr_token": qr_token, "device": device_payload(), "child_assent": True})
    assert first.status_code == 200
    second = client.post("/api/v1/pairing/verify", json={"qr_token": qr_token, "device": device_payload(), "child_assent": True})
    assert second.status_code == 409


def test_qr_payload_is_short_enough_to_scan(client, auth_headers):
    generated = generate_pairing(client, auth_headers)
    token = generated["qr_payload"].split("t=", 1)[1]

    # El JWT anterior medía ~330 caracteres y producía un QR de 89×89 módulos,
    # imposible de leer en pantalla a distancia normal. El token corto debe
    # dejar el código por debajo de ~50 módulos por lado.
    assert len(token) < 80
    assert len(token.split(".")[0]) == 36

    # Un token manipulado no abre la sesión.
    tampered = token[:-1] + ("A" if token[-1] != "A" else "B")
    rejected = client.post(
        "/api/v1/pairing/verify",
        json={"qr_token": tampered, "device": device_payload(), "child_assent": True},
    )
    assert rejected.status_code in (401, 422)


def test_qr_svg_has_few_modules(client, auth_headers):
    generated = generate_pairing(client, auth_headers)
    svg = generated["qr_svg"]
    # width = (modulos + 2 de borde) * scale(6)
    width = int(svg.split('width="', 1)[1].split('"', 1)[0])
    modules = width // 6 - 2
    assert modules <= 45, f"QR de {modules} módulos: demasiado denso para pantalla"


def test_pairing_rejects_unknown_code(client):
    response = verify_manual(client, "000000")
    assert response.status_code == 422


def test_pairing_qr_flow_and_ws_event(client, auth_headers):
    generated = generate_pairing(client, auth_headers, etiqueta="Matías")
    ws_path = generated["ws_url"]

    with client.websocket_connect(
        ws_path,
        subprotocols=["guardiania", generated["ws_ticket"]],
    ) as websocket:
        ready = websocket.receive_json()
        assert ready["type"] == "pairing_ready"
        assert ready["data"]["estado"] == "PENDIENTE"

        response = client.post(
            "/api/v1/pairing/verify",
            json={"qr_token": generated["qr_payload"].split("t=", 1)[1], "device": device_payload(), "child_assent": True},
        )
        assert response.status_code == 200, response.text

        success = websocket.receive_json()
        assert success["type"] == "pairing_success"
        assert success["data"]["device_alias"] == "Matías"


def test_pairing_ws_rejects_ticket_in_query_string(client, auth_headers):
    generated = generate_pairing(client, auth_headers, etiqueta="Ticket en URL")
    with pytest.raises(WebSocketDisconnect) as exc:
        with client.websocket_connect(
            f"{generated['ws_url']}?ticket={generated['ws_ticket']}"
        ) as websocket:
            websocket.receive_json()
    assert exc.value.code == 4401


def test_capture_sync_is_idempotent_and_rejects_extra_fields(client, auth_headers):
    generated = generate_pairing(client, auth_headers)
    verified = verify_manual(client, generated["manual_code"]).json()
    device_id = verified["device_id"]
    headers = {"Authorization": f"Bearer {verified['access_token']}"}
    event_id = str(uuid.uuid4())
    # Ventana relativa al reloj: con fechas fijas el test caduca solo al pasar
    # la retención de 7 días y parece una regresión de la API.
    window_end = datetime.now(timezone.utc) - timedelta(minutes=5)
    window_start = window_end - timedelta(minutes=15)

    payload = {
        "schema_version": 1,
        "batch_id": str(uuid.uuid4()),
        "device_clock": window_end.isoformat().replace("+00:00", "Z"),
        "captures": [
            {
                "schema_version": 1,
                "event_id": event_id,
                "device_id": device_id,
                "time_window": {
                    "start": window_start.isoformat().replace("+00:00", "Z"),
                    "end": window_end.isoformat().replace("+00:00", "Z"),
                    "tz_offset_minutes": -180,
                },
                "category": "social",
                "lexical_flag_count": 1,
                "flags": ["unknown_contact"],
                "hashed_contact_id": "ZGVtby1jb250YWN0by1kZW1v",
                "usage_duration_minutes": 15,
                "integrity": {"attestation_ok": True, "app_version": "1.0.0"},
            }
        ],
    }

    first = client.post("/api/v1/captures/sync", json=payload, headers=headers)
    assert first.status_code == 200, first.text
    assert first.json()["accepted"] == 1
    assert first.json()["duplicates"] == 0

    second = client.post("/api/v1/captures/sync", json=payload, headers=headers)
    assert second.status_code == 200
    assert second.json()["accepted"] == 0
    assert second.json()["duplicates"] == 1

    unsafe = {**payload, "captures": [{**payload["captures"][0], "raw_text": "hola mamá"}]}
    rejected = client.post("/api/v1/captures/sync", json=unsafe, headers=headers)
    assert rejected.status_code == 422

    mismatch = {
        **payload,
        "captures": [
            {**payload["captures"][0], "event_id": str(uuid.uuid4()), "device_id": str(uuid.uuid4())}
        ],
    }
    bad_device = client.post("/api/v1/captures/sync", json=mismatch, headers=headers)
    assert bad_device.status_code == 200
    assert bad_device.json()["rejected"][0]["code"] == "DEVICE_MISMATCH"


def test_revoke_device_blocks_sync(client, auth_headers):
    generated = generate_pairing(client, auth_headers)
    verified = verify_manual(client, generated["manual_code"]).json()
    headers = {"Authorization": f"Bearer {verified['access_token']}"}

    devices = client.get("/api/v1/devices", headers=auth_headers).json()
    binding_id = next(d["vinculacion_id"] for d in devices if d["id"] == verified["device_id"])
    revoked = client.post(f"/api/v1/devices/{binding_id}/revoke", headers=auth_headers)
    assert revoked.status_code == 204

    sync = client.post(
        "/api/v1/captures/sync",
        json={"schema_version": 1, "batch_id": str(uuid.uuid4()), "device_clock": "2026-10-22T12:00:00Z", "captures": []},
        headers=headers,
    )
    assert sync.status_code == 410


def test_device_refresh_exchanges_token_and_revocation_invalidates_it(client, auth_headers):
    generated = generate_pairing(client, auth_headers)
    verified = verify_manual(client, generated["manual_code"]).json()

    refreshed = client.post("/api/v1/devices/refresh", json={"refresh_token": verified["refresh_token"]})
    assert refreshed.status_code == 200, refreshed.text
    body = refreshed.json()
    assert body["token_type"] == "bearer"
    assert body["expires_in"] == 900

    sync = client.post(
        "/api/v1/captures/sync",
        json={"schema_version": 1, "batch_id": str(uuid.uuid4()), "device_clock": "2026-10-22T12:00:00Z", "captures": []},
        headers={"Authorization": f"Bearer {body['access_token']}"},
    )
    assert sync.status_code == 200, sync.text

    assert client.post("/api/v1/devices/refresh", json={"refresh_token": "x" * 48}).status_code == 401

    devices = client.get("/api/v1/devices", headers=auth_headers).json()
    binding_id = next(d["vinculacion_id"] for d in devices if d["id"] == verified["device_id"])
    assert client.post(f"/api/v1/devices/{binding_id}/revoke", headers=auth_headers).status_code == 204

    blocked = client.post("/api/v1/devices/refresh", json={"refresh_token": verified["refresh_token"]})
    assert blocked.status_code == 401


def test_cookie_session_and_csrf(client):
    client.cookies.clear()
    email = f"cookie-{uuid.uuid4().hex[:8]}@guardiania.cl"
    csrf = client.get("/api/v1/auth/csrf").json()["csrf_token"]
    response = client.post(
        "/api/v1/auth/register",
        json={
            "email": email,
            "password": "ClaveSegura2026",
            "alias": "Tutora cookie",
            "consent": True,
        },
        headers={"X-CSRF-Token": csrf},
    )
    assert response.status_code == 201, response.text
    assert "guardiania_access" in client.cookies
    assert client.get("/api/v1/overview").status_code == 200

    # Cookie-authenticated writes require the double-submit CSRF header.
    assert client.post("/api/v1/auth/logout").status_code == 403
    csrf = client.cookies.get("guardiania_csrf")
    assert client.post("/api/v1/auth/logout", headers={"X-CSRF-Token": csrf}).status_code == 204
    assert client.get("/api/v1/overview").status_code == 401
    client.cookies.clear()


def test_tutor_refresh_rotates_and_invalidates_old_token(client):
    client.cookies.clear()
    email = f"refresh-{uuid.uuid4().hex[:8]}@guardiania.cl"
    response = client.post(
        "/api/v1/auth/register",
        json={
            "email": email,
            "password": "ClaveSegura2026",
            "alias": "Tutora refresh",
            "consent": True,
        },
    )
    assert response.status_code == 201, response.text
    old_refresh = response.json()["refresh_token"]
    rotated = client.post("/api/v1/auth/refresh", json={"refresh_token": old_refresh})
    assert rotated.status_code == 200, rotated.text
    new_refresh = rotated.json()["refresh_token"]
    assert new_refresh and new_refresh != old_refresh
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": old_refresh}).status_code == 401


def test_pairing_requires_tutor_declaration_and_child_assent(client, auth_headers):
    missing_declaration = client.post(
        "/api/v1/pairing/generate",
        json={"etiqueta_menor": "Sin declaración", "ttl_seconds": 600},
        headers=auth_headers,
    )
    assert missing_declaration.status_code == 422

    generated = generate_pairing(client, auth_headers)
    missing_assent = client.post(
        "/api/v1/pairing/verify",
        json={
            "manual_code": generated["manual_code"].replace(" ", ""),
            "device": device_payload(),
            "child_assent": False,
        },
    )
    assert missing_assent.status_code == 422


def test_account_export_and_deletion(client):
    client.cookies.clear()
    email = f"delete-{uuid.uuid4().hex[:8]}@guardiania.cl"
    password = "ClaveSegura2026"
    registered = client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": password, "alias": "Cuenta borrable", "consent": True},
    )
    assert registered.status_code == 201
    headers = {"Authorization": f"Bearer {registered.json()['access_token']}"}

    exported = client.get("/api/v1/account/export", headers=headers)
    assert exported.status_code == 200, exported.text
    assert exported.headers["content-disposition"].startswith("attachment;")
    assert exported.json()["tutor"]["email"] == email

    wrong = client.post(
        "/api/v1/account/delete",
        json={"password": password, "confirmation": "NO ELIMINAR"},
        headers=headers,
    )
    assert wrong.status_code == 400

    deleted = client.post(
        "/api/v1/account/delete",
        json={"password": password, "confirmation": "ELIMINAR MI CUENTA"},
        headers=headers,
    )
    assert deleted.status_code == 204, deleted.text
    assert client.post("/api/v1/auth/login", json={"email": email, "password": password}).status_code == 401


def test_password_reset_uses_fragment_and_revokes_sessions(client, monkeypatch):
    client.cookies.clear()
    email = f"reset-{uuid.uuid4().hex[:8]}@guardiania.cl"
    old_password = "ClaveSegura2026"
    new_password = "ClaveNuevaSegura2026"
    registered = client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": old_password, "alias": "Cuenta recuperable", "consent": True},
    )
    assert registered.status_code == 201, registered.text
    old_refresh = registered.json()["refresh_token"]

    sent = []

    async def fake_send(to, subject, body):
        sent.append({"to": to, "subject": subject, "body": body})

    monkeypatch.setattr("app.services.accounts.send_email", fake_send)

    forgot = client.post("/api/v1/auth/password/forgot", json={"email": email})
    assert forgot.status_code == 202
    assert len(sent) == 1
    assert "/restablecer#token=" in sent[0]["body"]
    assert "?token=" not in sent[0]["body"]
    token = sent[0]["body"].split("/restablecer#token=", 1)[1].split(None, 1)[0]

    unknown = client.post("/api/v1/auth/password/forgot", json={"email": f"nadie-{uuid.uuid4().hex[:8]}@guardiania.cl"})
    assert unknown.status_code == 202
    assert unknown.json()["message"] == forgot.json()["message"]
    assert len(sent) == 1

    reset = client.post("/api/v1/auth/password/reset", json={"token": token, "new_password": new_password})
    assert reset.status_code == 204, reset.text
    assert client.post("/api/v1/auth/login", json={"email": email, "password": old_password}).status_code == 401

    relogin = client.post("/api/v1/auth/login", json={"email": email, "password": new_password})
    assert relogin.status_code == 200, relogin.text
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": old_refresh}).status_code == 401
    assert client.post(
        "/api/v1/auth/password/reset",
        json={"token": token, "new_password": "OtraClaveSegura2026"},
    ).status_code == 400


def test_email_verification_link_and_single_use(client, monkeypatch):
    from app.config import settings

    client.cookies.clear()
    monkeypatch.setattr(settings, "require_email_verification", True)
    email = f"verify-{uuid.uuid4().hex[:8]}@guardiania.cl"
    password = "ClaveSegura2026"

    sent = []

    async def fake_send(to, subject, body):
        sent.append({"to": to, "subject": subject, "body": body})

    monkeypatch.setattr("app.services.accounts.send_email", fake_send)

    registered = client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": password, "alias": "Cuenta por verificar", "consent": True},
    )
    assert registered.status_code == 202, registered.text
    assert len(sent) == 1
    assert "/verificar-correo#token=" in sent[0]["body"]
    token = sent[0]["body"].split("/verificar-correo#token=", 1)[1].split(None, 1)[0]

    assert client.post("/api/v1/auth/login", json={"email": email, "password": password}).status_code == 403
    verified = client.post("/api/v1/auth/verify-email", json={"token": token})
    assert verified.status_code == 200, verified.text
    assert verified.json()["email_verified"] is True
    assert client.post("/api/v1/auth/verify-email", json={"token": token}).status_code == 400
    assert client.post("/api/v1/auth/login", json={"email": email, "password": password}).status_code == 200


def test_overview_requires_authentication(client):
    client.cookies.clear()
    assert client.get("/api/v1/overview").status_code == 401


# ---------------------------------------------------------------------------
# IA 1.2.0: evaluación, alertas y stream del tutor
# ---------------------------------------------------------------------------


def evaluate_payload(device_id, *, flags=("self_harm",), event_id=None, age_minutes=15):
    now = datetime.now(timezone.utc).replace(microsecond=0)
    start = now - timedelta(minutes=age_minutes)
    return {
        "schema_version": 1,
        "event_id": str(event_id or uuid.uuid4()),
        "device_id": device_id,
        "time_window": {
            "start": start.isoformat(),
            "end": now.isoformat(),
            "tz_offset_minutes": 0,
        },
        "category": "social",
        "lexical_flag_count": len(flags),
        "flags": list(flags),
        "usage_duration_minutes": 15,
        "integrity": {"attestation_ok": True, "app_version": "1.2.0"},
    }


def test_capture_evaluate_notifies_tutor_over_ws(client, auth_headers, monkeypatch):
    from app.services import evaluation as evaluation_module
    from app.services.ai_evaluator import AIEvaluatorService, HeuristicProvider

    monkeypatch.setattr(
        evaluation_module,
        "ai_evaluator",
        AIEvaluatorService(heuristic=HeuristicProvider(), total_timeout_ms=500),
    )

    generated = generate_pairing(client, auth_headers, etiqueta="Diego")
    verified = verify_manual(client, generated["manual_code"]).json()
    device_headers = {"Authorization": f"Bearer {verified['access_token']}"}

    ticket = client.post("/api/v1/realtime/ticket", headers=auth_headers)
    assert ticket.status_code == 200, ticket.text
    stream = ticket.json()
    assert stream["ws_url"] == "/ws/tutor"
    assert stream["expires_in"] > 0

    with client.websocket_connect(
        stream["ws_url"], subprotocols=["guardiania", stream["ws_ticket"]]
    ) as websocket:
        ready = websocket.receive_json()
        assert ready["type"] == "tutor_ready"
        assert ready["data"]["alerts_subscribed"] is True

        response = client.post(
            "/api/v1/captures/evaluate",
            json=evaluate_payload(verified["device_id"]),
            headers=device_headers,
        )
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["evaluation"]["nivel_riesgo"] == "ALTO"
        assert body["evaluation"]["categoria"] == "Conducta_Riesgo"
        assert body["provider"] == "heuristic"
        assert body["cached"] is False
        assert body["alert_id"]
        assert body["notified"] is True
        assert body["latency_ms"] < 3000

        event = websocket.receive_json()
        assert event["type"] == "risk_alert"
        assert event["data"]["alert_id"] == body["alert_id"]
        assert event["data"]["severidad"] == "CRITICA"
        assert "raw" not in event["data"]


def test_index_serves_versioned_assets_without_cache(client):
    """Un despliegue no puede dejar la pestaña con JS viejo."""

    response = client.get("/")
    assert response.status_code == 200
    assert response.headers.get("cache-control") == "no-store"
    assert "/static/app.js?v=" in response.text
    assert "/static/app.css?v=" in response.text


def test_sync_publishes_medium_rule_alert_to_stream(client, auth_headers):
    """Una alerta MEDIA de reglas también llega al panel sin recargar."""

    generated = generate_pairing(client, auth_headers)
    verified = verify_manual(client, generated["manual_code"]).json()
    device_headers = {"Authorization": f"Bearer {verified['access_token']}"}

    ticket = client.post("/api/v1/realtime/ticket", headers=auth_headers).json()

    now = datetime.now(timezone.utc)
    start = now - timedelta(minutes=10)
    payload = {
        "schema_version": 1,
        "batch_id": str(uuid.uuid4()),
        "device_clock": now.isoformat().replace("+00:00", "Z"),
        "captures": [
            {
                "schema_version": 1,
                "event_id": str(uuid.uuid4()),
                "device_id": verified["device_id"],
                "time_window": {
                    "start": start.isoformat().replace("+00:00", "Z"),
                    "end": now.isoformat().replace("+00:00", "Z"),
                    "tz_offset_minutes": -180,
                },
                "category": "social",
                "lexical_flag_count": 1,
                "flags": ["gambling"],
                "usage_duration_minutes": 12,
                "notification_count": 3,
                "event_count": 3,
                "integrity": {"attestation_ok": True, "app_version": "1.2.0"},
            }
        ],
    }

    with client.websocket_connect(
        ticket["ws_url"], subprotocols=["guardiania", ticket["ws_ticket"]]
    ) as websocket:
        assert websocket.receive_json()["type"] == "tutor_ready"

        synced = client.post("/api/v1/captures/sync", json=payload, headers=device_headers)
        assert synced.status_code == 200, synced.text

        event = websocket.receive_json()
        assert event["type"] == "risk_alert"
        assert event["data"]["categoria"] == "gambling"
        assert event["data"]["severidad"] == "MEDIA"


def test_capture_evaluate_dedupes_and_rejects_raw_text(client, auth_headers, monkeypatch):
    from app.services import evaluation as evaluation_module
    from app.services.ai_evaluator import AIEvaluatorService, HeuristicProvider

    monkeypatch.setattr(
        evaluation_module,
        "ai_evaluator",
        AIEvaluatorService(heuristic=HeuristicProvider(), total_timeout_ms=500),
    )

    generated = generate_pairing(client, auth_headers)
    verified = verify_manual(client, generated["manual_code"]).json()
    device_headers = {"Authorization": f"Bearer {verified['access_token']}"}
    payload = evaluate_payload(verified["device_id"])

    first = client.post("/api/v1/captures/evaluate", json=payload, headers=device_headers)
    assert first.status_code == 200
    assert first.json()["alert_id"]

    second = client.post("/api/v1/captures/evaluate", json=payload, headers=device_headers)
    assert second.status_code == 200
    assert second.json()["alert_id"] is None
    assert second.json()["notified"] is False

    unsafe = {**payload, "raw_text": "mensaje privado del menor"}
    assert client.post("/api/v1/captures/evaluate", json=unsafe, headers=device_headers).status_code == 422

    mismatch = {**payload, "device_id": str(uuid.uuid4())}
    assert client.post("/api/v1/captures/evaluate", json=mismatch, headers=device_headers).status_code == 409

    old = evaluate_payload(verified["device_id"], age_minutes=8 * 24 * 60)
    assert client.post("/api/v1/captures/evaluate", json=old, headers=device_headers).status_code == 422


def test_tutor_stream_rejects_wrong_scope_and_query_ticket(client, auth_headers):
    generated = generate_pairing(client, auth_headers)

    with pytest.raises(WebSocketDisconnect) as exc:
        with client.websocket_connect(
            "/ws/tutor", subprotocols=["guardiania", generated["ws_ticket"]]
        ) as websocket:
            websocket.receive_json()
    assert exc.value.code == 4403

    ticket = client.post("/api/v1/realtime/ticket", headers=auth_headers).json()
    with pytest.raises(WebSocketDisconnect) as exc:
        with client.websocket_connect(f"/ws/tutor?ticket={ticket['ws_ticket']}") as websocket:
            websocket.receive_json()
    assert exc.value.code == 4401


def test_push_tokens_are_registered_without_echoing_them(client, auth_headers):
    token = "fcm-" + "x" * 120
    created = client.post(
        "/api/v1/push/tokens",
        json={"token": token, "plataforma": "ANDROID", "device_label": "Pixel del tutor"},
        headers=auth_headers,
    )
    assert created.status_code == 201, created.text
    body = created.json()
    assert "token" not in body
    assert body["activo"] is True
    assert body["device_label"] == "Pixel del tutor"

    listed = client.get("/api/v1/push/tokens", headers=auth_headers)
    assert listed.status_code == 200
    assert any(item["id"] == body["id"] for item in listed.json())
    assert all("token" not in item for item in listed.json())

    removed = client.post(
        "/api/v1/push/tokens/unregister",
        json={"token": token},
        headers=auth_headers,
    )
    assert removed.status_code == 204
    listed_after = client.get("/api/v1/push/tokens", headers=auth_headers).json()
    assert next(item for item in listed_after if item["id"] == body["id"])["activo"] is False


def test_push_token_rejects_jwt_shaped_credentials(client, auth_headers):
    """Un access token de sesión no es un token de entrega y no debe guardarse."""

    jwt_like = "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiJkZW1vIn0.firma"
    response = client.post(
        "/api/v1/push/tokens",
        json={"token": jwt_like, "plataforma": "ANDROID"},
        headers=auth_headers,
    )
    assert response.status_code == 422
    assert "FCM" in response.text

# --- Seguridad del panel: código de desvinculación, ocultar y sin señal ---


def _set_last_connection(device_id: str, when: datetime) -> None:
    import asyncio

    from app.db import SessionFactory
    from app.models import Device

    async def run() -> None:
        async with SessionFactory() as db:
            device = await db.get(Device, uuid.UUID(device_id))
            assert device is not None
            device.ultima_conexion = when
            device.creado_en = when - timedelta(hours=1)
            await db.commit()

    asyncio.run(run())


def _sync_payload(device_id: str, *, flags: list[str] | None = None) -> dict:
    now = datetime.now(timezone.utc)
    start = now - timedelta(minutes=10)
    return {
        "schema_version": 1,
        "batch_id": str(uuid.uuid4()),
        "device_clock": now.isoformat().replace("+00:00", "Z"),
        "captures": [
            {
                "schema_version": 1,
                "event_id": str(uuid.uuid4()),
                "device_id": device_id,
                "time_window": {
                    "start": start.isoformat().replace("+00:00", "Z"),
                    "end": now.isoformat().replace("+00:00", "Z"),
                    "tz_offset_minutes": -180,
                },
                "category": "social",
                "lexical_flag_count": len(flags or []),
                "flags": flags or [],
                "usage_duration_minutes": 5,
                "notification_count": 1,
                "event_count": 1,
                "integrity": {"attestation_ok": True, "app_version": "1.2.0"},
            }
        ],
    }


def test_unlink_requires_tutor_code(client, auth_headers):
    generated = generate_pairing(client, auth_headers)
    verified = verify_manual(client, generated["manual_code"]).json()
    device_headers = {"Authorization": f"Bearer {verified['access_token']}"}
    binding_id = verified["vinculacion_id"]

    assert client.put(
        f"/api/v1/devices/{binding_id}/unlink-code",
        json={"codigo": "481516"},
        headers=auth_headers,
    ).status_code == 204

    devices = client.get("/api/v1/devices", headers=auth_headers).json()
    assert next(d for d in devices if d["id"] == verified["device_id"])["unlink_code_set"] is True

    missing = client.post("/api/v1/devices/self/unlink", json={}, headers=device_headers)
    assert missing.status_code == 403
    assert missing.json()["detail"]["code"] == "pin_required"

    wrong = client.post(
        "/api/v1/devices/self/unlink", json={"codigo": "000000"}, headers=device_headers
    )
    assert wrong.status_code == 403
    assert wrong.json()["detail"]["code"] == "pin_invalid"
    assert wrong.json()["detail"]["attempts_left"] == 4

    ok = client.post(
        "/api/v1/devices/self/unlink", json={"codigo": "481516"}, headers=device_headers
    )
    assert ok.status_code == 204, ok.text

    revoked = client.get("/api/v1/devices", headers=auth_headers).json()
    row = next(d for d in revoked if d["id"] == verified["device_id"])
    assert row["vinculacion_estado"] == "REVOCADO"
    assert row["unlink_code_set"] is False

    stale = client.post(
        "/api/v1/captures/sync",
        json={
            "batch_id": str(uuid.uuid4()),
            "device_clock": datetime.now(timezone.utc).isoformat(),
            "captures": [],
        },
        headers=device_headers,
    )
    assert stale.status_code in (401, 410)


def test_unlink_code_locks_after_five_failures(client, auth_headers):
    generated = generate_pairing(client, auth_headers)
    verified = verify_manual(client, generated["manual_code"]).json()
    device_headers = {"Authorization": f"Bearer {verified['access_token']}"}
    binding_id = verified["vinculacion_id"]

    client.put(
        f"/api/v1/devices/{binding_id}/unlink-code",
        json={"codigo": "481516"},
        headers=auth_headers,
    )

    for _ in range(4):
        wrong = client.post(
            "/api/v1/devices/self/unlink", json={"codigo": "999999"}, headers=device_headers
        )
        assert wrong.status_code == 403

    fifth = client.post(
        "/api/v1/devices/self/unlink", json={"codigo": "999999"}, headers=device_headers
    )
    assert fifth.status_code == 403
    assert fifth.json()["detail"]["locked"] is True

    correct_but_locked = client.post(
        "/api/v1/devices/self/unlink", json={"codigo": "481516"}, headers=device_headers
    )
    assert correct_but_locked.status_code == 429
    assert correct_but_locked.json()["detail"]["code"] == "pin_locked"


def test_hide_revoked_device_keeps_history_out_of_the_list(client, auth_headers):
    generated = generate_pairing(client, auth_headers)
    verified = verify_manual(client, generated["manual_code"]).json()

    active = client.post(f"/api/v1/devices/{verified['device_id']}/hide", headers=auth_headers)
    assert active.status_code == 409

    assert client.post(
        f"/api/v1/devices/{verified['vinculacion_id']}/revoke", headers=auth_headers
    ).status_code == 204
    assert client.post(
        f"/api/v1/devices/{verified['device_id']}/hide", headers=auth_headers
    ).status_code == 204

    devices = client.get("/api/v1/devices", headers=auth_headers).json()
    assert all(d["id"] != verified["device_id"] for d in devices)
    overview = client.get("/api/v1/overview", headers=auth_headers).json()
    assert all(d["id"] != verified["device_id"] for d in overview["devices"])

    # Idempotente.
    assert client.post(
        f"/api/v1/devices/{verified['device_id']}/hide", headers=auth_headers
    ).status_code == 204


def test_hide_all_revoked_devices(client, auth_headers):
    for _ in range(2):
        generated = generate_pairing(client, auth_headers)
        verified = verify_manual(client, generated["manual_code"]).json()
        client.post(f"/api/v1/devices/{verified['vinculacion_id']}/revoke", headers=auth_headers)

    result = client.post("/api/v1/devices/hide-revoked", headers=auth_headers)
    assert result.status_code == 200
    assert result.json()["ocultos"] == 2
    assert client.get("/api/v1/devices", headers=auth_headers).json() == []


def test_offline_alert_is_sent_once_and_reconnect_is_announced(client, auth_headers):
    generated = generate_pairing(client, auth_headers, etiqueta="Sin señal")
    verified = verify_manual(client, generated["manual_code"]).json()
    device_headers = {"Authorization": f"Bearer {verified['access_token']}"}
    _set_last_connection(verified["device_id"], datetime.now(timezone.utc) - timedelta(hours=3))

    ticket = client.post("/api/v1/realtime/ticket", headers=auth_headers).json()
    with client.websocket_connect(
        ticket["ws_url"], subprotocols=["guardiania", ticket["ws_ticket"]]
    ) as websocket:
        assert websocket.receive_json()["type"] == "tutor_ready"

        client.get("/api/v1/overview", headers=auth_headers)
        event = websocket.receive_json()
        assert event["type"] == "risk_alert"
        assert event["data"]["categoria"] == "sin_senal"
        assert event["data"]["severidad"] == "MEDIA"

        alerts = client.get("/api/v1/alerts?limit=50", headers=auth_headers).json()
        assert len([a for a in alerts if a["categoria"] == "sin_senal"]) == 1

        # Un segundo barrido no repite el aviso del mismo corte.
        client.get("/api/v1/overview", headers=auth_headers)
        alerts = client.get("/api/v1/alerts?limit=50", headers=auth_headers).json()
        assert len([a for a in alerts if a["categoria"] == "sin_senal"]) == 1

        synced = client.post(
            "/api/v1/captures/sync",
            json=_sync_payload(verified["device_id"]),
            headers=device_headers,
        )
        assert synced.status_code == 200, synced.text

        reconnected = websocket.receive_json()
        assert reconnected["type"] == "device_reconnected"
        assert reconnected["data"]["device_alias"]

def test_fresh_device_is_not_reported_offline(client, auth_headers):
    generated = generate_pairing(client, auth_headers)
    verified = verify_manual(client, generated["manual_code"]).json()

    client.get("/api/v1/overview", headers=auth_headers)
    alerts = client.get("/api/v1/alerts?limit=50", headers=auth_headers).json()
    assert [a for a in alerts if a["categoria"] == "sin_senal"] == []
    assert verified["device_id"]

def test_maintenance_sweep_requires_the_key(client, monkeypatch):
    from app.config import settings

    monkeypatch.setattr(settings, "maintenance_key", "clave-de-prueba")

    assert client.post("/api/v1/maintenance/sweep").status_code == 404
    assert client.post(
        "/api/v1/maintenance/sweep", headers={"X-Maintenance-Key": "otra"}
    ).status_code == 404
    ok = client.post(
        "/api/v1/maintenance/sweep", headers={"X-Maintenance-Key": "clave-de-prueba"}
    )
    assert ok.status_code == 200
    assert "avisos" in ok.json()

def test_status_alerts_when_notification_access_is_lost(client, auth_headers):
    generated = generate_pairing(client, auth_headers)
    verified = verify_manual(client, generated["manual_code"]).json()
    device_headers = {"Authorization": f"Bearer {verified['access_token']}"}

    def report(**overrides):
        body = {
            "notificaciones_activas": True,
            "uso_activo": True,
            "dnd": False,
            "accesibilidad": False,
        }
        body.update(overrides)
        return client.post("/api/v1/devices/self/status", json=body, headers=device_headers)

    assert report(notificaciones_activas=False).status_code == 204
    alerts = client.get("/api/v1/alerts?limit=50", headers=auth_headers).json()
    access = [a for a in alerts if a["categoria"] == "acceso_desactivado"]
    assert len(access) == 1
    assert access[0]["severidad"] == "ALTA"
    assert "acceso a notificaciones" in access[0]["resumen"].lower()

    # El mismo corte no repite la alerta.
    assert report(notificaciones_activas=False).status_code == 204
    alerts = client.get("/api/v1/alerts?limit=50", headers=auth_headers).json()
    assert len([a for a in alerts if a["categoria"] == "acceso_desactivado"]) == 1

    devices = client.get("/api/v1/devices", headers=auth_headers).json()
    row = next(d for d in devices if d["id"] == verified["device_id"])
    assert row["notificaciones_activas"] is False

    # Al reactivarlo, el estado vuelve y un corte nuevo sí avisa otra vez.
    assert report(notificaciones_activas=True).status_code == 204
    devices = client.get("/api/v1/devices", headers=auth_headers).json()
    row = next(d for d in devices if d["id"] == verified["device_id"])
    assert row["notificaciones_activas"] is True

    assert report(notificaciones_activas=False).status_code == 204
    alerts = client.get("/api/v1/alerts?limit=50", headers=auth_headers).json()
    assert len([a for a in alerts if a["categoria"] == "acceso_desactivado"]) == 2

    # No molestar no genera alerta.
    assert report(notificaciones_activas=True, dnd=True).status_code == 204
    alerts = client.get("/api/v1/alerts?limit=50", headers=auth_headers).json()
    assert len([a for a in alerts if a["categoria"] == "acceso_desactivado"]) == 2
