import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import get_db
from ..deps import current_tutor
from ..models import Binding, Device, PairingSession, User
from ..net import client_ip
from ..ratelimit import enforce
from ..realtime import pairing_hub
from ..runtime import dispatcher
from ..schemas import PairingGenerateRequest, PairingGenerateResponse, PairingVerifyRequest, PairingVerifyResponse
from ..security import format_manual_code
from ..services.audit import record, record_consent
from ..services.pairing import cancel_pairing, generate_pairing, verify_pairing

router = APIRouter(prefix="/pairing", tags=["Vinculación"])


@router.post("/generate", response_model=PairingGenerateResponse, status_code=status.HTTP_201_CREATED)
async def generate(
    body: PairingGenerateRequest,
    request: Request,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    await enforce(f"pairing:generate:{tutor.id}", 20, 3600)
    if not body.tutor_declara_tutoria:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Debes declarar que ejerces la patria potestad, tutela o cuidado personal del menor.",
        )
    generated = await generate_pairing(db, tutor, body.etiqueta_menor, body.ttl_seconds)
    await record_consent(
        db,
        tutor_id=tutor.id,
        tipo="tutoria",
        version=settings.legal_version,
        request=request,
        vinculacion_id=None,
        detalle={"pairing_session": str(generated.session_id)},
    )
    await record(
        db,
        "pairing.generate",
        usuario_id=tutor.id,
        request=request,
        entidad="pairing_session",
        entidad_id=generated.session_id,
        detalle={"ttl": body.ttl_seconds},
        commit=True,
    )
    return PairingGenerateResponse(
        session_id=generated.session_id,
        qr_payload=generated.qr_payload,
        qr_svg=generated.qr_svg,
        manual_code=format_manual_code(generated.manual_code),
        expires_at=generated.expires_at,
        ws_url=f"/ws/pairing/{generated.session_id}",
        ws_ticket=generated.ws_ticket,
    )


@router.post("/verify", response_model=PairingVerifyResponse)
async def verify(body: PairingVerifyRequest, request: Request, db: AsyncSession = Depends(get_db)):
    await enforce(f"pairing:verify:{client_ip(request)}", 30, 60)
    await enforce(f"pairing:verify:install:{body.device.install_id}", 10, 600)
    response = await verify_pairing(db, body)
    binding = await db.get(Binding, response.vinculacion_id)
    if binding is not None:
        await record_consent(
            db,
            tutor_id=binding.tutor_id,
            tipo="asentimiento_menor",
            version=settings.legal_version,
            request=request,
            vinculacion_id=binding.id,
            detalle={"device_alias": response.device_alias, "origen": "dispositivo_menor"},
        )
        await record(
            db,
            "pairing.verify",
            usuario_id=binding.tutor_id,
            request=request,
            entidad="vinculacion",
            entidad_id=binding.id,
            detalle={"device_id": str(response.device_id)},
            commit=True,
        )
    await dispatcher.dispatch_once()
    return response


@router.get("/{session_id}")
async def session_status(
    session_id: uuid.UUID,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    session = await db.scalar(
        select(PairingSession).where(PairingSession.id == session_id, PairingSession.tutor_id == tutor.id)
    )
    if session is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Sesión no encontrada")
    binding = await db.scalar(select(Binding).where(Binding.sesion_id == session.id))
    device = await db.get(Device, binding.dispositivo_id) if binding and binding.dispositivo_id else None
    return {
        "session_id": session.id,
        "estado": session.estado,
        "expires_at": session.expira_en,
        "vinculacion_id": binding.id if binding else None,
        "vinculacion_estado": binding.estado if binding else None,
        "device_alias": device.alias if device else None,
        "subscribers": len(pairing_hub._queues.get(str(session.id), ())),
    }


@router.post("/{session_id}/cancel", status_code=status.HTTP_204_NO_CONTENT)
async def cancel(
    session_id: uuid.UUID,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    await cancel_pairing(db, tutor, session_id)
    await dispatcher.dispatch_once()
    return Response(status_code=status.HTTP_204_NO_CONTENT)
