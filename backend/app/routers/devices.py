from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import get_db, utcnow
from ..deps import DeviceContext, current_device
from ..models import Binding, Device, EstadoVinculacion
from ..ratelimit import enforce
from ..schemas import DeviceRefreshResponse, RefreshRequest, UnlinkSelfRequest
from ..security import create_device_token, hash_refresh_token
from ..services.unlink import unlink_from_device

router = APIRouter(prefix="/devices", tags=["Dispositivos"])


def _invalid() -> HTTPException:
    return HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token de actualización inválido")


@router.post("/refresh", response_model=DeviceRefreshResponse)
async def refresh_device(
    body: RefreshRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
):
    """Exchange a long-lived refresh token for a short-lived device token.

    The refresh token is deliberately not rotated: losing the response would
    leave the phone unable to sync until a new pairing. Revocation on the
    tutor panel invalidates it immediately.
    """
    token_hash = hash_refresh_token(body.refresh_token)
    client = request.client.host if request.client else "unknown"
    await enforce(f"devices:refresh:ip:{client}", 60, 3600)
    await enforce(f"devices:refresh:{token_hash[:16]}", 30, 3600)

    now = utcnow()
    device = await db.scalar(select(Device).where(Device.refresh_token_hash == token_hash))
    if (
        device is None
        or device.estado != "ACTIVO"
        or device.refresh_expira_en is None
        or device.refresh_expira_en <= now
    ):
        raise _invalid()

    binding = await db.scalar(
        select(Binding).where(
            Binding.dispositivo_id == device.id,
            Binding.estado == EstadoVinculacion.ACTIVO.value,
        )
    )
    if binding is None:
        raise _invalid()

    device.refresh_expira_en = now + timedelta(days=settings.refresh_token_days)
    await db.commit()
    return DeviceRefreshResponse(
        access_token=create_device_token(device.id, binding.id),
        expires_in=settings.device_token_minutes * 60,
    )

@router.post("/self/unlink", status_code=status.HTTP_204_NO_CONTENT)
async def unlink_self(
    body: UnlinkSelfRequest,
    ctx: DeviceContext = Depends(current_device),
    db: AsyncSession = Depends(get_db),
):
    """Desvincula el teléfono. Exige el código del tutor si está definido."""

    await enforce(f"devices:unlink:{ctx.device.id}", 10, 900)
    await unlink_from_device(db, ctx, body.codigo)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
