import asyncio

from app.config import settings
from app.services import mail
from app.services.email_templates import password_reset_email


def test_brevo_payload_uses_sender_and_text(monkeypatch):
    monkeypatch.setattr(settings, "email_from", "IA Guardian <verified@example.com>")
    payload = mail.brevo_payload("tutor@example.com", "Asunto", "Cuerpo")
    assert payload == {
        "sender": {"name": "IA Guardian", "email": "verified@example.com"},
        "to": [{"email": "tutor@example.com"}],
        "subject": "Asunto",
        "textContent": "Cuerpo",
    }


def test_brevo_payload_includes_html(monkeypatch):
    monkeypatch.setattr(settings, "email_from", "IA Guardian <verified@example.com>")
    payload = mail.brevo_payload("tutor@example.com", "Asunto", "Cuerpo", "<p>Hola</p>")
    assert payload["htmlContent"] == "<p>Hola</p>"


def test_send_email_dispatches_to_brevo(monkeypatch):
    called = {}

    async def fake_send_brevo(to, subject, body, html=None):
        called.update({"to": to, "subject": subject, "body": body, "html": html})

    monkeypatch.setattr(settings, "email_delivery", "brevo")
    monkeypatch.setattr(mail, "_send_brevo", fake_send_brevo)

    asyncio.run(mail.send_email("tutor@example.com", "Asunto", "Cuerpo", html="<p>Hola</p>"))

    assert called == {
        "to": "tutor@example.com",
        "subject": "Asunto",
        "body": "Cuerpo",
        "html": "<p>Hola</p>",
    }


def test_password_reset_email_has_button_and_plain_text():
    link = "https://guardiania.cl/restablecer#token=abc123"
    subject, text, html = password_reset_email("Camila", link, "https://guardiania.cl")
    assert subject == "Restablece tu contraseña de IA Guardian"
    assert link in text
    assert link in html
    assert "Crear contraseña nueva" in html
    assert "vence en 1 hora" in html
    assert "Privacidad por diseño" in html
