# Firebase Cloud Messaging en IA Guardian 1.7.10

Objetivo: que el tutor reciba una alerta aunque la app esté en segundo plano o
cerrada. **FCM es solo transporte**, no base de datos. PostgreSQL sigue siendo
la única fuente de verdad de alertas, auditoría y retención.

## Qué se envía (minimización)

El mensaje push contiene como máximo:

| Campo | Ejemplo | Nota |
|---|---|---|
| `tipo` | `risk_alert` | constante |
| `alert_id` | UUID | el detalle se consulta a la API |
| `severidad` | `ALTA` | sin narrativa |
| `categoria` | `bullying` | código, no texto libre |
| `device_alias` | `Sofía` | alias que puso el tutor |
| `ts` | ISO-8601 | hora del servidor |

Nunca viajan: texto de notificaciones, nombres de contactos, mensajes,
narrativa de la IA ni el vector completo.

## Paso a paso

### 1. Crear el proyecto

1. <https://console.firebase.google.com> → **Agregar proyecto**.
2. Nombre sugerido: `guardiania`.
3. Plan **Spark** (gratis) basta para FCM.
4. Google Analytics es opcional; puedes desactivarlo.

### 2. Registrar la app Android

1. ⚙️ **Configuración del proyecto** → pestaña **General**.
2. **Tus apps** → icono Android.
3. **Nombre del paquete:** `cl.guardiania.agent` (exacto, sin espacios).
4. Apodo: `IA Guardian Agent`.
5. SHA-1: opcional para FCM; no es necesario ahora.
6. Descarga `google-services.json` y guárdalo en:

```
android/app/google-services.json
```

### 3. Activar Cloud Messaging

1. Menú izquierdo → **Compilar** → **Cloud Messaging**.
2. Debe aparecer habilitado (en Spark ya viene activo).
3. No hace falta crear campañas ni plantillas; el backend usa HTTP v1.

### 4. Cuenta de servicio para el backend

1. ⚙️ **Configuración del proyecto** → **Cuentas de servicio**.
2. **Generar nueva clave privada** → JSON.
3. Guárdala como:

```
backend/secrets/firebase-service-account.json
```

4. En `backend/.env`:

```env
FCM_ENABLED=true
# FIREBASE_CREDENTIALS_FILE=secrets/firebase-service-account.json
```

La ruta por defecto ya apunta a `backend/secrets/firebase-service-account.json`.

### 5. Verificar sin exponer secretos

```powershell
cd backend
.venv\Scripts\python -m app.tools.verify_firebase
```

Salida esperada:

```
[OK] cuenta de servicio · project_id=guardiania
[OK] google-services · package=cl.guardiania.agent project_id=guardiania
Resultado: OK. Credenciales presentes y coherentes.
```

El verificador no imprime claves; puedes pegar su salida en el chat si algo
falla.

### 6. Probar de extremo a extremo

1. Backend con `FCM_ENABLED=true` y reiniciado.
2. App Android compilada con `google-services.json` (el plugin de Gradle se
   agrega en la fase Android).
3. Inicia sesión como tutor y acepta notificaciones.
4. La app registra su token con `POST /api/v1/push/tokens`.
5. Genera una captura con `flags: ["self_harm"]` desde el dispositivo del menor
   o llama a `POST /api/v1/captures/evaluate` con el token del dispositivo.
6. Debes recibir la notificación con el alias del menor.

La app también mantiene un WebSocket (`/ws/tutor`) para entrega instantánea con
la app abierta; FCM cubre el caso en segundo plano.

## Seguridad y operación

- `google-services.json` y la cuenta de servicio están en `.gitignore`.
- Si una clave se filtra: revócala en Google Cloud IAM y genera otra.
- El backend firma un JWT RS256 con la cuenta de servicio y lo cambia por un
  access token OAuth2; no se guarda la clave en la base de datos.
- Los tokens FCM que Google marca como `UNREGISTERED` se desactivan solos.
- Al iniciar sesión otro tutor en el mismo teléfono, el token se reasigna para
  no filtrar alertas al tutor anterior.
- El envío tiene un tope de `FCM_TIMEOUT_MS` (1500 ms por defecto) para no
  romper el SLA de evaluación → notificación.

## Diagnóstico rápido

| Síntoma | Causa probable |
|---|---|
| `FCM_ENABLED=false` en el verificador | falta la variable en `backend/.env` |
| `FALTA el archivo ...` | ruta incorrecta o archivo no descargado |
| `package ... y se esperaba ...` | registraste otro paquete en Firebase |
| `project_id ... no coinciden` | backend y Android apuntan a proyectos distintos |
| Push no llega con app cerrada | falta el servicio Android de FCM (fase Android) o el token no está activo |
| Push llega pero sin sonido | canal `guardiania_alerts` sin importancia HIGH en Android |

## Por qué no Firestore

Duplicar alertas o perfiles de menores en Firestore crearía una segunda copia
que habría que cifrar, retener, auditar y borrar. La app consulta el detalle a
la API autenticada, y FCM solo despierta al dispositivo. Si más adelante se
quiere un espejo para el panel web, se evaluará como caché sin PII.
