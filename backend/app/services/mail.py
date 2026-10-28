import asyncio
import logging
import smtplib
import ssl
from email.message import EmailMessage

from ..config import settings

logger = logging.getLogger("guardiania.mail")


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
    await asyncio.to_thread(_send_sync, to, subject, body)
