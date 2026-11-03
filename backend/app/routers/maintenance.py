"""Tareas periódicas para el plan gratuito.

Render no ofrece workers gratis, así que el barrido de «sin señal» se ejecuta
al abrir el panel/app, en cada sincronización y, si se configura un pinger,
desde este endpoint protegido por una clave.
"""

from __future__ import annotations

import hmac

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import get_db
from ..services.offline import sweep_offline

router = APIRouter(prefix="/maintenance", tags=["Mantenimiento"], include_in_schema=False)


@router.post("/sweep")
async def sweep(request: Request, db: AsyncSession = Depends(get_db)):
    expected = settings.maintenance_key
    provided = request.headers.get("X-Maintenance-Key", "")
    if not expected or not hmac.compare_digest(provided, expected):
        # No se revela si la clave existe.
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")

    alerts = await sweep_offline(db)
    return {"avisos": len(alerts)}
