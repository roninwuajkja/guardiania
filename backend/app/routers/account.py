import uuid
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import get_db, utcnow
from ..deps import current_tutor
from ..models import (
    AccountSecurity,
    AuditEvent,
    AuthToken,
    Binding,
    ConsentRecord,
    Device,
    FeatureCapture,
    OutboxEvent,
    PairingSession,
    RiskAlert,
    TutorSession,
    User,
)
from ..schemas import ConsentOut, ConsentRequest, DeleteAccountRequest
from ..security import hash_password, verify_password
from ..services.audit import record, record_consent

router = APIRouter(prefix="/account", tags=["Cuenta y datos"])


@router.get("/consents", response_model=list[ConsentOut])
async def list_consents(tutor: User = Depends(current_tutor), db: AsyncSession = Depends(get_db)):
    rows = (
        await db.execute(
            select(ConsentRecord)
            .where(ConsentRecord.tutor_id == tutor.id)
            .order_by(ConsentRecord.creado_en.desc())
            .limit(100)
        )
    ).scalars().all()
    return [
        ConsentOut(
            id=row.id,
            tipo=row.tipo,
            version=row.version,
            aceptado=row.aceptado,
            vinculacion_id=row.vinculacion_id,
            creado_en=row.creado_en,
        )
        for row in rows
    ]


@router.post("/consents", response_model=ConsentOut, status_code=status.HTTP_201_CREATED)
async def add_consent(
    body: ConsentRequest,
    request: Request,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    if body.vinculacion_id is not None:
        owned = await db.scalar(
            select(Binding.id).where(
                Binding.id == body.vinculacion_id,
                Binding.tutor_id == tutor.id,
            )
        )
        if owned is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Vinculación no encontrada")
    row = await record_consent(
        db,
        tutor_id=tutor.id,
        tipo=body.tipo,
        version=body.version or settings.legal_version,
        request=request,
        vinculacion_id=body.vinculacion_id,
        detalle={"origen": "api"},
        commit=True,
    )
    await record(
        db,
        "account.consent",
        usuario_id=tutor.id,
        request=request,
        entidad="consentimiento",
        entidad_id=row.id,
        detalle={"tipo": body.tipo, "version": row.version},
        commit=True,
    )
    return ConsentOut(
        id=row.id,
        tipo=row.tipo,
        version=row.version,
        aceptado=row.aceptado,
        vinculacion_id=row.vinculacion_id,
        creado_en=row.creado_en,
    )


@router.get("/export")
async def export_account(
    request: Request,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    now = utcnow()
    security = await db.get(AccountSecurity, tutor.id)
    consents = (
        await db.execute(select(ConsentRecord).where(ConsentRecord.tutor_id == tutor.id))
    ).scalars().all()
    devices = (
        await db.execute(select(Device).where(Device.usuario_id == tutor.id))
    ).scalars().all()
    bindings = (
        await db.execute(select(Binding).where(Binding.tutor_id == tutor.id))
    ).scalars().all()
    binding_ids = [binding.id for binding in bindings]
    alerts = (
        await db.execute(
            select(RiskAlert).where(RiskAlert.vinculacion_id.in_(binding_ids)).order_by(RiskAlert.creado_en.desc())
        )
    ).scalars().all() if binding_ids else []
    captures = (
        await db.execute(
            select(FeatureCapture)
            .where(FeatureCapture.vinculacion_id.in_(binding_ids))
            .order_by(FeatureCapture.time_window_start.desc())
        )
    ).scalars().all() if binding_ids else []
    audit = (
        await db.execute(
            select(AuditEvent)
            .where(AuditEvent.usuario_id == tutor.id, AuditEvent.creado_en >= now - timedelta(days=90))
            .order_by(AuditEvent.creado_en.desc())
        )
    ).scalars().all()

    payload = {
        "generated_at": now,
        "legal_version": settings.legal_version,
        "tutor": {
            "id": str(tutor.id),
            "email": tutor.email,
            "alias": tutor.alias,
            "consent_version": tutor.consentimiento_version,
            "consent_at": tutor.consentimiento_en,
            "email_verified": bool(security and security.email_verificado_en),
            "created_at": tutor.creado_en,
        },
        "consents": [
            {
                "tipo": item.tipo,
                "version": item.version,
                "accepted": item.aceptado,
                "binding_id": str(item.vinculacion_id) if item.vinculacion_id else None,
                "created_at": item.creado_en,
            }
            for item in consents
        ],
        "devices": [
            {
                "id": str(item.id),
                "alias": item.alias,
                "platform": item.plataforma,
                "model_family": item.model_family,
                "state": item.estado,
                "last_seen": item.ultima_conexion,
                "created_at": item.creado_en,
            }
            for item in devices
        ],
        "bindings": [
            {
                "id": str(item.id),
                "device_id": str(item.dispositivo_id) if item.dispositivo_id else None,
                "child_label": item.etiqueta_menor,
                "state": item.estado,
                "policy": item.politica,
                "activated_at": item.activada_en,
                "revoked_at": item.revocada_en,
                "created_at": item.creado_en,
            }
            for item in bindings
        ],
        "alerts": [
            {
                "id": str(item.id),
                "binding_id": str(item.vinculacion_id),
                "device_id": str(item.dispositivo_id),
                "category": item.categoria,
                "severity": item.severidad,
                "score": item.score,
                "summary": item.resumen,
                "rules": item.reglas,
                "state": item.estado,
                "created_at": item.creado_en,
            }
            for item in alerts
        ],
        "captures": [
            {
                "event_id": str(item.event_id),
                "binding_id": str(item.vinculacion_id),
                "device_id": str(item.dispositivo_id),
                "window_start": item.time_window_start,
                "window_end": item.time_window_end,
                "category": item.categoria,
                "lexical_flag_count": item.lexical_flag_count,
                "flags": item.flags,
                "hashed_contact_id": item.hashed_contact_id,
                "usage_duration_minutes": item.usage_duration_minutes,
            }
            for item in captures
        ],
        "audit": [
            {
                "action": item.accion,
                "entity": item.entidad,
                "entity_id": item.entidad_id,
                "detail": item.detalle,
                "created_at": item.creado_en,
            }
            for item in audit
        ],
    }
    await record(db, "account.export", usuario_id=tutor.id, request=request, commit=True)
    return JSONResponse(
        content=jsonable_encoder(payload),
        headers={"Content-Disposition": 'attachment; filename="ia-guardian-datos.json"'},
    )


@router.post("/delete", status_code=status.HTTP_204_NO_CONTENT)
async def delete_account(
    body: DeleteAccountRequest,
    request: Request,
    response: Response,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    if not tutor.password_hash or not verify_password(body.password, tutor.password_hash):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Contraseña incorrecta")
    if body.confirmation.strip().upper() != settings.account_delete_phrase.upper():
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Frase de confirmación incorrecta")

    now = utcnow()
    bindings = (await db.execute(select(Binding.id).where(Binding.tutor_id == tutor.id))).scalars().all()
    if bindings:
        await db.execute(delete(FeatureCapture).where(FeatureCapture.vinculacion_id.in_(bindings)))
        await db.execute(delete(RiskAlert).where(RiskAlert.vinculacion_id.in_(bindings)))
        await db.execute(delete(ConsentRecord).where(ConsentRecord.vinculacion_id.in_(bindings)))
        await db.execute(delete(OutboxEvent).where(OutboxEvent.agregado_id.in_(bindings)))
        await db.execute(delete(Binding).where(Binding.id.in_(bindings)))
    await db.execute(delete(PairingSession).where(PairingSession.tutor_id == tutor.id))
    await db.execute(delete(Device).where(Device.usuario_id == tutor.id))
    await db.execute(delete(ConsentRecord).where(ConsentRecord.tutor_id == tutor.id))
    await db.execute(delete(AuthToken).where(AuthToken.usuario_id == tutor.id))
    await db.execute(delete(TutorSession).where(TutorSession.usuario_id == tutor.id))
    await db.execute(delete(AuditEvent).where(AuditEvent.usuario_id == tutor.id))

    user_id = tutor.id
    # Keep the row referentially valid while removing identifying data.
    tutor.email = f"eliminado-{tutor.id}@invalid.local"
    tutor.password_hash = "!"
    tutor.alias = "Cuenta eliminada"
    tutor.fecha_nacimiento = None
    tutor.tutor_legal_id = None
    tutor.consentimiento_version = None
    tutor.consentimiento_en = None
    tutor.eliminado_en = now
    security = await db.get(AccountSecurity, tutor.id)
    if security is not None:
        security.token_version += 1
        security.email_verificado_en = None
    await db.commit()

    await record(
        db,
        "account.deleted",
        request=request,
        entidad="usuario",
        entidad_id=user_id,
        detalle={"retention_days": settings.retention_days},
        commit=True,
    )
    from ..security import clear_auth_cookies

    clear_auth_cookies(response)
    response.status_code = status.HTTP_204_NO_CONTENT
    return response


@router.get("/audit")
async def recent_audit(
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    rows = (
        await db.execute(
            select(AuditEvent)
            .where(AuditEvent.usuario_id == tutor.id)
            .order_by(AuditEvent.creado_en.desc())
            .limit(100)
        )
    ).scalars().all()
    return [
        {
            "action": row.accion,
            "entity": row.entidad,
            "entity_id": row.entidad_id,
            "detail": row.detalle,
            "created_at": row.creado_en,
        }
        for row in rows
    ]
