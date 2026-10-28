import hashlib
import hmac
import secrets
import uuid
from datetime import timedelta
from pathlib import Path

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerificationError, VerifyMismatchError
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi import HTTPException, Response, status

from .config import settings
from .db import utcnow

_password_hasher = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=4)
_ALGORITHM = "EdDSA"


def hash_password(password: str) -> str:
    return _password_hasher.hash(password)


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return _password_hasher.verify(password_hash, password)
    except (VerifyMismatchError, VerificationError, ValueError):
        return False


def _load_private_key() -> Ed25519PrivateKey:
    if settings.jwt_private_key_pem:
        key = serialization.load_pem_private_key(settings.jwt_private_key_pem.encode(), password=None)
        return key  # type: ignore[return-value]
    key_path = Path(settings.keys_dir) / "ed25519.pem"
    if key_path.exists():
        key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
        return key  # type: ignore[return-value]
    key = Ed25519PrivateKey.generate()
    key_path.parent.mkdir(parents=True, exist_ok=True)
    key_path.write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    return key


_private_key = _load_private_key()
_public_key = _private_key.public_key()


def _encode(claims: dict) -> str:
    return jwt.encode(claims, _private_key, algorithm=_ALGORITHM, headers={"kid": settings.jwt_kid})


def _base_claims(audience: str, subject: str, scope: str, minutes: int, token_type: str) -> dict:
    now = utcnow()
    return {
        "iss": settings.jwt_issuer,
        "sub": subject,
        "aud": audience,
        "iat": int(now.timestamp()),
        "nbf": int((now - timedelta(seconds=5)).timestamp()),
        "exp": int((now + timedelta(minutes=minutes)).timestamp()),
        "jti": str(uuid.uuid4()),
        "scope": scope,
        "typ": token_type,
    }


def create_tutor_token(user_id: uuid.UUID, session_id: uuid.UUID, token_version: int) -> str:
    claims = _base_claims(
        settings.jwt_audience,
        str(user_id),
        "tutor:read tutor:write",
        settings.access_token_minutes,
        "tutor",
    )
    claims["sid"] = str(session_id)
    claims["ver"] = token_version
    return _encode(claims)


def create_pairing_token(tutor_id: uuid.UUID, session_id: uuid.UUID, ttl_seconds: int) -> str:
    claims = _base_claims(
        settings.pairing_audience,
        str(tutor_id),
        "pairing:verify",
        max(1, ttl_seconds // 60),
        "pairing",
    )
    claims["exp"] = int((utcnow() + timedelta(seconds=ttl_seconds)).timestamp())
    claims["jti"] = str(session_id)
    claims["ver"] = 1
    claims["dev"] = "web-console"
    return _encode(claims)


def create_device_token(device_id: uuid.UUID, binding_id: uuid.UUID) -> str:
    claims = _base_claims(
        settings.jwt_audience,
        str(device_id),
        "captures:write device:self",
        settings.device_token_minutes,
        "device",
    )
    claims["vin"] = str(binding_id)
    return _encode(claims)


def create_ws_ticket(session_id: uuid.UUID, tutor_id: uuid.UUID) -> str:
    claims = _base_claims(
        settings.jwt_audience,
        str(tutor_id),
        "pairing:subscribe",
        1,
        "ws_ticket",
    )
    claims["exp"] = int((utcnow() + timedelta(seconds=settings.ws_ticket_seconds)).timestamp())
    claims["sid"] = str(session_id)
    return _encode(claims)


def create_alert_ws_ticket(tutor_id: uuid.UUID) -> str:
    """Short-lived ticket for the tutor alert stream.

    Tickets travel in the WebSocket subprotocol header, never in the URL, and
    carry only a scope: they cannot be replayed against the REST API.
    """

    claims = _base_claims(
        settings.jwt_audience,
        str(tutor_id),
        "alerts:subscribe",
        1,
        "ws_ticket",
    )
    claims["exp"] = int((utcnow() + timedelta(seconds=settings.ws_ticket_seconds)).timestamp())
    return _encode(claims)


def decode_token(token: str, audience: str) -> dict:
    try:
        return jwt.decode(
            token,
            _public_key,
            algorithms=[_ALGORITHM],
            audience=audience,
            issuer=settings.jwt_issuer,
            options={"require": ["exp", "iat", "iss", "aud", "sub"]},
        )
    except jwt.PyJWTError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token inválido o expirado") from exc


def new_refresh_token() -> tuple[str, str]:
    token = secrets.token_urlsafe(48)
    return token, hash_refresh_token(token)


def hash_refresh_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def generate_pairing_code(length: int | None = None) -> str:
    size = length or settings.pairing_code_length
    return str(secrets.randbelow(10**size)).zfill(size)


def hash_pairing_code(code: str) -> str:
    return hmac.new(settings.pairing_pepper.encode(), code.encode(), hashlib.sha256).hexdigest()


def hash_install_id(install_id: str) -> str:
    return hmac.new(settings.pairing_pepper.encode(), install_id.encode(), hashlib.sha256).hexdigest()


def format_manual_code(code: str) -> str:
    if len(code) == 6:
        return f"{code[:3]} {code[3:]}"
    return code


def hash_ip(value: str) -> str:
    return hmac.new(settings.audit_pepper.encode(), value.encode(), hashlib.sha256).hexdigest()


def hash_user_agent(value: str) -> str:
    return hmac.new(settings.audit_pepper.encode(), value.encode(), hashlib.sha256).hexdigest()


def new_opaque_token() -> tuple[str, str]:
    token = secrets.token_urlsafe(40)
    return token, hashlib.sha256(token.encode()).hexdigest()


def new_csrf_token() -> str:
    return secrets.token_urlsafe(32)


ACCESS_COOKIE = "guardiania_access"
REFRESH_COOKIE = "guardiania_refresh"
CSRF_COOKIE = "guardiania_csrf"


def access_cookie_name() -> str:
    return f"__Host-{ACCESS_COOKIE}" if settings.effective_cookie_secure else ACCESS_COOKIE


def refresh_cookie_name() -> str:
    return f"__Host-{REFRESH_COOKIE}" if settings.effective_cookie_secure else REFRESH_COOKIE


def _cookie_kwargs() -> dict:
    kwargs = {
        "path": "/",
        "secure": settings.effective_cookie_secure,
        "samesite": settings.cookie_samesite,
    }
    # __Host- cookies must not carry a Domain attribute.
    if settings.cookie_domain and not settings.effective_cookie_secure:
        kwargs["domain"] = settings.cookie_domain
    return kwargs


def set_auth_cookies(
    response: Response,
    access_token: str,
    refresh_token: str,
    csrf_token: str,
    *,
    access_max_age: int,
    refresh_max_age: int,
) -> None:
    base = _cookie_kwargs()
    response.set_cookie(
        access_cookie_name(),
        access_token,
        max_age=access_max_age,
        httponly=True,
        **base,
    )
    response.set_cookie(
        refresh_cookie_name(),
        refresh_token,
        max_age=refresh_max_age,
        httponly=True,
        **base,
    )
    response.set_cookie(
        CSRF_COOKIE,
        csrf_token,
        max_age=refresh_max_age,
        httponly=False,
        **base,
    )


def clear_auth_cookies(response: Response) -> None:
    base = _cookie_kwargs()
    response.delete_cookie(access_cookie_name(), path="/", secure=base["secure"], samesite=base["samesite"])
    response.delete_cookie(refresh_cookie_name(), path="/", secure=base["secure"], samesite=base["samesite"])
    response.delete_cookie(CSRF_COOKIE, path="/", secure=base["secure"], samesite=base["samesite"])
