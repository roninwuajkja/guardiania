import hmac
import uuid
from dataclasses import dataclass

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .config import settings
from .db import get_db, utcnow
from .models import AccountSecurity, Binding, Device, TutorSession, User
from .security import CSRF_COOKIE, access_cookie_name, decode_token

_bearer = HTTPBearer(auto_error=False)


def _credentials_error(detail: str = "No autenticado") -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail=detail,
        headers={"WWW-Authenticate": "Bearer"},
    )


def _csrf_error() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail="Solicitud rechazada por protección CSRF",
    )


async def current_tutor(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    db: AsyncSession = Depends(get_db),
) -> User:
    using_cookie = credentials is None
    token = credentials.credentials if credentials is not None else request.cookies.get(access_cookie_name())
    if not token:
        raise _credentials_error()
    if using_cookie and request.method not in {"GET", "HEAD", "OPTIONS"}:
        header = request.headers.get("x-csrf-token", "")
        cookie = request.cookies.get(CSRF_COOKIE, "")
        if not header or not cookie or not hmac.compare_digest(header, cookie):
            raise _csrf_error()

    claims = decode_token(token, settings.jwt_audience)
    if claims.get("typ") != "tutor":
        raise _credentials_error("Tipo de token inválido")
    try:
        user_id = uuid.UUID(claims["sub"])
        session_id = uuid.UUID(claims["sid"])
    except (KeyError, ValueError) as exc:
        raise _credentials_error() from exc

    session = await db.get(TutorSession, session_id)
    if (
        session is None
        or session.usuario_id != user_id
        or session.revocada_en is not None
        or session.expira_en <= utcnow()
    ):
        raise _credentials_error("Sesión revocada o expirada")
    security = await db.get(AccountSecurity, user_id)
    token_version = security.token_version if security is not None else 0
    if int(claims.get("ver", 0)) != token_version:
        raise _credentials_error("Sesión invalidada")

    user = await db.scalar(select(User).where(User.id == user_id, User.eliminado_en.is_(None)))
    if user is None:
        raise _credentials_error("Cuenta no encontrada")
    return user


@dataclass
class DeviceContext:
    device: Device
    binding: Binding


async def current_device(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    db: AsyncSession = Depends(get_db),
) -> DeviceContext:
    if credentials is None:
        raise _credentials_error()
    claims = decode_token(credentials.credentials, settings.jwt_audience)
    if claims.get("typ") != "device":
        raise _credentials_error("Tipo de token inválido")
    try:
        device_id = uuid.UUID(claims["sub"])
        binding_id = uuid.UUID(claims["vin"])
    except (KeyError, ValueError) as exc:
        raise _credentials_error() from exc

    device = await db.get(Device, device_id)
    binding = await db.get(Binding, binding_id)
    if device is None or device.estado != "ACTIVO":
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="Dispositivo revocado")
    if binding is None or binding.estado != "ACTIVO" or binding.dispositivo_id != device.id:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="Vinculación revocada o expirada")
    return DeviceContext(device=device, binding=binding)
