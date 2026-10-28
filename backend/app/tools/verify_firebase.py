"""Verificador local de credenciales Firebase.

Uso:
    python -m app.tools.verify_firebase
    python -m app.tools.verify_firebase --google-services ..\\android\\app\\google-services.json

Nunca imprime claves ni tokens. Solo informa OK / FALTA / ERROR para que puedas
compartir la salida sin filtrar secretos.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from ..config import BASE_DIR, settings
from ..services.push import load_credentials


def _check_service_account(path: Path) -> tuple[list[str], dict | None]:
    issues: list[str] = []
    if not path.exists():
        issues.append(f"FALTA el archivo {path}")
        return issues, None
    try:
        credentials = load_credentials(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        issues.append(f"ERROR en la cuenta de servicio: {type(exc).__name__}: {exc}")
        return issues, None
    if credentials.project_id in {"", "REEMPLAZA_CON_TU_PROJECT_ID"}:
        issues.append("ERROR: project_id sigue siendo el de la plantilla")
    return issues, {
        "project_id": credentials.project_id,
        "client_email_domain": credentials.client_email.split("@")[-1],
        "token_uri": credentials.token_uri,
    }


def _client_packages(data: dict) -> list[str]:
    """google-services.json puede traer `client_info` arriba o dentro de `client[]`."""

    infos: list[dict] = []
    if isinstance(data.get("client_info"), dict):
        infos.append(data["client_info"])
    clients = data.get("client")
    if isinstance(clients, list):
        for entry in clients:
            if isinstance(entry, dict) and isinstance(entry.get("client_info"), dict):
                infos.append(entry["client_info"])
    packages = []
    for info in infos:
        android = info.get("android_client_info")
        if isinstance(android, dict) and android.get("package_name"):
            packages.append(str(android["package_name"]))
    return packages


def _check_google_services(path: Path, expected_package: str) -> tuple[list[str], dict | None]:
    issues: list[str] = []
    if not path.exists():
        issues.append(f"FALTA el archivo {path}")
        return issues, None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        issues.append(f"ERROR al leer google-services.json: {type(exc).__name__}")
        return issues, None
    if not isinstance(data, dict):
        issues.append("ERROR: google-services.json no es un objeto JSON")
        return issues, None

    packages = _client_packages(data)
    project_id = data.get("project_info", {}).get("project_id") if isinstance(data.get("project_info"), dict) else None
    if expected_package not in packages:
        found = ", ".join(packages) if packages else "ninguno"
        issues.append(f"ERROR: el package registrado es {found} y se esperaba {expected_package!r}")
    if not project_id:
        issues.append("ERROR: google-services.json no tiene project_info.project_id")
    return issues, {"package": expected_package if expected_package in packages else ", ".join(packages), "project_id": project_id}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verifica credenciales de Firebase para IA Guardian")
    parser.add_argument(
        "--credentials",
        type=Path,
        default=settings.firebase_credentials_file,
        help="Ruta de la cuenta de servicio (por defecto FIREBASE_CREDENTIALS_FILE)",
    )
    parser.add_argument(
        "--google-services",
        type=Path,
        default=BASE_DIR.parent / "android" / "app" / "google-services.json",
        help="Ruta de google-services.json del cliente Android",
    )
    parser.add_argument("--package", default=settings.android_package_name)
    args = parser.parse_args(argv)

    issues: list[str] = []
    print("IA Guardian - verificacion de Firebase")
    print(f"FCM_ENABLED={settings.fcm_enabled}")
    print(f"Credenciales: {args.credentials}")
    print(f"Android:      {args.google_services}")
    print("-" * 56)

    account_issues, account = _check_service_account(args.credentials)
    issues.extend(account_issues)
    if account:
        print(f"[OK] cuenta de servicio - project_id={account['project_id']}")
        print(f"[OK] client_email - dominio={account['client_email_domain']}")
        print(f"[OK] token_uri - {account['token_uri']}")

    android_issues, android = _check_google_services(args.google_services, args.package)
    issues.extend(android_issues)
    if android:
        print(f"[OK] google-services - package={android['package']} project_id={android['project_id']}")

    if account and android and account["project_id"] != android["project_id"]:
        issues.append("ERROR: el project_id del backend y de Android no coinciden")

    print("-" * 56)
    if issues:
        for issue in issues:
            print(f"[FALLA] {issue}")
        print("Resultado: NO listo. Corrige lo anterior y vuelve a ejecutar.")
        return 1

    print("Resultado: OK. Credenciales presentes y coherentes.")
    if not settings.fcm_enabled:
        print("Aviso: FCM_ENABLED=false; define FCM_ENABLED=true y reinicia el backend para enviar push.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
