import uuid

from fastapi import APIRouter, Depends, Query, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import get_db
from ..deps import current_tutor
from ..models import User
from ..schemas import (
    AlertOut,
    AlertUpdateRequest,
    DeviceOut,
    HideResult,
    OverviewOut,
    UnlinkCodeRequest,
)
from ..services.offline import sweep_offline
from ..services.panel import (
    build_overview,
    hide_device,
    hide_revoked_devices,
    list_alerts,
    list_devices,
    revoke_binding,
    update_alert_state,
)
from ..services.unlink import clear_unlink_code, set_unlink_code

router = APIRouter(tags=["Panel del tutor"])


@router.get("/overview", response_model=OverviewOut)
async def overview(tutor: User = Depends(current_tutor), db: AsyncSession = Depends(get_db)):
    await sweep_offline(db, tutor_id=tutor.id)
    return await build_overview(db, tutor.id)


@router.get("/alerts", response_model=list[AlertOut])
async def alerts(
    estado: str | None = Query(default=None),
    severidad: str | None = Query(default=None),
    limit: int = Query(default=50, ge=1, le=200),
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    await sweep_offline(db, tutor_id=tutor.id)
    return await list_alerts(db, tutor.id, estado=estado, severidad=severidad, limit=limit)


@router.patch("/alerts/{alert_id}", response_model=AlertOut)
async def update_alert(
    alert_id: uuid.UUID,
    body: AlertUpdateRequest,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    return await update_alert_state(db, tutor.id, alert_id, body.estado)


@router.get("/devices", response_model=list[DeviceOut])
async def devices(tutor: User = Depends(current_tutor), db: AsyncSession = Depends(get_db)):
    await sweep_offline(db, tutor_id=tutor.id)
    return await list_devices(db, tutor.id)


@router.put("/devices/{binding_id}/unlink-code", status_code=status.HTTP_204_NO_CONTENT)
async def put_unlink_code(
    binding_id: uuid.UUID,
    body: UnlinkCodeRequest,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    await set_unlink_code(db, tutor.id, binding_id, body.codigo)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.delete("/devices/{binding_id}/unlink-code", status_code=status.HTTP_204_NO_CONTENT)
async def remove_unlink_code(
    binding_id: uuid.UUID,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    await clear_unlink_code(db, tutor.id, binding_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/devices/hide-revoked", response_model=HideResult)
async def hide_all_revoked(tutor: User = Depends(current_tutor), db: AsyncSession = Depends(get_db)):
    return HideResult(ocultos=await hide_revoked_devices(db, tutor.id))


@router.post("/devices/{device_id}/hide", status_code=status.HTTP_204_NO_CONTENT)
async def hide_one(
    device_id: uuid.UUID,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    await hide_device(db, tutor.id, device_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/devices/{binding_id}/revoke", status_code=status.HTTP_204_NO_CONTENT)
async def revoke(
    binding_id: uuid.UUID,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    await revoke_binding(db, tutor.id, binding_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
