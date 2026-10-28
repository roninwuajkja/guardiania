from fastapi import Request

from .config import settings


def client_ip(request: Request | None) -> str:
    """Return the real client address only when the reverse proxy is trusted.

    The proxy must overwrite (not append to) untrusted X-Forwarded-For values.
    """
    if request is None:
        return "unknown"
    if settings.trust_proxy_headers:
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            return forwarded.split(",", 1)[0].strip()[:64] or "unknown"
    return request.client.host if request.client else "unknown"


def user_agent(request: Request | None) -> str:
    if request is None:
        return ""
    return request.headers.get("user-agent", "")[:512]
