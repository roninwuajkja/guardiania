import hashlib
import logging
import uuid
from dataclasses import dataclass
from datetime import timedelta

from fastapi import HTTPException, status
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import utcnow
from ..models import AccountSecurity, AuthToken, Rol, TutorSession, User
from ..security import (
    create_tutor_token,
    hash_password,
    new_opaque_token,
    new_refresh_token,
    verify_password,
)
from .mail import send_email

logger = logging.getLogger("guardiania.accounts")

_DUMMY_HASH = hash_password("dummy-password-not-used-for-login-2026")


@dataclass
class IssuedSession:
    user: User
    access_token: str
    refresh_token: str
    session: TutorSession
    security: AccountSecurity


async def get_security(db: AsyncSession, user: User) -> AccountSecurity:
    security = await db.get(AccountSecurity, user.id)
    if security is None:
        # Legacy rows created before the security table existed.
        security = AccountSecurity(
            usuario_id=user.id,
            token_version=0,
            email_verificado_en=utcnow(),
        )
        db.add(security)
        await db.flush()
    return security


async def create_account(
    db: AsyncSession,
    *,
    email: str,
    password: str,
    alias: str,
    consent: bool,
    guardian_declaration: bool,
) -> tuple[User, AccountSecurity]:
    user = User(
        rol=Rol.TUTOR.value,
        email=email,
        password_hash=hash_password(password),
        alias=alias.strip(),
        consentimiento_version=settings.legal_version,
        consentimiento_en=utcnow(),
    )
    db.add(user)
    await db.flush()
    security = AccountSecurity(
        usuario_id=user.id,
        token_version=0,
        email_verificado_en=None,
    )
    db.add(security)
    await db.flush()
    if consent:
        from .audit import record_consent

        await record_consent(
            db,
            tutor_id=user.id,
            tipo="terminos",
            version=settings.legal_version,
            detalle={"origen": "registro", "marco_legal": "Ley 21.719"},
        )
        await record_consent(
            db,
            tutor_id=user.id,
            tipo="privacidad",
            version=settings.legal_version,
            detalle={"origen": "registro", "marco_legal": "Ley 21.719"},
        )
    if guardian_declaration:
        from .audit import record_consent

        await record_consent(
            db,
            tutor_id=user.id,
            tipo="tutoria",
            version=settings.legal_version,
            detalle={
                "origen": "registro",
                "marco_legal": "Ley 21.719",
                "monitoreo_menor": "senales_derivadas",
                "contenido_conversaciones": False,
                "asentimiento_menor": "se_registra_al_vincular",
            },
        )
    return user, security


async def issue_session(
    db: AsyncSession,
    user: User,
    *,
    request=None,
) -> IssuedSession:
    from ..net import client_ip, user_agent
    from ..security import hash_ip, hash_user_agent

    security = await get_security(db, user)
    refresh_raw, refresh_hash = new_refresh_token()
    session = TutorSession(
        usuario_id=user.id,
        refresh_token_hash=refresh_hash,
        ip_hash=hash_ip(client_ip(request)) if request is not None else None,
        user_agent_hash=hash_user_agent(user_agent(request)) if request is not None else None,
        expira_en=utcnow() + timedelta(days=settings.tutor_refresh_days),
    )
    db.add(session)
    await db.flush()
    return IssuedSession(
        user=user,
        access_token=create_tutor_token(user.id, session.id, security.token_version),
        refresh_token=refresh_raw,
        session=session,
        security=security,
    )


async def authenticate(
    db: AsyncSession,
    *,
    email: str,
    password: str,
    request=None,
) -> User:
    from ..net import client_ip
    from ..ratelimit import enforce

    await enforce(f"auth:login:email:{email}", 12, 300)
    user = await db.scalar(
        select(User).where(User.email == email, User.eliminado_en.is_(None))
    )
    if user is None or not user.password_hash:
        # Keep the response generic and spend comparable time hashing.
        verify_password(password, _DUMMY_HASH)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Correo o contraseña incorrectos")

    security = await get_security(db, user)
    now = utcnow()
    if security.bloqueado_hasta is not None and security.bloqueado_hasta > now:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="La cuenta está bloqueada temporalmente. Intenta más tarde.",
        )
    if not verify_password(password, user.password_hash):
        security.intentos_fallidos += 1
        if security.intentos_fallidos >= 5:
            security.bloqueado_hasta = now + timedelta(minutes=15)
            security.intentos_fallidos = 0
        await db.commit()
        from .audit import record

        await record(
            db,
            "auth.login_failed",
            usuario_id=user.id,
            request=request,
            detalle={"intentos": security.intentos_fallidos},
            commit=True,
        )
        await enforce(f"auth:login:fail:ip:{client_ip(request)}", 30, 900)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Correo o contraseña incorrectos")

    security.intentos_fallidos = 0
    security.bloqueado_hasta = None
    if settings.require_email_verification and security.email_verificado_en is None:
        await db.commit()
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Debes verificar tu correo antes de entrar.",
        )
    await db.commit()
    from .audit import record

    await record(db, "auth.login", usuario_id=user.id, request=request, commit=True)
    return user


async def refresh_session(db: AsyncSession, raw_refresh: str, *, request=None) -> IssuedSession:
    from ..security import hash_refresh_token

    token_hash = hash_refresh_token(raw_refresh)
    session = await db.scalar(
        select(TutorSession).where(
            TutorSession.refresh_token_hash == token_hash,
            TutorSession.revocada_en.is_(None),
            TutorSession.expira_en > utcnow(),
        )
    )
    if session is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Sesión expirada")
    user = await db.scalar(
        select(User).where(User.id == session.usuario_id, User.eliminado_en.is_(None))
    )
    if user is None:
        session.revocada_en = utcnow()
        await db.commit()
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Sesión expirada")
    security = await get_security(db, user)
    new_raw, new_hash = new_refresh_token()
    now = utcnow()
    # Atomic compare-and-swap: two concurrent refreshes must not both rotate.
    rotated = await db.execute(
        update(TutorSession)
        .where(
            TutorSession.id == session.id,
            TutorSession.refresh_token_hash == token_hash,
            TutorSession.revocada_en.is_(None),
            TutorSession.expira_en > now,
        )
        .values(
            refresh_token_hash=new_hash,
            ultimo_uso_en=now,
            expira_en=now + timedelta(days=settings.tutor_refresh_days),
        )
    )
    if rotated.rowcount != 1:
        await db.rollback()
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Sesión expirada")
    session.refresh_token_hash = new_hash
    session.ultimo_uso_en = now
    session.expira_en = now + timedelta(days=settings.tutor_refresh_days)
    await db.commit()
    from .audit import record

    await record(db, "auth.refresh", usuario_id=user.id, request=request, commit=True)
    return IssuedSession(
        user=user,
        access_token=create_tutor_token(user.id, session.id, security.token_version),
        refresh_token=new_raw,
        session=session,
        security=security,
    )


async def revoke_session(db: AsyncSession, session_id: uuid.UUID, *, user_id: uuid.UUID | None = None) -> None:
    query = select(TutorSession).where(TutorSession.id == session_id)
    if user_id is not None:
        query = query.where(TutorSession.usuario_id == user_id)
    session = await db.scalar(query)
    if session is not None and session.revocada_en is None:
        session.revocada_en = utcnow()
        await db.commit()


async def revoke_all_sessions(
    db: AsyncSession,
    user: User,
    *,
    keep_current: uuid.UUID | None = None,
    bump_version: bool = True,
    password_changed: bool = False,
) -> None:
    query = select(TutorSession).where(
        TutorSession.usuario_id == user.id,
        TutorSession.revocada_en.is_(None),
    )
    if keep_current is not None:
        query = query.where(TutorSession.id != keep_current)
    sessions = (await db.execute(query)).scalars().all()
    now = utcnow()
    for session in sessions:
        session.revocada_en = now
    if bump_version or password_changed:
        security = await get_security(db, user)
        if bump_version:
            security.token_version += 1
        if password_changed:
            security.password_cambiada_en = now
    await db.commit()


async def create_auth_token(
    db: AsyncSession,
    *,
    user: User,
    purpose: str,
    ttl_hours: int = 24,
) -> str:
    raw, token_hash = new_opaque_token()
    db.add(
        AuthToken(
            usuario_id=user.id,
            proposito=purpose,
            token_hash=token_hash,
            expira_en=utcnow() + timedelta(hours=ttl_hours),
        )
    )
    await db.commit()
    return raw


async def consume_auth_token(
    db: AsyncSession,
    *,
    raw_token: str,
    purpose: str,
    ttl_invalid: bool = True,
) -> AuthToken:
    token_hash = hashlib.sha256(raw_token.encode()).hexdigest()
    token = await db.scalar(
        select(AuthToken).where(
            AuthToken.token_hash == token_hash,
            AuthToken.proposito == purpose,
            AuthToken.usado_en.is_(None),
            AuthToken.expira_en > utcnow(),
        )
    )
    if token is None:
        if ttl_invalid:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Token inválido o expirado")
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Token inválido")
    now = utcnow()
    # Consume once even if two requests race on the same link.
    consumed = await db.execute(
        update(AuthToken)
        .where(AuthToken.id == token.id, AuthToken.usado_en.is_(None), AuthToken.expira_en > now)
        .values(usado_en=now)
    )
    if consumed.rowcount != 1:
        await db.rollback()
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Token inválido o expirado")
    token.usado_en = now
    await db.commit()
    return token


async def send_verification_email(db: AsyncSession, user: User) -> None:
    raw = await create_auth_token(db, user=user, purpose="verify_email", ttl_hours=24)
    # Keep the token in the fragment so it never reaches server access logs or Referer.
    link = f"{settings.public_base_url.rstrip('/')}/verificar-correo#token={raw}"
    await send_email(
        user.email or "",
        "Verifica tu correo en IA Guardian",
        "Confirma tu correo para activar tu cuenta de tutor:\n\n"
        f"{link}\n\n"
        "Si no creaste esta cuenta, ignora este mensaje. El enlace vence en 24 horas.",
    )


async def verify_email(db: AsyncSession, raw_token: str) -> User:
    token = await consume_auth_token(db, raw_token=raw_token, purpose="verify_email")
    user = await db.get(User, token.usuario_id)
    if user is None or user.eliminado_en is not None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Token inválido")
    security = await get_security(db, user)
    security.email_verificado_en = utcnow()
    await db.commit()
    return user


async def send_password_reset(db: AsyncSession, user: User) -> None:
    raw = await create_auth_token(db, user=user, purpose="reset_password", ttl_hours=1)
    # Keep the token in the fragment so it never reaches server access logs or Referer.
    link = f"{settings.public_base_url.rstrip('/')}/restablecer#token={raw}"
    await send_email(
        user.email or "",
        "Restablece tu contraseña de IA Guardian",
        "Usa este enlace para crear una contraseña nueva. Vence en 1 hora:\n\n"
        f"{link}\n\n"
        "Si no lo solicitaste, ignora este mensaje y cambia tu contraseña si sospechas un acceso.",
    )


async def reset_password(db: AsyncSession, raw_token: str, new_password: str) -> User:
    token = await consume_auth_token(db, raw_token=raw_token, purpose="reset_password")
    user = await db.get(User, token.usuario_id)
    if user is None or user.eliminado_en is not None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Token inválido")
    user.password_hash = hash_password(new_password)
    await revoke_all_sessions(db, user, bump_version=True, password_changed=True)
    return user


async def mark_password_changed(db: AsyncSession, user: User) -> None:
    security = await get_security(db, user)
    security.password_cambiada_en = utcnow()
    security.token_version += 1
    await db.commit()
