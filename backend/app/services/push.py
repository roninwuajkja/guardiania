"""Cliente FCM HTTP v1 sin ``firebase-admin``.

Motivo: el proyecto ya depende de PyJWT + cryptography; agregar firebase-admin
implicaría otra cadena de dependencias (gRPC) solo para firmar un JWT y hacer un
POST. Este cliente hace exactamente eso y se apaga solo si no hay credenciales.

Privacidad: el payload incluye ``alert_id``, severidad, categoría, alias y el
resumen **derivado** de la alerta (nunca el texto, nombres de contactos ni el
contenido que originó la señal).
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx
import jwt

from ..config import settings

logger = logging.getLogger("guardiania.push")

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
_REQUIRED_FIELDS = ("project_id", "client_email", "private_key", "token_uri")

_SEVERITY_LABELS = {
    "BAJA": "baja",
    "MEDIA": "media",
    "ALTA": "alta",
    "CRITICA": "crítica",
}

_CATEGORY_LABELS = {
    "self_harm": "Posible autolesión",
    "grooming": "Posible grooming",
    "sexual_content": "Contenido sexual",
    "sexual_harassment": "Acoso sexual",
    "violence": "Violencia",
    "threat": "Amenaza",
    "coercion": "Presión o chantaje",
    "bullying": "Acoso escolar",
    "hate_speech": "Discurso de odio",
    "insult_severe": "Insulto grave",
    "insult_moderate": "Insulto moderado",
    "insult_mild": "Insulto leve",
    "eating_disorder": "Trastorno alimentario",
    "doxxing": "Datos expuestos",
    "stalking": "Acecho",
    "runaway": "Riesgo de fuga",
    "dangerous_challenge": "Reto peligroso",
    "drugs": "Drogas o alcohol",
    "gambling": "Apuestas",
    "malestar": "Malestar emocional",
    "scam_points": "Canje de puntos",
    "scam_casino": "Invitación a casino",
    "scam_financial": "Inversión sospechosa",
    "scam_account": "Phishing o suplantación",
    "scam_prize": "Premio sospechoso",
    "scam_delivery": "Envío sospechoso",
    "scam_job": "Trabajo sospechoso",
    "scam_romance": "Estafa romántica",
    "scam": "Posible estafa",
    "unknown_contact": "Contacto desconocido",
    "uso_nocturno": "Uso nocturno",
    "uso_prolongado": "Uso prolongado",
    "contacto_recurrente": "Contacto recurrente",
    "sin_senal": "Teléfono sin señal",
    "acceso_desactivado": "Acceso a notificaciones desactivado",
}


@dataclass(frozen=True)
class FirebaseCredentials:
    project_id: str
    client_email: str
    private_key: str
    token_uri: str


@dataclass(frozen=True)
class PushResult:
    sent: int = 0
    failed: int = 0
    invalid_tokens: tuple[str, ...] = ()

    @property
    def attempted(self) -> int:
        return self.sent + self.failed


def load_credentials(path: Path | str) -> FirebaseCredentials:
    """Lee y valida una cuenta de servicio. Los errores nunca incluyen claves."""

    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("La cuenta de servicio debe ser un objeto JSON")
    missing = [field for field in _REQUIRED_FIELDS if not data.get(field)]
    if missing:
        raise ValueError(f"Faltan campos en la cuenta de servicio: {', '.join(missing)}")
    if "private_key" in data and "BEGIN PRIVATE KEY" not in str(data["private_key"]):
        raise ValueError("private_key no parece una clave PEM PKCS#8")
    return FirebaseCredentials(
        project_id=str(data["project_id"]),
        client_email=str(data["client_email"]),
        private_key=str(data["private_key"]),
        token_uri=str(data["token_uri"]),
    )


def token_fingerprint(token: str) -> str:
    """Identificador estable para logs, sin exponer el token completo."""

    return hashlib.sha256(token.encode()).hexdigest()[:10]


def _stringify(data: dict[str, Any]) -> dict[str, str]:
    return {key: str(value) for key, value in data.items() if value is not None}


class FirebasePushService:
    def __init__(self) -> None:
        self._credentials: FirebaseCredentials | None = None
        self._credentials_loaded = False
        self._access_token: str | None = None
        self._access_expires_at = 0.0
        self._lock = asyncio.Lock()

    @property
    def enabled(self) -> bool:
        if not settings.fcm_enabled:
            return False
        return settings.firebase_credentials_file.exists()

    def _load(self) -> FirebaseCredentials | None:
        if self._credentials_loaded:
            return self._credentials
        self._credentials_loaded = True
        if not settings.fcm_enabled:
            return None
        path = settings.firebase_credentials_file
        if not path.exists():
            logger.warning("fcm_disabled reason=credentials_missing")
            return None
        try:
            self._credentials = load_credentials(path)
            logger.info("fcm_ready project=%s", self._credentials.project_id)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            logger.warning("fcm_disabled reason=credentials_invalid error=%s", type(exc).__name__)
            self._credentials = None
        return self._credentials

    async def _access(self) -> str | None:
        credentials = self._load()
        if credentials is None:
            return None
        now = time.time()
        if self._access_token and now < self._access_expires_at - 60:
            return self._access_token
        async with self._lock:
            now = time.time()
            if self._access_token and now < self._access_expires_at - 60:
                return self._access_token
            claims = {
                "iss": credentials.client_email,
                "scope": FCM_SCOPE,
                "aud": credentials.token_uri,
                "iat": int(now),
                "exp": int(now + 3600),
            }
            assertion = jwt.encode(claims, credentials.private_key, algorithm="RS256")
            try:
                async with httpx.AsyncClient(timeout=min(5.0, settings.fcm_timeout_ms / 1000)) as client:
                    response = await client.post(
                        credentials.token_uri,
                        data={
                            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                            "assertion": assertion,
                        },
                    )
            except httpx.HTTPError as exc:
                logger.warning("fcm_oauth_failed error=%s", type(exc).__name__)
                return None
            if response.status_code >= 400:
                logger.warning("fcm_oauth_status status=%d", response.status_code)
                return None
            try:
                payload = response.json()
                self._access_token = str(payload["access_token"])
                self._access_expires_at = now + int(payload.get("expires_in", 3600))
            except (KeyError, TypeError, ValueError):
                logger.warning("fcm_oauth_invalid_response")
                return None
            return self._access_token

    async def send(
        self,
        tokens: list[str],
        *,
        data: dict[str, Any],
    ) -> PushResult:
        if not tokens or not self.enabled:
            return PushResult()
        credentials = self._load()
        access = await self._access()
        if credentials is None or access is None:
            return PushResult(failed=len(tokens))

        payload_data = _stringify(data)
        severity = str(data.get("severidad") or "").upper()
        alias = str(data.get("device_alias") or "el teléfono")
        is_test = str(data.get("tipo") or "") == "test"
        if is_test:
            title = "IA Guardian · Prueba"
            body = "Si ves esto, las notificaciones push funcionan."
        else:
            category = str(data.get("categoria") or "").lower()
            category_label = _CATEGORY_LABELS.get(category, "Señal de riesgo")
            severity_label = _SEVERITY_LABELS.get(severity, "de riesgo")
            summary = str(data.get("resumen") or "").strip()
            title = f"IA Guardian · {category_label}"
            body = f"Señal {severity_label} · {alias}"
            if summary:
                body = f"{body}: {summary}"
        if not is_test:
            payload_data.setdefault("open_alerts", "1")
        semaphore = asyncio.Semaphore(5)

        async def send_one(token: str) -> tuple[bool, bool]:
            # data + notification: el sistema pinta la notificación sin despertar
            # la app (llega en segundos aunque esté cerrada); el data permite
            # abrir el detalle y deduplicar contra el WebSocket si está abierta.
            message = {
                "message": {
                    "token": token,
                    "data": payload_data,
                    "notification": {"title": title, "body": body},
                    "android": {
                        "priority": "high",
                        "ttl": "300s",
                        "notification": {
                            "channel_id": "guardiania_alerts",
                            "sound": "default",
                        },
                    },
                }
            }
            async with semaphore:
                try:
                    async with httpx.AsyncClient(timeout=settings.fcm_timeout_ms / 1000) as client:
                        response = await client.post(
                            f"https://fcm.googleapis.com/v1/projects/{credentials.project_id}/messages:send",
                            json=message,
                            headers={"Authorization": f"Bearer {access}"},
                        )
                except httpx.HTTPError as exc:
                    logger.warning("fcm_send_failed token=%s error=%s", token_fingerprint(token), type(exc).__name__)
                    return False, False
            if response.status_code < 300:
                return True, False
            invalid = False
            error_status = None
            try:
                error = response.json().get("error", {})
                error_status = error.get("status")
                for detail in error.get("details", []):
                    if detail.get("errorCode") == "UNREGISTERED":
                        invalid = True
                # Solo se da de baja si Google dice que el token ya no existe.
                # Un INVALID_ARGUMENT de payload no invalida el token.
                if error_status == "NOT_FOUND":
                    invalid = True
                if error_status == "INVALID_ARGUMENT" and "registration token" in str(error.get("message", "")):
                    invalid = True
            except (TypeError, ValueError):
                pass
            logger.warning(
                "fcm_send_status token=%s status=%d error=%s invalid=%s",
                token_fingerprint(token),
                response.status_code,
                error_status,
                invalid,
            )
            return False, invalid

        results = await asyncio.gather(*(send_one(token) for token in tokens))
        sent = sum(1 for ok, _ in results if ok)
        invalid = tuple(token for token, (_, bad) in zip(tokens, results) if bad)
        return PushResult(sent=sent, failed=len(tokens) - sent, invalid_tokens=invalid)

    def reset_for_tests(self) -> None:
        self._credentials = None
        self._credentials_loaded = False
        self._access_token = None
        self._access_expires_at = 0.0


push_service = FirebasePushService()
