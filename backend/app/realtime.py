import asyncio
import uuid
from typing import Any


class PairingHub:
    """Fan-out of pairing events to open WebSocket connections in this process."""

    def __init__(self) -> None:
        self._queues: dict[str, set[asyncio.Queue]] = {}

    def subscribe(self, session_id: uuid.UUID | str) -> asyncio.Queue:
        key = str(session_id)
        queue: asyncio.Queue = asyncio.Queue(maxsize=32)
        self._queues.setdefault(key, set()).add(queue)
        return queue

    def unsubscribe(self, session_id: uuid.UUID | str, queue: asyncio.Queue) -> None:
        key = str(session_id)
        subscribers = self._queues.get(key)
        if not subscribers:
            return
        subscribers.discard(queue)
        if not subscribers:
            self._queues.pop(key, None)

    def publish(self, session_id: uuid.UUID | str, event: dict[str, Any]) -> None:
        for queue in list(self._queues.get(str(session_id), ())):
            try:
                queue.put_nowait(event)
            except asyncio.QueueFull:
                continue


class TutorHub:
    """Fan-out of risk alerts to every open session of one tutor.

    A tutor may have several devices or browser tabs; each gets its own queue.
    Queues are bounded so a slow client can never grow memory without limit.
    """

    def __init__(self) -> None:
        self._queues: dict[str, set[asyncio.Queue]] = {}

    def subscribe(self, tutor_id: uuid.UUID | str) -> asyncio.Queue:
        key = str(tutor_id)
        queue: asyncio.Queue = asyncio.Queue(maxsize=64)
        self._queues.setdefault(key, set()).add(queue)
        return queue

    def unsubscribe(self, tutor_id: uuid.UUID | str, queue: asyncio.Queue) -> None:
        key = str(tutor_id)
        subscribers = self._queues.get(key)
        if not subscribers:
            return
        subscribers.discard(queue)
        if not subscribers:
            self._queues.pop(key, None)

    def publish(self, tutor_id: uuid.UUID | str, event: dict[str, Any]) -> bool:
        """Return True when at least one live stream received the event."""
        delivered = False
        for queue in list(self._queues.get(str(tutor_id), ())):
            try:
                queue.put_nowait(event)
                delivered = True
            except asyncio.QueueFull:
                continue
        return delivered

    def has_subscribers(self, tutor_id: uuid.UUID | str) -> bool:
        return bool(self._queues.get(str(tutor_id)))


pairing_hub = PairingHub()
tutor_hub = TutorHub()
