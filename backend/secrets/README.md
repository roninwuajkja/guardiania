# Credenciales locales de Firebase

Esta carpeta guarda secretos y **no se versiona** (ver `.gitignore`).

- `firebase-service-account.json`: clave de cuenta de servicio para FCM HTTP v1.
  Descárgala desde Firebase Console → ⚙️ Configuración del proyecto →
  Cuentas de servicio → Generar nueva clave privada.
- Nunca pegues este contenido en el chat, un issue o un commit.
- Si se filtra, revócala en Google Cloud IAM y genera una nueva.

El archivo `firebase-service-account.example.json` solo muestra la estructura.
Puedes verificar tu archivo sin exponer claves con:

```powershell
.venv\Scripts\python -m app.tools.verify_firebase
```
