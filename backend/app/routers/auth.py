import hmac
import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import get_db, utcnow
from ..deps import current_tutor
from ..models import AccountSecurity, Rol, TutorSession, User
from ..net import client_ip
from ..ratelimit import enforce
from ..schemas import (
    AuthResponse,
    ChangePasswordRequest,
    ForgotPasswordRequest,
    LoginRequest,
    RegisterPendingResponse,
    RegisterRequest,
    ResetPasswordRequest,
    TutorOut,
    TutorRefreshRequest,
    VerifyEmailRequest,
)
from ..security import (
    CSRF_COOKIE,
    access_cookie_name,
    clear_auth_cookies,
    create_tutor_token,
    decode_token,
    hash_password,
    new_csrf_token,
    refresh_cookie_name,
    set_auth_cookies,
    verify_password,
)
from ..services import accounts
from ..services.audit import record

router = APIRouter(prefix="/auth", tags=["Autenticación"])
logger = logging.getLogger("guardiania.auth")


def _tutor_out(user: User, security: AccountSecurity, session: TutorSession | None = None) -> TutorOut:
    return TutorOut(
        id=user.id,
        email=user.email or "",
        alias=user.alias,
        email_verified=security.email_verificado_en is not None,
        legal_version=settings.legal_version,
        session_id=session.id if session else None,
        session_expires_at=session.expira_en if session else None,
    )


def _set_session_cookies(response: Response, issued: accounts.IssuedSession) -> str:
    csrf = new_csrf_token()
    set_auth_cookies(
        response,
        issued.access_token,
        issued.refresh_token,
        csrf,
        access_max_age=settings.access_token_minutes * 60,
        refresh_max_age=settings.tutor_refresh_days * 86400,
    )
    return csrf


def _session_from_access(request: Request) -> uuid.UUID | None:
    token = request.headers.get("authorization", "")
    if token.lower().startswith("bearer "):
        token = token[7:].strip()
    else:
        token = request.cookies.get(access_cookie_name(), "")
    if not token:
        return None
    try:
        claims = decode_token(token, settings.jwt_audience)
        return uuid.UUID(claims["sid"])
    except (HTTPException, KeyError, ValueError):
        return None


def _check_cookie_csrf(request: Request) -> None:
    if request.headers.get("authorization", "").lower().startswith("bearer "):
        return
    header = request.headers.get("x-csrf-token", "")
    cookie = request.cookies.get(CSRF_COOKIE, "")
    if not header or not cookie or not hmac.compare_digest(header, cookie):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Solicitud rechazada por protección CSRF")


@router.get("/csrf", include_in_schema=False)
async def csrf_token(request: Request, response: Response):
    token = request.cookies.get(CSRF_COOKIE) or new_csrf_token()
    response.set_cookie(
        CSRF_COOKIE,
        token,
        max_age=settings.tutor_refresh_days * 86400,
        httponly=False,
        path="/",
        secure=settings.effective_cookie_secure,
        samesite=settings.cookie_samesite,
    )
    return {"csrf_token": token}


@router.post("/register", response_model=None, status_code=status.HTTP_201_CREATED)
async def register(body: RegisterRequest, request: Request, response: Response, db: AsyncSession = Depends(get_db)):
    await enforce(f"auth:register:ip:{client_ip(request)}", 5, 3600)
    existing = await db.scalar(select(User).where(User.email == body.email))
    if existing is not None:
        if settings.require_email_verification:
            return RegisterPendingResponse(
                message="Si el correo puede registrarse, recibirás un enlace de verificación."
            )
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Ya existe una cuenta con este correo")

    user, security = await accounts.create_account(
        db,
        email=body.email,
        password=body.password,
        alias=body.alias,
        consent=body.consent,
    )
    await record(db, "auth.register", usuario_id=user.id, request=request, detalle={"legal": settings.legal_version})

    if settings.require_email_verification:
        await db.commit()
        try:
            await accounts.send_verification_email(db, user)
        except Exception:  # pragma: no cover - depends on external SMTP
            logger.exception("No se pudo enviar el correo de verificación")
        response.status_code = status.HTTP_202_ACCEPTED
        return RegisterPendingResponse(
            message="Revisa tu correo para verificar la cuenta antes de entrar."
        )

    security.email_verificado_en = utcnow()
    issued = await accounts.issue_session(db, user, request=request)
    await db.commit()
    _set_session_cookies(response, issued)
    return AuthResponse(
        access_token=issued.access_token,
        refresh_token=issued.refresh_token,
        expires_in=settings.access_token_minutes * 60,
        tutor=_tutor_out(user, security, issued.session),
    )


@router.post("/login", response_model=AuthResponse)
async def login(body: LoginRequest, request: Request, response: Response, db: AsyncSession = Depends(get_db)):
    await enforce(f"auth:login:ip:{client_ip(request)}", 20, 300)
    user = await accounts.authenticate(db, email=body.email, password=body.password, request=request)
    security = await accounts.get_security(db, user)
    issued = await accounts.issue_session(db, user, request=request)
    await db.commit()
    _set_session_cookies(response, issued)
    return AuthResponse(
        access_token=issued.access_token,
        refresh_token=issued.refresh_token,
        expires_in=settings.access_token_minutes * 60,
        tutor=_tutor_out(user, security, issued.session),
    )


@router.post("/refresh", response_model=AuthResponse)
async def refresh(
    request: Request,
    response: Response,
    body: TutorRefreshRequest | None = None,
    db: AsyncSession = Depends(get_db),
):
    using_cookie = not (body and body.refresh_token)
    if using_cookie:
        _check_cookie_csrf(request)
    raw = body.refresh_token if body and body.refresh_token else request.cookies.get(refresh_cookie_name())
    if not raw:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Sesión expirada")
    issued = await accounts.refresh_session(db, raw, request=request)
    _set_session_cookies(response, issued)
    return AuthResponse(
        access_token=issued.access_token,
        refresh_token=issued.refresh_token,
        expires_in=settings.access_token_minutes * 60,
        tutor=_tutor_out(issued.user, issued.security, issued.session),
    )


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(
    request: Request,
    response: Response,
    body: TutorRefreshRequest | None = None,
    db: AsyncSession = Depends(get_db),
):
    has_browser_session = bool(
        request.cookies.get(access_cookie_name()) or request.cookies.get(refresh_cookie_name())
    )
    if (
        has_browser_session
        and not (body and body.refresh_token)
        and not request.headers.get("authorization", "").lower().startswith("bearer ")
    ):
        _check_cookie_csrf(request)
    session_id = _session_from_access(request)
    if session_id is None and body and body.refresh_token:
        from ..security import hash_refresh_token

        session = await db.scalar(
            select(TutorSession).where(TutorSession.refresh_token_hash == hash_refresh_token(body.refresh_token))
        )
        session_id = session.id if session else None
    if session_id is not None:
        await accounts.revoke_session(db, session_id)
        await record(db, "auth.logout", usuario_id=None, request=request, entidad="sesion", entidad_id=session_id, commit=True)
    clear_auth_cookies(response)
    response.status_code = status.HTTP_204_NO_CONTENT
    return response


@router.post("/logout-all", status_code=status.HTTP_204_NO_CONTENT)
async def logout_all(
    request: Request,
    response: Response,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    await accounts.revoke_all_sessions(db, tutor, bump_version=True)
    await record(db, "auth.logout_all", usuario_id=tutor.id, request=request, commit=True)
    clear_auth_cookies(response)
    response.status_code = status.HTTP_204_NO_CONTENT
    return response


@router.get("/me", response_model=TutorOut)
async def me(
    request: Request,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    security = await accounts.get_security(db, tutor)
    session_id = _session_from_access(request)
    session = await db.get(TutorSession, session_id) if session_id else None
    return _tutor_out(tutor, security, session)


@router.post("/verify-email", response_model=TutorOut)
async def verify_email(body: VerifyEmailRequest, request: Request, db: AsyncSession = Depends(get_db)):
    user = await accounts.verify_email(db, body.token)
    security = await accounts.get_security(db, user)
    await record(db, "auth.email_verified", usuario_id=user.id, request=request, commit=True)
    return _tutor_out(user, security)


@router.post("/verify-email/resend", status_code=status.HTTP_202_ACCEPTED)
async def resend_verification(body: ForgotPasswordRequest, request: Request, db: AsyncSession = Depends(get_db)):
    await enforce(f"auth:resend:ip:{client_ip(request)}", 5, 3600)
    user = await db.scalar(select(User).where(User.email == body.email, User.eliminado_en.is_(None)))
    if user is not None:
        security = await accounts.get_security(db, user)
        if security.email_verificado_en is None:
            await accounts.send_verification_email(db, user)
    return {"message": "Si corresponde, enviamos un nuevo enlace."}


@router.post("/password/forgot", status_code=status.HTTP_202_ACCEPTED)
async def forgot_password(body: ForgotPasswordRequest, request: Request, db: AsyncSession = Depends(get_db)):
    await enforce(f"auth:forgot:ip:{client_ip(request)}", 5, 3600)
    user = await db.scalar(select(User).where(User.email == body.email, User.eliminado_en.is_(None)))
    if user is not None:
        await accounts.send_password_reset(db, user)
    return {"message": "Si el correo existe, enviamos instrucciones."}


@router.post("/password/reset", status_code=status.HTTP_204_NO_CONTENT)
async def reset_password(body: ResetPasswordRequest, request: Request, response: Response, db: AsyncSession = Depends(get_db)):
    user = await accounts.reset_password(db, body.token, body.new_password)
    await record(db, "auth.password_reset", usuario_id=user.id, request=request, commit=True)
    clear_auth_cookies(response)
    response.status_code = status.HTTP_204_NO_CONTENT
    return response


@router.post("/password/change", status_code=status.HTTP_204_NO_CONTENT)
async def change_password(
    body: ChangePasswordRequest,
    request: Request,
    response: Response,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    if not tutor.password_hash or not verify_password(body.current_password, tutor.password_hash):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="La contraseña actual no es correcta")
    tutor.password_hash = hash_password(body.new_password)
    await accounts.revoke_all_sessions(db, tutor, bump_version=True, password_changed=True)
    await record(db, "auth.password_changed", usuario_id=tutor.id, request=request, commit=True)
    clear_auth_cookies(response)
    response.status_code = status.HTTP_204_NO_CONTENT
    return response
