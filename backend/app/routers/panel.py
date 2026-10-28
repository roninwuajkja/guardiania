import uuid

from fastapi import APIRouter, Depends, Query, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import get_db
from ..deps import current_tutor
from ..models import User
from ..schemas import AlertOut, AlertUpdateRequest, DeviceOut, OverviewOut
from ..services.panel import (
    build_overview,
    list_alerts,
    list_devices,
    revoke_binding,
    update_alert_state,
)

router = APIRouter(tags=["Panel del tutor"])


@router.get("/overview", response_model=OverviewOut)
async def overview(tutor: User = Depends(current_tutor), db: AsyncSession = Depends(get_db)):
    return await build_overview(db, tutor.id)


@router.get("/alerts", response_model=list[AlertOut])
async def alerts(
    estado: str | None = Query(default=None),
    severidad: str | None = Query(default=None),
    limit: int = Query(default=50, ge=1, le=200),
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
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
    return await list_devices(db, tutor.id)


@router.post("/devices/{binding_id}/revoke", status_code=status.HTTP_204_NO_CONTENT)
async def revoke(
    binding_id: uuid.UUID,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    await revoke_binding(db, tutor.id, binding_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
