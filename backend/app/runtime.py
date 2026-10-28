from .realtime import pairing_hub
from .services.outbox import OutboxDispatcher

dispatcher = OutboxDispatcher(pairing_hub)
