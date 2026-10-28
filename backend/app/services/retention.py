import logging
from dataclasses import dataclass
from datetime import timedelta

from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import utcnow
from ..models import AuditEvent, AuthToken, FeatureCapture, OutboxEvent, RiskAlert, TutorSession

logger = logging.getLogger("guardiania.retention")


@dataclass
class RetentionResult:
    captures: int = 0
    alerts: int = 0
    tokens: int = 0
    sessions: int = 0
    outbox: int = 0
    audit: int = 0


async def run_retention(db: AsyncSession) -> RetentionResult:
    now = utcnow()
    result = RetentionResult()

    cutoff = now - timedelta(days=settings.retention_days)
    deleted = await db.execute(delete(FeatureCapture).where(FeatureCapture.time_window_start < cutoff))
    result.captures = deleted.rowcount or 0
    deleted = await db.execute(delete(RiskAlert).where(RiskAlert.creado_en < cutoff))
    result.alerts = deleted.rowcount or 0

    deleted = await db.execute(
        delete(AuthToken).where(
            (AuthToken.expira_en < now - timedelta(days=7)) | (AuthToken.usado_en.is_not(None))
        )
    )
    result.tokens = deleted.rowcount or 0
    deleted = await db.execute(
        delete(TutorSession).where(
            (TutorSession.expira_en < now - timedelta(days=30))
            | (TutorSession.revocada_en < now - timedelta(days=30))
        )
    )
    result.sessions = deleted.rowcount or 0
    deleted = await db.execute(
        delete(OutboxEvent).where(
            OutboxEvent.publicado_en.is_not(None),
            OutboxEvent.publicado_en < now - timedelta(days=7),
        )
    )
    result.outbox = deleted.rowcount or 0
    deleted = await db.execute(
        delete(AuditEvent).where(AuditEvent.creado_en < now - timedelta(days=settings.audit_retention_days))
    )
    result.audit = deleted.rowcount or 0
    await db.commit()
    logger.info(
        "Retención aplicada: capturas=%s alertas=%s tokens=%s sesiones=%s outbox=%s auditoría=%s",
        result.captures,
        result.alerts,
        result.tokens,
        result.sessions,
        result.outbox,
        result.audit,
    )
    return result
