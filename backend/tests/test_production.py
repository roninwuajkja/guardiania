import pytest

from app.config import Settings


def production_settings(**overrides):
    values = {
        "environment": "production",
        "public_base_url": "https://app.guardiania.cl",
        "jwt_private_key_pem": "-----BEGIN PRIVATE KEY-----\ntest\n-----END PRIVATE KEY-----",
        "pairing_pepper": "p" * 48,
        "audit_pepper": "a" * 48,
        "seed_demo": False,
        "database_url": "postgresql+asyncpg://user:pass@db:5432/guardiania",
        "cookie_secure": True,
        "cors_origins": "https://app.guardiania.cl",
        "trusted_hosts": "app.guardiania.cl",
        "docs_enabled": False,
        "email_delivery": "smtp",
        "smtp_host": "smtp.guardiania.cl",
        "require_email_verification": True,
    }
    values.update(overrides)
    return Settings(**values)


def test_production_settings_accept_hardened_configuration():
    production_settings().validate_runtime()


@pytest.mark.parametrize(
    ("override", "message"),
    [
        ({"public_base_url": "http://app.guardiania.cl"}, "HTTPS"),
        ({"database_url": "sqlite+aiosqlite:///./guardiania.db"}, "PostgreSQL"),
        ({"seed_demo": True}, "SEED_DEMO"),
        ({"pairing_pepper": "dev-pairing-pepper-change-me"}, "PAIRING_PEPPER"),
        ({"audit_pepper": "short"}, "AUDIT_PEPPER"),
        ({"trusted_hosts": "*"}, "TRUSTED_HOSTS"),
        ({"email_delivery": "console"}, "EMAIL_DELIVERY"),
        ({"smtp_starttls": False}, "SMTP_SSL"),
        ({"require_email_verification": False}, "REQUIRE_EMAIL_VERIFICATION"),
        ({"docs_enabled": True}, "DOCS_ENABLED"),
    ],
)
def test_production_settings_fail_closed(override, message):
    with pytest.raises(RuntimeError) as exc:
        production_settings(**override).validate_runtime()
    assert message in str(exc.value)


def test_legal_pages_are_served_with_security_headers(client):
    for path in (
        "/legal/privacidad",
        "/legal/terminos",
        "/legal/consentimiento",
        "/legal/seguridad",
        "/legal/retencion",
        "/recuperar",
        "/verificar-correo",
        "/restablecer",
        "/static/legal.js",
        "/static/app.css",
    ):
        response = client.get(path)
        assert response.status_code == 200, (path, response.text)
    page = client.get("/legal/privacidad")
    assert "text/html" in page.headers["content-type"]
    assert "Política de privacidad" in page.text
    csp = page.headers["content-security-policy"]
    assert "default-src 'self'" in csp
    assert "connect-src 'self' ws://127.0.0.1:8000" in csp
    assert "connect-src 'self' ws: wss:" not in csp
    assert page.headers["x-content-type-options"] == "nosniff"
    assert page.headers["referrer-policy"] == "strict-origin-when-cross-origin"
    assert "frame-ancestors 'none'" in page.headers["content-security-policy"]


def test_health_reports_production_hardened_version(client):
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["version"] == "1.7.9"
