import asyncio
import logging
import uuid

from sqlalchemy import select

from ..db import SessionFactory, utcnow
from ..models import OutboxEvent
from ..realtime import PairingHub

logger = logging.getLogger("guardiania.outbox")


class OutboxDispatcher:
    """Publishes committed domain events to the in-process pairing hub.

    The outbox row is the source of truth for replay and audit; the dispatcher
    runs after commit, so a WebSocket client never sees an event that the
    database rejected.
    """

    def __init__(self, hub: PairingHub) -> None:
        self._hub = hub
        self._lock = asyncio.Lock()

    async def dispatch_once(self) -> int:
        async with self._lock:
            published = 0
            async with SessionFactory() as db:
                rows = (
                    await db.execute(
                        select(OutboxEvent)
                        .where(OutboxEvent.publicado_en.is_(None))
                        .order_by(OutboxEvent.id)
                        .limit(100)
                    )
                ).scalars().all()
                for row in rows:
                    session_id = row.payload.get("session_id")
                    if session_id:
                        self._hub.publish(
                            session_id,
                            {
                                "v": 1,
                                "type": row.tipo,
                                "session_id": session_id,
                                "seq": row.id,
                                "ts": utcnow().isoformat(),
                                "data": row.payload,
                            },
                        )
                    row.publicado_en = utcnow()
                    published += 1
                if rows:
                    await db.commit()
            return published

    async def run_forever(self, interval_seconds: float = 0.5) -> None:
        while True:
            try:
                await self.dispatch_once()
            except asyncio.CancelledError:
                raise
            except Exception:  # pragma: no cover - resilience loop
                logger.exception("outbox dispatch failed")
            await asyncio.sleep(interval_seconds)


def pairing_event(
    event_type: str,
    *,
    session_id: uuid.UUID,
    binding_id: uuid.UUID,
    tutor_id: uuid.UUID,
    device_alias: str | None = None,
    severity: str | None = None,
) -> OutboxEvent:
    return OutboxEvent(
        agregado="vinculacion",
        agregado_id=binding_id,
        tipo=event_type,
        payload={
            "session_id": str(session_id),
            "vinculacion_id": str(binding_id),
            "tutor_id": str(tutor_id),
            "device_alias": device_alias,
            "severity": severity,
        },
    )
