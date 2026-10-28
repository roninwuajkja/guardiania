import os
from pathlib import Path

import pytest

DB_PATH = Path(__file__).resolve().parent / "test_guardiania.db"
os.environ["DATABASE_URL"] = f"sqlite+aiosqlite:///{DB_PATH.as_posix()}"
os.environ["SEED_DEMO"] = "false"
os.environ["RATE_LIMIT_ENABLED"] = "false"
os.environ["ENVIRONMENT"] = "test"
# Las pruebas nunca llaman a Firebase ni a un proveedor de IA real.
os.environ["FCM_ENABLED"] = "false"
# Ninguna prueba debe usar una clave real del .env: se fuerzan vacías para que
# el resultado no dependa de la máquina ni gaste cuota.
for _key in ("GROQ_API_KEY", "GEMINI_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY"):
    os.environ[_key] = ""

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402


@pytest.fixture(scope="session")
def client():
    if DB_PATH.exists():
        DB_PATH.unlink()
    with TestClient(app) as test_client:
        yield test_client
