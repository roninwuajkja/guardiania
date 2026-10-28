import asyncio
import time
from collections import defaultdict, deque

from fastapi import HTTPException, status

from .config import settings


class SlidingWindowLimiter:
    """Small in-process limiter. Replace with a shared store when running multi-instance."""

    def __init__(self) -> None:
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = asyncio.Lock()

    async def hit(self, key: str, limit: int, window_seconds: int) -> None:
        if not settings.rate_limit_enabled:
            return
        now = time.monotonic()
        async with self._lock:
            bucket = self._hits[key]
            while bucket and bucket[0] <= now - window_seconds:
                bucket.popleft()
            if len(bucket) >= limit:
                retry_after = max(1, int(bucket[0] + window_seconds - now))
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail="Demasiados intentos. Espera un momento.",
                    headers={"Retry-After": str(retry_after)},
                )
            bucket.append(now)


limiter = SlidingWindowLimiter()


async def enforce(key: str, limit: int, window_seconds: int) -> None:
    await limiter.hit(key, limit, window_seconds)
