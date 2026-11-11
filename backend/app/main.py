import asyncio
import hashlib
import logging
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import urlsplit

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.trustedhost import TrustedHostMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from .config import settings
from .db import SessionFactory, init_db
from .routers import account, auth, captures, devices, maintenance, pairing, panel, realtime, ws
from .runtime import dispatcher
from .services.pairing import expire_stale_pairings
from .services.retention import run_retention

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger("guardiania")
WEB_DIR = Path(__file__).resolve().parent / "web"

_PUBLIC_URL = urlsplit(settings.public_base_url)
_WS_ORIGIN = (
    f"{'wss' if _PUBLIC_URL.scheme == 'https' else 'ws'}://{_PUBLIC_URL.netloc}"
    if _PUBLIC_URL.netloc
    else ""
)
CONNECT_SRC = "'self'" + (f" {_WS_ORIGIN}" if _WS_ORIGIN else "")


async def _expiry_loop() -> None:
    while True:
        try:
            async with SessionFactory() as db:
                expired = await expire_stale_pairings(db)
            if expired:
                await dispatcher.dispatch_once()
        except asyncio.CancelledError:
            raise
        except Exception:  # pragma: no cover - resilience loop
            logger.exception("pairing expiry failed")
        await asyncio.sleep(30)


async def _retention_loop() -> None:
    while True:
        try:
            async with SessionFactory() as db:
                await run_retention(db)
        except asyncio.CancelledError:
            raise
        except Exception:  # pragma: no cover - resilience loop
            logger.exception("retention job failed")
        await asyncio.sleep(6 * 60 * 60)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    await init_db()
    if settings.seed_demo:
        from .seed import run_seed

        await run_seed()
    tasks = [
        asyncio.create_task(dispatcher.run_forever(), name="outbox-dispatcher"),
        asyncio.create_task(_expiry_loop(), name="pairing-expiry"),
        asyncio.create_task(_retention_loop(), name="retention"),
    ]
    logger.info("IA Guardian listo en entorno %s", settings.environment)
    try:
        yield
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


class RequestContextMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        request_id = str(uuid.uuid4())
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers.setdefault("X-Request-ID", request_id)
        return response


class RequestSizeLimitMiddleware:
    def __init__(self, app: ASGIApp, max_bytes: int) -> None:
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = dict(scope.get("headers", []))
        raw_length = headers.get(b"content-length")
        if raw_length:
            try:
                if int(raw_length) > self.max_bytes:
                    await self._reject(send)
                    return
            except ValueError:
                await self._reject(send)
                return

        body = bytearray()
        while True:
            message = await receive()
            if message["type"] != "http.request":
                break
            body.extend(message.get("body", b""))
            if len(body) > self.max_bytes:
                await self._reject(send)
                return
            if not message.get("more_body", False):
                break

        replayed = False
        buffered = bytes(body)

        async def replay_receive() -> Message:
            nonlocal replayed
            if not replayed:
                replayed = True
                return {"type": "http.request", "body": buffered, "more_body": False}
            return await receive()

        await self.app(scope, replay_receive, send)

    @staticmethod
    async def _reject(send: Send) -> None:
        body = b'{"detail":"Solicitud demasiado grande"}'
        await send(
            {
                "type": "http.response.start",
                "status": 413,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(body)).encode()),
                ],
            }
        )
        await send({"type": "http.response.body", "body": body})


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
        response.headers.setdefault("X-Frame-Options", "DENY")
        response.headers.setdefault("Cross-Origin-Opener-Policy", "same-origin")
        response.headers.setdefault("Cross-Origin-Resource-Policy", "same-origin")
        response.headers.setdefault(
            "Permissions-Policy",
            "camera=(self), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()",
        )
        response.headers.setdefault(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
            f"font-src 'self'; script-src 'self'; connect-src {CONNECT_SRC}; "
            "base-uri 'none'; object-src 'none'; form-action 'self'; frame-ancestors 'none'",
        )
        if settings.is_production:
            response.headers.setdefault(
                "Strict-Transport-Security",
                "max-age=63072000; includeSubDomains; preload",
            )
        if request.url.path.startswith("/api/") or request.url.path.startswith("/auth/"):
            response.headers.setdefault("Cache-Control", "no-store")
        elif request.url.path.startswith("/static/"):
            # El panel se actualiza seguido: se revalida siempre para que un
            # despliegue no deje una pestaña con JS viejo durante una hora.
            response.headers.setdefault("Cache-Control", "no-cache")
        return response


app = FastAPI(
    title="IA Guardian API",
    description="Control parental con privacidad por diseño: vinculación multidispositivo y evaluación de riesgo con IA.",
    version="1.7.16",
    lifespan=lifespan,
    docs_url=None if settings.is_production or not settings.docs_enabled else "/docs",
    redoc_url=None if settings.is_production or not settings.docs_enabled else "/redoc",
    openapi_url=None if settings.is_production or not settings.docs_enabled else "/openapi.json",
)

app.add_middleware(RequestSizeLimitMiddleware, max_bytes=settings.max_request_bytes)
app.add_middleware(SecurityHeadersMiddleware)
app.add_middleware(RequestContextMiddleware)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "Idempotency-Key", "X-CSRF-Token", "X-Request-ID"],
)
if settings.trusted_host_list and "*" not in settings.trusted_host_list:
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=settings.trusted_host_list)

app.include_router(auth.router, prefix="/api/v1")
app.include_router(account.router, prefix="/api/v1")
app.include_router(pairing.router, prefix="/api/v1")
app.include_router(captures.router, prefix="/api/v1")
app.include_router(devices.router, prefix="/api/v1")
app.include_router(panel.router, prefix="/api/v1")
app.include_router(realtime.router, prefix="/api/v1")
app.include_router(maintenance.router, prefix="/api/v1")
app.include_router(ws.router)

if WEB_DIR.exists():
    app.mount("/static", StaticFiles(directory=WEB_DIR), name="static")


@app.exception_handler(Exception)
async def unhandled_exception(request: Request, exc: Exception):
    request_id = getattr(request.state, "request_id", "unknown")
    logger.exception("Error no controlado [%s] %s %s", request_id, request.method, request.url.path)
    return JSONResponse(
        status_code=500,
        content={"detail": "Ocurrió un error interno.", "request_id": request_id},
        headers={"X-Request-ID": request_id},
    )


@app.get("/api/v1/health", tags=["Salud"])
async def health():
    return {"status": "ok", "service": "ia-guardian", "version": app.version}


@app.get("/health", include_in_schema=False)
async def health_alias():
    """Alias for load balancers and local monitors."""
    return await health()


_ASSET_VERSION: str | None = None


def _asset_version() -> str:
    """Versión de los estáticos: cambia cuando cambia el panel desplegado."""

    global _ASSET_VERSION
    if _ASSET_VERSION is None:
        digest = hashlib.sha1()
        for name in ("app.js", "app.css", "fonts.css"):
            path = WEB_DIR / name
            if path.exists():
                digest.update(path.read_bytes())
        _ASSET_VERSION = digest.hexdigest()[:12]
    return _ASSET_VERSION


def _index_html() -> str:
    """index.html con los estáticos versionados para saltarse cachés viejas."""

    version = _asset_version()
    html = (WEB_DIR / "index.html").read_text(encoding="utf-8")
    for name in ("app.js", "app.css", "fonts.css"):
        html = html.replace(f'/static/{name}', f'/static/{name}?v={version}')
    return html


@app.get("/", include_in_schema=False)
async def index():
    return HTMLResponse(_index_html(), headers={"Cache-Control": "no-store"})


LEGAL_PAGES = {
    "privacidad": "privacidad.html",
    "terminos": "terminos.html",
    "consentimiento": "consentimiento.html",
    "seguridad": "seguridad.html",
    "retencion": "retencion.html",
}


@app.get("/legal/{page}", include_in_schema=False)
async def legal(page: str):
    filename = LEGAL_PAGES.get(page)
    if filename is None:
        return JSONResponse(status_code=404, content={"detail": "Documento no encontrado"})
    return FileResponse(WEB_DIR / "legal" / filename, media_type="text/html; charset=utf-8")


@app.get("/recuperar", include_in_schema=False)
async def recover_access_page():
    return FileResponse(WEB_DIR / "legal" / "recuperar.html", media_type="text/html; charset=utf-8")


@app.get("/verificar-correo", include_in_schema=False)
async def verify_email_page():
    return FileResponse(WEB_DIR / "legal" / "verificar-correo.html", media_type="text/html; charset=utf-8")


@app.get("/restablecer", include_in_schema=False)
async def reset_password_page():
    return FileResponse(WEB_DIR / "legal" / "restablecer.html", media_type="text/html; charset=utf-8")


@app.get("/favicon.svg", include_in_schema=False)
async def favicon():
    return FileResponse(WEB_DIR / "favicon.svg", media_type="image/svg+xml")


@app.get("/apple-touch-icon.png", include_in_schema=False)
async def apple_touch_icon():
    return FileResponse(WEB_DIR / "apple-touch-icon.png", media_type="image/png")
