import asyncio
import logging
import smtplib
import ssl
from email.message import EmailMessage
from email.utils import parseaddr

import httpx

from ..config import settings

logger = logging.getLogger("guardiania.mail")


def _sender_parts() -> tuple[str, str]:
    name, address = parseaddr(settings.email_from)
    return name or "IA Guardian", address


def brevo_payload(to: str, subject: str, body: str) -> dict:
    name, address = _sender_parts()
    return {
        "sender": {"name": name, "email": address},
        "to": [{"email": to}],
        "subject": subject,
        "textContent": body,
    }


async def _send_brevo(to: str, subject: str, body: str) -> None:
    if not settings.brevo_api_key:
        raise RuntimeError("BREVO_API_KEY no configurada")
    async with httpx.AsyncClient(timeout=15.0) as client:
        response = await client.post(
            settings.brevo_api_url,
            json=brevo_payload(to, subject, body),
            headers={"api-key": settings.brevo_api_key, "accept": "application/json"},
        )
        if response.is_error:
            logger.error("Brevo rechazó el correo con estado %s", response.status_code)
            response.raise_for_status()
    logger.info("Brevo aceptó el correo con estado %s", response.status_code)


def _send_sync(to: str, subject: str, body: str) -> None:
    message = EmailMessage()
    message["From"] = settings.email_from
    message["To"] = to
    message["Subject"] = subject
    message.set_content(body)
    context = ssl.create_default_context()
    host = settings.smtp_host or "localhost"
    if settings.smtp_ssl:
        smtp_client = smtplib.SMTP_SSL(host, settings.smtp_port, timeout=20, context=context)
    else:
        smtp_client = smtplib.SMTP(host, settings.smtp_port, timeout=20)
    with smtp_client as smtp:
        if settings.smtp_starttls and not settings.smtp_ssl:
            smtp.starttls(context=context)
            smtp.ehlo()
        if settings.smtp_user and settings.smtp_password:
            smtp.login(settings.smtp_user, settings.smtp_password)
        smtp.send_message(message)


async def send_email(to: str, subject: str, body: str) -> None:
    if settings.email_delivery == "console":
        logger.info("CORREO (modo consola) para %s | %s\n%s", to, subject, body)
        return
    if settings.email_delivery == "brevo":
        await _send_brevo(to, subject, body)
        return
    await asyncio.to_thread(_send_sync, to, subject, body)
