import base64
import json
import logging
import os
from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

BASE_DIR = Path(__file__).resolve().parent.parent
logger = logging.getLogger("guardiania.config")


def _default_public_base_url() -> str:
    """URL pública del servicio.

    Render inyecta RENDER_EXTERNAL_URL con el dominio real asignado, así que el
    despliegue no necesita escribirla a mano ni redeployar para corregirla.
    """
    return os.environ.get("RENDER_EXTERNAL_URL", "http://127.0.0.1:8000").rstrip("/")


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=BASE_DIR / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "IA Guardian"
    environment: str = "dev"
    public_base_url: str = Field(default_factory=_default_public_base_url)
    database_url: str = f"sqlite+aiosqlite:///{(BASE_DIR / 'guardiania.db').as_posix()}"

    jwt_issuer: str = "https://api.guardiania.cl"
    jwt_audience: str = "guardiania:api"
    pairing_audience: str = "guardiania:pairing"
    jwt_kid: str = "guardiania-dev-1"
    jwt_private_key_pem: str | None = None
    keys_dir: Path = BASE_DIR / ".data" / "keys"

    pairing_ttl_seconds: int = 600
    pairing_code_length: int = 6
    pairing_max_attempts: int = 5
    pairing_pepper: str = "dev-pairing-pepper-change-me"
    audit_pepper: str = "dev-audit-pepper-change-me"

    access_token_minutes: int = 30
    device_token_minutes: int = 15
    refresh_token_days: int = 30
    tutor_refresh_days: int = 14
    ws_ticket_seconds: int = 60
    sync_max_batch: int = 200

    rate_limit_enabled: bool = True
    seed_demo: bool = True
    cors_origins: str = "http://localhost:8000,http://127.0.0.1:8000"
    trusted_hosts: str = "*"
    trust_proxy_headers: bool = False

    cookie_secure: bool | None = None
    cookie_samesite: str = "lax"
    cookie_domain: str | None = None

    max_request_bytes: int = 1_048_576
    retention_days: int = 90
    audit_retention_days: int = 365
    docs_enabled: bool = True

    legal_version: str = "2026-10-22"
    privacy_email: str = "privacidad@guardiania.cl"
    security_email: str = "seguridad@guardiania.cl"
    support_email: str = "soporte@guardiania.cl"

    email_delivery: str = "console"  # console | smtp
    email_from: str = "IA Guardian <no-reply@guardiania.cl>"
    smtp_host: str | None = None
    smtp_port: int = 587
    smtp_user: str | None = None
    smtp_password: str | None = None
    smtp_starttls: bool = True
    smtp_ssl: bool = False
    require_email_verification: bool = False

    require_attestation: bool = False
    account_delete_phrase: str = "ELIMINAR MI CUENTA"

    # --- Motor de IA (Módulo A) ------------------------------------------------
    # La nube recibe SOLO el vector derivado. AI_SEND_SNIPPET existe como
    # compuerta explícita para un futuro con consentimiento y DPA firmados; en
    # 1.7.1 ninguna ruta acepta texto, por lo que el valor no habilita nada.
    ai_enabled: bool = True
    ai_send_snippet: bool = False
    # auto = primer proveedor con clave; explícito = ese manda y el resto queda
    # como fallback. "heuristic" desactiva la nube sin apagar el motor.
    ai_provider: str = "auto"
    ai_primary_timeout_ms: int = 1000
    ai_fallback_timeout_ms: int = 450
    ai_total_timeout_ms: int = 1500
    ai_cache_ttl_seconds: int = 300
    ai_cache_max_entries: int = 512
    ai_breaker_failures: int = 3
    ai_breaker_cooldown_seconds: int = 60

    # Groq (API compatible con OpenAI). Free tier generoso y LPU de baja
    # latencia; se usa como primario en la demo. Los modelos gpt-oss aceptan
    # json_schema estricto y un esfuerzo de razonamiento configurable.
    groq_api_key: str | None = None
    groq_model: str = "openai/gpt-oss-20b"
    groq_base_url: str = "https://api.groq.com/openai/v1"
    # low mantiene la latencia baja; None omite el campo para modelos que no lo
    # aceptan.
    groq_reasoning_effort: str | None = "low"

    openai_api_key: str | None = None
    openai_model: str = "gpt-4o-mini"
    openai_base_url: str = "https://api.openai.com/v1"

    anthropic_api_key: str | None = None
    anthropic_model: str = "claude-3-5-haiku-latest"
    anthropic_base_url: str = "https://api.anthropic.com/v1"

    # Google Gemini (API nativa). El alias "latest" evita romperse cuando
    # Google retira una versión concreta del modelo flash.
    gemini_api_key: str | None = None
    gemini_model: str = "gemini-3.5-flash"
    gemini_base_url: str = "https://generativelanguage.googleapis.com/v1beta"
    # 0 apaga el razonamiento y reduce latencia; None omite thinkingConfig.
    gemini_thinking_budget: int | None = 0
    # Alternativa para modelos que rechazan thinkingBudget (p. ej. Flash-Lite):
    # minimal | low | medium | high. Si se define, tiene prioridad sobre el budget.
    gemini_thinking_level: str | None = None

    # --- Notificaciones push (FCM HTTP v1) -------------------------------------
    # Sin credenciales el servicio queda deshabilitado y el WebSocket sigue
    # siendo el canal en tiempo real. El payload nunca incluye texto.
    fcm_enabled: bool = False
    firebase_credentials_file: Path = BASE_DIR / "secrets" / "firebase-service-account.json"
    # Permite desplegar sin disco persistente: el JSON se escribe a un archivo
    # temporal al arrancar. Nunca se registra en logs.
    firebase_credentials_json: str | None = None
    fcm_timeout_ms: int = 1500
    android_package_name: str = "cl.guardiania.agent"

    # Minutos sin sincronizar antes de avisar «teléfono sin señal».
    offline_alert_minutes: int = 60

    # Clave del endpoint /maintenance/sweep que puede llamar el pinger.
    maintenance_key: str | None = None

    @model_validator(mode="before")
    @classmethod
    def materialize_firebase_credentials(cls, data):
        """Escribe la cuenta de servicio desde una variable de entorno.

        Render gratuito no permite subir archivos secretos, así que la
        credencial viaja como variable (JSON o base64) y se materializa en
        /tmp antes de validar la configuración.
        """

        if not isinstance(data, dict):
            return data
        raw = data.get("firebase_credentials_json") or os.environ.get("FIREBASE_CREDENTIALS_JSON")
        if not raw:
            return data
        text = str(raw).strip()
        if not text.startswith("{"):
            try:
                text = base64.b64decode(text).decode("utf-8")
            except Exception:
                return data
        json.loads(text)  # si no es JSON válido, se ignora sin filtrar el contenido
        target = Path(
            data.get("firebase_credentials_file")
            or os.environ.get("FIREBASE_CREDENTIALS_FILE")
            or "/tmp/firebase-service-account.json"
        )
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
        try:
            target.chmod(0o600)
        except OSError:
            pass
        data["firebase_credentials_file"] = str(target)
        data["fcm_enabled"] = True
        return data

    @field_validator("offline_alert_minutes")
    @classmethod
    def offline_minutes_range(cls, value: int) -> int:
        if not 5 <= value <= 1440:
            raise ValueError("OFFLINE_ALERT_MINUTES debe estar entre 5 y 1440")
        return value

    @field_validator(
        "gemini_api_key",
        "openai_api_key",
        "anthropic_api_key",
        "groq_api_key",
        mode="before",
    )
    @classmethod
    def blank_key_is_absent(cls, value: str | None) -> str | None:
        """Una variable de entorno vacía o solo con espacios no es una clave.

        Sin esto, un ``ANTHROPIC_API_KEY=" "`` heredado del shell hace que el
        servicio intente un proveedor inválido antes de degradar a heurística.
        """

        if value is None:
            return None
        stripped = str(value).strip()
        return stripped or None

    @field_validator("groq_reasoning_effort", mode="before")
    @classmethod
    def normalize_reasoning_effort(cls, value: str | None) -> str | None:
        if value is None:
            return None
        stripped = str(value).strip().lower()
        return stripped or None

    @property
    def ai_configured(self) -> bool:
        return self.ai_enabled and bool(
            self.groq_api_key
            or self.gemini_api_key
            or self.openai_api_key
            or self.anthropic_api_key
        )

    @property
    def is_production(self) -> bool:
        return self.environment.strip().lower() in {"prod", "production"}

    @property
    def effective_cookie_secure(self) -> bool:
        if self.cookie_secure is not None:
            return self.cookie_secure
        return self.is_production or self.public_base_url.startswith("https://")

    @property
    def cors_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]

    @property
    def trusted_host_list(self) -> list[str]:
        return [host.strip() for host in self.trusted_hosts.split(",") if host.strip()]

    def validate_runtime(self) -> None:
        """Fail closed when production is missing a security-critical setting."""
        if self.cookie_samesite not in {"lax", "strict"}:
            raise RuntimeError("COOKIE_SAMESITE debe ser 'lax' o 'strict'")
        if self.max_request_bytes < 16_384 or self.max_request_bytes > 8_388_608:
            raise RuntimeError("MAX_REQUEST_BYTES debe estar entre 16 KiB y 8 MiB")
        if self.retention_days < 1 or self.retention_days > 3650:
            raise RuntimeError("RETENTION_DAYS debe estar entre 1 y 3650")
        # Demo free tier: la latencia observada llega a ~15 s, así que el techo
        # se deja en 18,5 s. El cliente Android corta a los 20 s de lectura.
        if not 100 <= self.ai_primary_timeout_ms <= 18500:
            raise RuntimeError("AI_PRIMARY_TIMEOUT_MS debe estar entre 100 y 18500")
        if not 100 <= self.ai_fallback_timeout_ms <= 18500:
            raise RuntimeError("AI_FALLBACK_TIMEOUT_MS debe estar entre 100 y 18500")
        if not 200 <= self.ai_total_timeout_ms <= 19500:
            raise RuntimeError("AI_TOTAL_TIMEOUT_MS debe estar entre 200 y 19500")
        if self.ai_provider.strip().lower() not in {
            "auto",
            "groq",
            "gemini",
            "openai",
            "anthropic",
            "heuristic",
        }:
            raise RuntimeError(
                "AI_PROVIDER debe ser auto, groq, gemini, openai, anthropic o heuristic"
            )
        if self.groq_reasoning_effort not in {None, "low", "medium", "high"}:
            raise RuntimeError("GROQ_REASONING_EFFORT debe ser low, medium, high o vacío")
        if self.gemini_thinking_budget is not None and not -1 <= self.gemini_thinking_budget <= 32768:
            raise RuntimeError("GEMINI_THINKING_BUDGET debe estar entre -1 y 32768")
        if self.gemini_thinking_level is not None and self.gemini_thinking_level not in {
            "minimal",
            "low",
            "medium",
            "high",
        }:
            raise RuntimeError("GEMINI_THINKING_LEVEL debe ser minimal, low, medium o high")
        if not 100 <= self.fcm_timeout_ms <= 10_000:
            raise RuntimeError("FCM_TIMEOUT_MS debe estar entre 100 y 10000")
        if not self.is_production:
            if self.pairing_pepper.startswith("dev-") or self.audit_pepper.startswith("dev-"):
                logger.warning("Entorno no productivo con secretos de desarrollo. No usar con datos reales.")
            return

        problems: list[str] = []
        if not self.public_base_url.startswith("https://"):
            problems.append("PUBLIC_BASE_URL debe usar HTTPS")
        if not self.jwt_private_key_pem:
            problems.append("JWT_PRIVATE_KEY_PEM es obligatorio (no se genera clave efímera en producción)")
        if self.pairing_pepper.startswith("dev-") or len(self.pairing_pepper) < 32:
            problems.append("PAIRING_PEPPER debe ser un secreto aleatorio de al menos 32 caracteres")
        if self.audit_pepper.startswith("dev-") or len(self.audit_pepper) < 32:
            problems.append("AUDIT_PEPPER debe ser un secreto aleatorio de al menos 32 caracteres")
        if self.seed_demo:
            problems.append("SEED_DEMO debe ser false")
        if self.database_url.startswith("sqlite"):
            problems.append("DATABASE_URL debe apuntar a PostgreSQL en producción")
        if not self.effective_cookie_secure:
            problems.append("COOKIE_SECURE debe ser true")
        if not self.cors_origin_list or any(not origin.startswith("https://") for origin in self.cors_origin_list):
            problems.append("CORS_ORIGINS debe contener solo orígenes HTTPS explícitos")
        if not self.trusted_host_list or "*" in self.trusted_host_list:
            problems.append("TRUSTED_HOSTS debe listar los dominios explícitos")
        if self.docs_enabled:
            problems.append("DOCS_ENABLED debe ser false")
        if self.email_delivery != "smtp" or not self.smtp_host:
            problems.append("EMAIL_DELIVERY=smtp y SMTP_HOST son obligatorios")
        if not self.smtp_ssl and not self.smtp_starttls:
            problems.append("El correo debe usar SMTP_SSL o SMTP_STARTTLS con validación de certificado")
        if not self.require_email_verification:
            problems.append("REQUIRE_EMAIL_VERIFICATION debe ser true")
        if self.fcm_enabled and not self.firebase_credentials_file.exists():
            problems.append("FCM_ENABLED=true requiere FIREBASE_CREDENTIALS_FILE existente")
        if self.ai_send_snippet:
            problems.append("AI_SEND_SNIPPET debe ser false: la Regla Cero prohíbe texto en la nube")
        if problems:
            raise RuntimeError("Configuración de producción insegura: " + "; ".join(problems))
        logger.info("Configuración de producción validada")


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
settings.validate_runtime()
