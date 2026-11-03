import asyncio
import logging

from fastapi import APIRouter, Depends, Query, Response, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import get_db, utcnow
from ..deps import current_tutor
from ..models import PushToken, User
from ..ratelimit import enforce
from ..schemas import PushTestOut, PushTokenOut, PushTokenRequest, RealtimeTicketResponse
from ..security import create_alert_ws_ticket
from ..services.push import push_service

router = APIRouter(tags=["Tiempo real"])

logger = logging.getLogger("guardiania.push")
# Una tarea programada sin referencia puede ser recolectada antes de ejecutarse.
_scheduled_push_tests: set[asyncio.Task] = set()


def _ws_url() -> str:
    # Ruta relativa, igual que /ws/pairing: el cliente la resuelve contra su
    # base y el túnel/dominio no queda incrustado en la respuesta.
    return "/ws/tutor"


@router.post("/realtime/ticket", response_model=RealtimeTicketResponse)
async def realtime_ticket(
    tutor: User = Depends(current_tutor),
):
    """Emite un ticket efímero para el stream de alertas del tutor.

    El ticket viaja en ``Sec-WebSocket-Protocol``; nunca en la URL, porque los
    proxies y logs de acceso guardan la query string.
    """

    await enforce(f"realtime:ticket:{tutor.id}", 60, 60)
    return RealtimeTicketResponse(
        ws_url=_ws_url(),
        ws_ticket=create_alert_ws_ticket(tutor.id),
        expires_in=settings.ws_ticket_seconds,
    )


def _token_out(row: PushToken) -> PushTokenOut:
    return PushTokenOut(
        id=row.id,
        plataforma=row.plataforma,
        activo=row.activo,
        device_label=row.device_label,
        creado_en=row.creado_en,
        actualizado_en=row.actualizado_en,
    )


@router.get("/push/tokens", response_model=list[PushTokenOut])
async def list_tokens(
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    rows = (
        await db.execute(
            select(PushToken).where(PushToken.usuario_id == tutor.id).order_by(PushToken.creado_en.desc())
        )
    ).scalars()
    return [_token_out(row) for row in rows]


@router.post("/push/tokens", response_model=PushTokenOut, status_code=status.HTTP_201_CREATED)
async def register_token(
    body: PushTokenRequest,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    """Registra o reasigna un token FCM al tutor autenticado.

    Reasignar importa: si otra persona inicia sesión en el mismo teléfono, el
    token deja de pertenecer al tutor anterior para no filtrar alertas.
    """

    await enforce(f"push:register:{tutor.id}", 30, 3600)
    row = await db.scalar(select(PushToken).where(PushToken.token == body.token))
    if row is None:
        row = PushToken(
            usuario_id=tutor.id,
            token=body.token,
            plataforma=body.plataforma,
            device_label=body.device_label,
            activo=True,
        )
        db.add(row)
    else:
        row.usuario_id = tutor.id
        row.plataforma = body.plataforma
        row.device_label = body.device_label or row.device_label
        row.activo = True
    await db.commit()
    await db.refresh(row)
    return _token_out(row)


@router.post("/push/tokens/unregister", status_code=status.HTTP_204_NO_CONTENT)
async def unregister_token(
    body: PushTokenRequest,
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
):
    row = await db.scalar(
        select(PushToken).where(PushToken.token == body.token, PushToken.usuario_id == tutor.id)
    )
    if row is not None:
        row.activo = False
        await db.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)

@router.post("/push/test", response_model=PushTestOut)
async def test_push(
    tutor: User = Depends(current_tutor),
    db: AsyncSession = Depends(get_db),
    delay_seconds: int = Query(default=0, ge=0, le=30),
):
    """Envía una push de prueba solo a los tokens del tutor autenticado.

    ``delay_seconds`` permite programar la prueba para que el tutor pueda
    cerrar la app antes de que FCM la entregue. Sin retraso, un mensaje
    ``notification`` enviado con la app en primer plano lo consume el servicio
    local y el sistema no lo pinta.
    """

    await enforce(f"push:test:{tutor.id}", 5, 60)
    rows = list(
        (
            await db.execute(
                select(PushToken).where(PushToken.usuario_id == tutor.id, PushToken.activo.is_(True))
            )
        ).scalars()
    )
    tokens = [row.token for row in rows]

    if not push_service.enabled:
        return PushTestOut(enabled=False, sent=0, invalid=0, tokens=len(tokens))
    if not tokens:
        return PushTestOut(enabled=True, sent=0, invalid=0, tokens=0)

    data = {
        "tipo": "test",
        "severidad": "BAJA",
        "categoria": "test",
        "device_alias": "IA Guardian",
        "ts": utcnow().isoformat(),
    }

    if delay_seconds > 0:
        async def send_later() -> None:
            await asyncio.sleep(delay_seconds)
            try:
                result = await push_service.send(tokens, data=data)
                logger.info(
                    "push_test_scheduled sent=%d failed=%d tokens=%d",
                    result.sent,
                    result.failed,
                    len(tokens),
                )
            except Exception as exc:  # noqa: BLE001 - una prueba no debe tumbar el proceso
                logger.warning("push_test_scheduled_failed error=%s", type(exc).__name__)

        task = asyncio.create_task(send_later())
        _scheduled_push_tests.add(task)
        task.add_done_callback(_scheduled_push_tests.discard)
        return PushTestOut(
            enabled=True,
            sent=0,
            invalid=0,
            tokens=len(tokens),
            scheduled=True,
            delay_seconds=delay_seconds,
        )

    result = await push_service.send(tokens, data=data)
    if result.invalid_tokens:
        invalid = set(result.invalid_tokens)
        for row in rows:
            if row.token in invalid:
                row.activo = False
        await db.commit()

    return PushTestOut(
        enabled=True,
        sent=result.sent,
        invalid=len(result.invalid_tokens),
        tokens=len(tokens),
        scheduled=False,
        delay_seconds=0,
    )
