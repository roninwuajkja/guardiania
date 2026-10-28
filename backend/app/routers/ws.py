import asyncio
import uuid
from datetime import datetime

from fastapi import APIRouter, HTTPException, WebSocket, WebSocketDisconnect
from sqlalchemy import select

from ..config import settings
from ..db import SessionFactory, utcnow
from ..models import Binding, Device, PairingSession, User
from ..realtime import pairing_hub, tutor_hub
from ..security import decode_token

router = APIRouter()


def _envelope(event_type: str, session_id: uuid.UUID, data: dict, seq: int = 0) -> dict:
    return {
        "v": 1,
        "type": event_type,
        "session_id": str(session_id),
        "seq": seq,
        "ts": utcnow().isoformat(),
        "data": data,
    }


def _stream_envelope(event_type: str, data: dict) -> dict:
    return {
        "v": 1,
        "type": event_type,
        "seq": 0,
        "ts": utcnow().isoformat(),
        "data": data,
    }


def _binding_state(binding: Binding | None) -> str:
    return binding.estado if binding else "PENDIENTE"


WS_PROTOCOL = "guardiania"


@router.websocket("/ws/pairing/{session_id}")
async def pairing_socket(
    websocket: WebSocket,
    session_id: uuid.UUID,
):
    # Never put the bearer ticket in the query string: reverse proxies and
    # access logs would persist it. Browsers can send it as a subprotocol.
    offered = [
        item.strip()
        for item in websocket.headers.get("sec-websocket-protocol", "").split(",")
        if item.strip()
    ]
    has_protocol = WS_PROTOCOL in offered
    ticket = next((item for item in offered if item != WS_PROTOCOL), None)
    await websocket.accept(subprotocol=WS_PROTOCOL if has_protocol else None)

    if not has_protocol or not ticket:
        await websocket.close(code=4401, reason="Ticket requerido")
        return

    try:
        claims = decode_token(ticket, settings.jwt_audience)
    except HTTPException:
        await websocket.close(code=4401, reason="Ticket inválido")
        return

    if claims.get("typ") != "ws_ticket" or claims.get("scope") != "pairing:subscribe":
        await websocket.close(code=4403, reason="Ticket sin permisos")
        return
    if claims.get("sid") != str(session_id):
        await websocket.close(code=4403, reason="Sesión no coincide")
        return

    try:
        tutor_id = uuid.UUID(claims["sub"])
    except (KeyError, ValueError):
        await websocket.close(code=4401, reason="Ticket inválido")
        return

    async with SessionFactory() as db:
        session = await db.get(PairingSession, session_id)
        binding = await db.scalar(select(Binding).where(Binding.sesion_id == session_id))
        device = await db.get(Device, binding.dispositivo_id) if binding and binding.dispositivo_id else None

    if session is None or session.tutor_id != tutor_id:
        await websocket.close(code=4404, reason="Sesión no encontrada")
        return

    queue = pairing_hub.subscribe(session_id)
    try:
        await websocket.send_json(
            _envelope(
                "pairing_ready",
                session_id,
                {
                    "estado": session.estado,
                    "expires_at": _iso(session.expira_en),
                    "vinculacion_estado": _binding_state(binding),
                    "device_alias": device.alias if device else None,
                },
            )
        )

        if binding is not None and binding.estado == "ACTIVO":
            await websocket.send_json(
                _envelope(
                    "pairing_success",
                    session_id,
                    {
                        "vinculacion_id": str(binding.id),
                        "device_alias": device.alias if device else binding.etiqueta_menor,
                    },
                )
            )
            await websocket.close(code=1000)
            return

        if session.estado != "PENDIENTE" or (binding is not None and binding.estado in {"EXPIRADO", "REVOCADO"}):
            await websocket.send_json(
                _envelope("pairing_expired", session_id, {"estado": _binding_state(binding)})
            )
            await websocket.close(code=1000)
            return

        while True:
            try:
                event = await asyncio.wait_for(queue.get(), timeout=25)
            except TimeoutError:
                await websocket.send_json(_envelope("ping", session_id, {}))
                continue
            await websocket.send_json(event)
            if event.get("type") in {"pairing_success", "pairing_expired", "binding_revoked"}:
                break
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        pairing_hub.unsubscribe(session_id, queue)
        try:
            await websocket.close(code=1000)
        except RuntimeError:
            pass


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value else None


@router.websocket("/ws/tutor")
async def tutor_socket(websocket: WebSocket):
    """Stream de alertas para el dispositivo del tutor.

    Autenticación idéntica a ``/ws/pairing``: ticket efímero con scope
    ``alerts:subscribe`` en el subprotocolo. La query string se rechaza para no
    dejar el ticket en logs.
    """

    offered = [
        item.strip()
        for item in websocket.headers.get("sec-websocket-protocol", "").split(",")
        if item.strip()
    ]
    has_protocol = WS_PROTOCOL in offered
    ticket = next((item for item in offered if item != WS_PROTOCOL), None)
    await websocket.accept(subprotocol=WS_PROTOCOL if has_protocol else None)

    if not has_protocol or not ticket:
        await websocket.close(code=4401, reason="Ticket requerido")
        return

    try:
        claims = decode_token(ticket, settings.jwt_audience)
    except HTTPException:
        await websocket.close(code=4401, reason="Ticket inválido")
        return

    if claims.get("typ") != "ws_ticket" or claims.get("scope") != "alerts:subscribe":
        await websocket.close(code=4403, reason="Ticket sin permisos")
        return

    try:
        tutor_id = uuid.UUID(claims["sub"])
    except (KeyError, ValueError):
        await websocket.close(code=4401, reason="Ticket inválido")
        return

    async with SessionFactory() as db:
        tutor = await db.get(User, tutor_id)

    if tutor is None or tutor.eliminado_en is not None:
        await websocket.close(code=4404, reason="Cuenta no encontrada")
        return

    queue = tutor_hub.subscribe(tutor_id)
    try:
        await websocket.send_json(
            _stream_envelope(
                "tutor_ready",
                {
                    "alerts_subscribed": True,
                    "tutor_alias": tutor.alias,
                    "server_time": utcnow().isoformat(),
                },
            )
        )
        while True:
            try:
                event = await asyncio.wait_for(queue.get(), timeout=25)
            except TimeoutError:
                await websocket.send_json(_stream_envelope("ping", {}))
                continue
            await websocket.send_json(event)
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        tutor_hub.unsubscribe(tutor_id, queue)
        try:
            await websocket.close(code=1000)
        except RuntimeError:
            pass
