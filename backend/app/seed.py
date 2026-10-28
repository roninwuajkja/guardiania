import logging
import uuid
from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from sqlalchemy import select

from .config import settings
from .db import SessionFactory, utcnow
from .models import (
    AccountSecurity,
    Binding,
    ConsentRecord,
    Device,
    FeatureCapture,
    PairingSession,
    RiskAlert,
    User,
)
from .security import hash_install_id, hash_password

logger = logging.getLogger("guardiania.seed")

DEMO_EMAIL = "demo@guardiania.cl"
DEMO_PASSWORD = "GuardianIA2026"

try:
    ZONE = ZoneInfo("America/Santiago")
except ZoneInfoNotFoundError:  # pragma: no cover
    ZONE = timezone(timedelta(hours=-4))


def _local(day_offset: int, hour: int, minute: int = 0) -> datetime:
    base = utcnow().astimezone(ZONE)
    target = (base - timedelta(days=day_offset)).replace(
        hour=hour, minute=minute, second=0, microsecond=0
    )
    return target


async def run_seed() -> None:
    async with SessionFactory() as db:
        existing = await db.scalar(select(User).where(User.email == DEMO_EMAIL))
        if existing is not None:
            changed = False
            security = await db.get(AccountSecurity, existing.id)
            if security is None:
                db.add(
                    AccountSecurity(
                        usuario_id=existing.id,
                        token_version=0,
                        email_verificado_en=utcnow(),
                    )
                )
                changed = True
            elif security.email_verificado_en is None:
                security.email_verificado_en = utcnow()
                changed = True
            existing_consents = set(
                (
                    await db.scalars(
                        select(ConsentRecord.tipo).where(ConsentRecord.tutor_id == existing.id)
                    )
                ).all()
            )
            for tipo in ("terminos", "privacidad", "tutoria"):
                if tipo not in existing_consents:
                    db.add(
                        ConsentRecord(
                            tutor_id=existing.id,
                            tipo=tipo,
                            version=settings.legal_version,
                            aceptado=True,
                            detalle={"origen": "seed_demo_backfill"},
                        )
                    )
                    changed = True
            if changed:
                await db.commit()
            return

        now = utcnow()
        tutor = User(
            rol="TUTOR",
            email=DEMO_EMAIL,
            password_hash=hash_password(DEMO_PASSWORD),
            alias="Camila Rojas",
            consentimiento_version=settings.legal_version,
            consentimiento_en=now - timedelta(days=21),
        )
        db.add(tutor)
        await db.flush()
        db.add(
            AccountSecurity(
                usuario_id=tutor.id,
                token_version=0,
                email_verificado_en=now - timedelta(days=21),
            )
        )
        for tipo in ("terminos", "privacidad", "tutoria"):
            db.add(
                ConsentRecord(
                    tutor_id=tutor.id,
                    tipo=tipo,
                    version=settings.legal_version,
                    aceptado=True,
                    detalle={"origen": "seed_demo"},
                    creado_en=now - timedelta(days=21),
                )
            )

        profiles = [
            {
                "name": "Sofía",
                "age": 12,
                "device": "Sofía · Galaxy A54",
                "model": "Galaxy A54",
                "api": 34,
                "install": "demo-sofia-a54",
                "hours": [8, 12, 16, 20, 22],
                "durations": [30, 25, 45, 35, 20],
                "categories": ["estudio", "social", "video", "social", "mensajeria"],
            },
            {
                "name": "Matías",
                "age": 9,
                "device": "Matías · Redmi Note 12",
                "model": "Redmi Note 12",
                "api": 33,
                "install": "demo-matias-note12",
                "hours": [9, 13, 17, 20],
                "durations": [40, 30, 50, 25],
                "categories": ["video", "juego", "estudio", "social"],
            },
        ]

        bindings: dict[str, Binding] = {}
        devices: dict[str, Device] = {}

        for index, profile in enumerate(profiles):
            child = User(
                rol="MENOR",
                alias=profile["name"],
                fecha_nacimiento=date(date.today().year - profile["age"], 1, 1),
                tutor_legal_id=tutor.id,
            )
            db.add(child)
            await db.flush()

            created = now - timedelta(days=20 - index)
            session = PairingSession(
                tutor_id=tutor.id,
                estado="VERIFICADA",
                expira_en=created + timedelta(minutes=10),
                verificado_en=created + timedelta(minutes=2),
                intentos=1,
                max_intentos=5,
                creado_en=created,
            )
            db.add(session)
            await db.flush()

            device = Device(
                usuario_id=tutor.id,
                alias=profile["device"],
                api_level=profile["api"],
                model_family=profile["model"],
                fingerprint_hmac=hash_install_id(profile["install"]),
                estado="ACTIVO",
                ultima_conexion=now - timedelta(minutes=12 + index * 7),
                creado_en=created,
            )
            db.add(device)
            await db.flush()

            binding = Binding(
                tutor_id=tutor.id,
                menor_id=child.id,
                dispositivo_id=device.id,
                sesion_id=session.id,
                etiqueta_menor=profile["name"],
                estado="ACTIVO",
                politica={"retencion_dias": 90, "captura_texto": False},
                activada_en=created + timedelta(minutes=2),
                creado_en=created,
                actualizado_en=created,
            )
            session.dispositivo_menor_id = device.id
            db.add(binding)
            await db.flush()

            bindings[profile["name"]] = binding
            devices[profile["name"]] = device

            for day_offset in range(7):
                for hour, duration, category in zip(
                    profile["hours"], profile["durations"], profile["categories"], strict=True
                ):
                    local_start = _local(day_offset, hour, 10)
                    if local_start >= now - timedelta(minutes=10):
                        continue
                    local_end = local_start + timedelta(minutes=duration)
                    offset_minutes = int((local_start.utcoffset() or timedelta()).total_seconds() // 60)
                    flags: list[str] = []
                    contact = None
                    if profile["name"] == "Sofía" and day_offset == 1 and hour == 20:
                        flags = ["unknown_contact"]
                        contact = "ZGVtby1jb250YWN0by1zb2ZpYQ"
                    if profile["name"] == "Matías" and day_offset == 2 and hour == 20:
                        flags = ["unknown_contact"]
                        contact = "ZGVtby1jb250YWN0by1tYXRpYXM"
                    db.add(
                        FeatureCapture(
                            dispositivo_id=device.id,
                            event_id=uuid.uuid4(),
                            vinculacion_id=binding.id,
                            time_window_start=local_start.astimezone(timezone.utc),
                            time_window_end=local_end.astimezone(timezone.utc),
                            tz_offset_minutes=offset_minutes,
                            categoria=category,
                            lexical_flag_count=len(flags),
                            flags=flags,
                            hashed_contact_id=contact,
                            usage_duration_minutes=duration if hour != 22 else min(duration, 25),
                            recibido_en=local_end.astimezone(timezone.utc) + timedelta(minutes=3),
                        )
                    )

        def alert(
            child_name: str,
            categoria: str,
            severidad: str,
            score: int,
            label: str,
            detail: str,
            created_at: datetime,
            state: str = "NUEVA",
        ) -> RiskAlert:
            binding = bindings[child_name]
            device = devices[child_name]
            return RiskAlert(
                vinculacion_id=binding.id,
                dispositivo_id=device.id,
                event_id=uuid.uuid4(),
                categoria=categoria,
                severidad=severidad,
                score=score,
                reglas=[{"code": categoria, "label": label, "evidence": {"demo": True}}],
                resumen=detail[:280],
                estado=state,
                revisada_en=None if state == "NUEVA" else created_at + timedelta(hours=2),
                creado_en=created_at,
            )

        db.add_all(
            [
                alert(
                    "Sofía",
                    "bullying",
                    "ALTA",
                    74,
                    "Acoso escolar",
                    "Escucha sin minimizar y considera avisar al colegio si se repite.",
                    now - timedelta(hours=5),
                ),
                alert(
                    "Matías",
                    "gambling",
                    "MEDIA",
                    58,
                    "Apuestas",
                    "Revisa si hay dinero involucrado y pon límites claros.",
                    now - timedelta(hours=2),
                ),
                alert(
                    "Sofía",
                    "uso_nocturno",
                    "MEDIA",
                    55,
                    "Uso en horario nocturno",
                    "Revisa por qué estuvo despierta y acuerden un horario de descanso.",
                    now - timedelta(days=2, hours=3),
                    "VISTA",
                ),
                alert(
                    "Matías",
                    "contacto_recurrente",
                    "ALTA",
                    76,
                    "Contacto desconocido recurrente",
                    "Pregunta quién es y cómo se conocieron, sin interrogar.",
                    now - timedelta(days=3, hours=6),
                    "RECONOCIDA",
                ),
                alert(
                    "Matías",
                    "uso_prolongado",
                    "MEDIA",
                    60,
                    "Uso prolongado",
                    "Propón una pausa y revisen juntos cuánto tiempo es razonable.",
                    now - timedelta(days=4, hours=1),
                    "VISTA",
                ),
                alert(
                    "Sofía",
                    "self_harm",
                    "CRITICA",
                    95,
                    "Posible autolesión",
                    "Conversaron en familia; la alerta quedó cerrada.",
                    now - timedelta(days=6, hours=4),
                    "DESCARTADA",
                ),
            ]
        )
        await db.commit()
        logger.info("Datos demo creados: %s / %s", DEMO_EMAIL, DEMO_PASSWORD)
