import asyncio

from app.config import settings
from app.services import mail


def test_brevo_payload_uses_sender_and_text(monkeypatch):
    monkeypatch.setattr(settings, "email_from", "IA Guardian <verified@example.com>")
    payload = mail.brevo_payload("tutor@example.com", "Asunto", "Cuerpo")
    assert payload == {
        "sender": {"name": "IA Guardian", "email": "verified@example.com"},
        "to": [{"email": "tutor@example.com"}],
        "subject": "Asunto",
        "textContent": "Cuerpo",
    }


def test_send_email_dispatches_to_brevo(monkeypatch):
    called = {}

    async def fake_send_brevo(to, subject, body):
        called.update({"to": to, "subject": subject, "body": body})

    monkeypatch.setattr(settings, "email_delivery", "brevo")
    monkeypatch.setattr(mail, "_send_brevo", fake_send_brevo)

    asyncio.run(mail.send_email("tutor@example.com", "Asunto", "Cuerpo"))

    assert called == {"to": "tutor@example.com", "subject": "Asunto", "body": "Cuerpo"}
