from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import get_db
from ..deps import DeviceContext, current_device
from ..ratelimit import enforce
from ..schemas import DerivedFeatureVector, EvaluationResponse, SyncRequest, SyncResponse
from ..services.captures import sync_captures
from ..services.evaluation import evaluate_vector

router = APIRouter(prefix="/captures", tags=["Capturas"])


@router.post("/sync", response_model=SyncResponse)
async def sync(
    body: SyncRequest,
    request: Request,
    ctx: DeviceContext = Depends(current_device),
    db: AsyncSession = Depends(get_db),
):
    client = request.client.host if request.client else "unknown"
    await enforce(f"captures:sync:{ctx.device.id}", 120, 3600)
    await enforce(f"captures:sync:ip:{client}", 120, 3600)
    return await sync_captures(db, ctx, body)


@router.post("/evaluate", response_model=EvaluationResponse)
async def evaluate(
    vector: DerivedFeatureVector,
    request: Request,
    ctx: DeviceContext = Depends(current_device),
    db: AsyncSession = Depends(get_db),
):
    """Evalúa un único vector con IA y despacha la alerta al tutor.

    El cuerpo es exactamente ``DerivedFeatureVector``: ``extra="forbid"`` hace
    imposible colar texto crudo en la petición.
    """

    client = request.client.host if request.client else "unknown"
    await enforce(f"captures:evaluate:{ctx.device.id}", 120, 3600)
    await enforce(f"captures:evaluate:ip:{client}", 120, 3600)
    return await evaluate_vector(db, ctx, vector)
