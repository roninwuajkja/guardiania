# IA Guardian

Control parental para Android con **privacidad por diseño**: la conversación nunca sale del teléfono. El agente analiza localmente, descarta el texto y envía solo un vector de señales derivadas. El tutor recibe alertas explicables, no contenido.

> **Proyecto de Título — borrador de producción 1.2.0.** Los datos de la demo son sintéticos. El motor de riesgo combina reglas explicables y una evaluación de IA que **solo recibe el vector derivado** (nunca texto); si no hay proveedor configurado, opera en modo heurístico. No es un diagnóstico ni reemplaza una conversación familiar. Antes de operar con datos reales de menores se requiere revisión legal chilena y auditoría de seguridad independiente; ver [`docs/compliance/compuerta-lanzamiento.md`](docs/compliance/compuerta-lanzamiento.md).

![Panel del tutor](docs/screenshots/web-overview.png)

## Qué incluye

- **Dos roles en Android:** panel del tutor (login, alertas, dispositivos, vinculación, ajustes) y agente del menor (captura local, permisos, estado, desvinculación).
- **Panel web del tutor** servido por FastAPI, con sesión en cookies `httpOnly` + CSRF, refresh serializado, exportación y eliminación de cuenta.
- **Vinculación con dos voluntades:** el tutor declara patria potestad/tutela/cuidado personal y el menor otorga asentimiento en su propio teléfono. QR de 10 minutos, código manual de 6 dígitos y confirmación en vivo por WebSocket.
- **Cuentas de tutor:** registro, verificación de correo, recuperación de contraseña, cierre de todas las sesiones, consentimientos versionados y bitácora de auditoría.
- **Agente Android nativo** (Kotlin + Compose) que lee uso de apps y metadatos de notificaciones, sanitiza en memoria y mantiene una cola offline cifrada con SQLCipher.
- **Motor de riesgo determinista** con evidencias (`gambling`, `self_harm`, `bullying`, `grooming`, `drugs`, `violence`, `uso_nocturno`, `uso_prolongado`). La regla `contacto_recurrente` queda reservada: el agente 1.2.0 aún no calcula hashes de contacto.
- **Evaluación de IA en la nube con privacidad por diseño (1.2.0):** el proveedor recibe únicamente el vector derivado (categoría, flags anonimizados, duración, conteos y hora local). Cadena Groq → Gemini → OpenAI → Anthropic → heurística local, con caché, circuit breaker y presupuesto configurable (2500/3000 ms en la demo con Groq). Sin claves configuradas, todo sigue funcionando en modo heurístico.
- **Alertas en tiempo real y push (1.2.0):** WebSocket `/ws/tutor` con ticket efímero para la app abierta y Firebase Cloud Messaging para segundo plano. El push lleva solo `alert_id`, severidad, categoría y alias: nunca narrativa ni contenido.
- **Backend FastAPI async** con PostgreSQL en producción y SQLite solo para demo local.
- **Sin analítica ni publicidad:** el único tercero opcional es el proveedor de IA (solo vector) y FCM (payload mínimo); ambos se pueden desactivar por configuración.

## Arquitectura

```mermaid
flowchart LR
  subgraph Phone["Teléfono del menor · Android"]
    U["UsageStatsManager"] --> S["Sanitizer local\n(texto descartado)"]
    N["NotificationListener"] --> S
    S --> F["DerivedFeatureVector\ncategoría · flags · conteos"]
    F --> Q[("SQLCipher\noutbox + tokens")]
    Q --> W["WorkManager\ncada 15 min"]
  end

  subgraph Tutor["Tutor · web o Android"]
    WEB["Panel web\ncookies + CSRF"]
    APP["App tutor\nBearer + refresh rotatorio"]
  end

  W -->|"POST /captures/evaluate\nseñal prioritaria"| API["FastAPI\nAPI + WebSocket"]
  W -->|"POST /captures/sync\nlote idempotente"| API
  WEB -->|"HTTPS"| API
  APP -->|"HTTPS"| API
  API --> DB[("PostgreSQL\nSQLite solo demo")]
  API --> R["Reglas explicables"]
  API -->|"solo vector derivado"| IA["Evaluador IA\nGroq → Gemini → OpenAI → Anthropic → heurística"]
  R --> DB
  API -->|"alerta mínima"| FCM["FCM"]
  FCM --> APP
  API -->|"ticket en subprotocolo WS"| WEB
```

Diagramas y contratos completos en [`docs/architecture.md`](docs/architecture.md). Decisiones de producto y privacidad en [`DESIGN.md`](DESIGN.md).

## Estructura

```text
.
├── backend/              API FastAPI, panel web, páginas legales y tests
│   ├── app/
│   │   ├── routers/      auth, pairing, captures, devices, account, panel, realtime, ws
│   │   ├── services/     pairing, captures, risk, evaluation, ai_evaluator, alerts, push,
│   │   │                 accounts, mail, retention, audit
│   │   ├── web/          consola del tutor (vanilla JS, fuentes locales)
│   │   ├── models.py     usuarios, sesiones, consentimientos, capturas, alertas, push_tokens
│   │   └── seed.py       demo sintética
│   └── tests/            integración, IA, push y configuración de producción
├── android/              app Kotlin + Jetpack Compose (tutor y menor)
│   └── app/src/main/java/cl/guardiania/agent/
│       ├── capture/      Sanitizer, FeatureComposer, listener de notificaciones
│       ├── data/         SQLCipher, Retrofit, sesión, refresh, tutor
│       ├── notifications/ canales y notificación local del tutor
│       ├── push/         registro FCM y servicio de mensajes
│       ├── realtime/     stream de alertas por WebSocket
│       ├── sync/         SyncEngine, HighRiskPolicy, WorkManager
│       └── ui/           role chooser, tutor, pairing, estado, tema
├── docs/                 arquitectura, capturas y paquete de cumplimiento
├── docker-compose.yml    PostgreSQL opcional para desarrollo
├── DESIGN.md             decisiones de producto, privacidad y sistema visual
└── PRODUCT.md            brief de producto
```

## Inicio rápido (demo local)

Requisitos: **Python 3.11+**, **JDK 17** y **Android SDK 35** para la app.

### 1. Backend y panel web

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item .env.example .env
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload
```

Abre <http://127.0.0.1:8000>. La base SQLite `backend/guardiania.db` y la demo se crean solas en el primer arranque.

| Campo | Valor |
| --- | --- |
| Correo | `demo@guardiania.cl` |
| Clave | `GuardianIA2026` |
| Menores | Sofía (12) y Matías (14) |

OpenAPI queda en <http://127.0.0.1:8000/docs> (solo en desarrollo) y el healthcheck en `GET /api/v1/health`.

### 1.1 Motor de IA (opcional)

La app funciona sin IA en la nube: sin clave responde la heurística local. Para
probar el camino real, crea una clave gratuita en <https://console.groq.com/keys>
(sin tarjeta) y déjala en `backend/.env`:

```env
GROQ_API_KEY=gsk_...
GROQ_MODEL=openai/gpt-oss-20b
GROQ_REASONING_EFFORT=low
AI_PROVIDER=groq
AI_PRIMARY_TIMEOUT_MS=2500
AI_FALLBACK_TIMEOUT_MS=450
AI_TOTAL_TIMEOUT_MS=3000
```

Reinicia el backend y verifica con vectores sintéticos:

```powershell
cd backend
.\.venv\Scripts\python.exe -m app.tools.verify_ai --provider groq --list-models
.\.venv\Scripts\python.exe -m app.tools.verify_ai --provider groq --repeat 5
```

**Medición real (28-10-2026):** 8 de 8 evaluaciones con `provider=groq`, JSON
válido, P50 **765–780 ms**, P95 **1000 ms**. La señal de prueba del emulador
produjo `ai_eval provider=groq latency_ms=812` y una única alerta con narrativa
en el panel del tutor.

**Límites del free tier (honestos):** alrededor de 30 req/min y 1.000 req/día
por modelo, con 8K TPM; al agotarse responde `429` y el sistema degrada a
heurística. La cuota y los modelos disponibles se confirman con `--list-models`
contra la cuenta real. Groq no entrena con inputs/outputs por defecto y no
retiene la inferencia salvo logs de abuso/fallos (hasta 30 días); aun así, usa
**solo vectores sintéticos o de demo** hasta firmar el DPA. El texto de las
notificaciones sigue sin salir del teléfono.

Gemini queda como alternativa configurable (`AI_PROVIDER=gemini`), pero su free
tier de 20 req/día y sus 4–18 s de latencia no sirven como camino principal ni
como fallback de 450 ms.

### 2. App Android

```powershell
cd android
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n cl.guardiania.agent/.MainActivity
```

En el emulador usa `http://10.0.2.2:8000`. En un teléfono físico por USB, la opción recomendada es `adb reverse tcp:8000 tcp:8000` y luego `http://127.0.0.1:8000` en la app: el backend sigue escuchando solo en loopback y no se expone a la red. Alternativa sin cable: IP LAN del computador, backend en `0.0.0.0` y ambos equipos en la misma red. El tráfico HTTP en claro está habilitado **solo en builds debug**; la build release exige HTTPS.

Detalles de permisos, SQLCipher, roles y pruebas en [`android/README.md`](android/README.md).

### 3. Vincular un menor

1. En el panel o en la app tutor, entra a **Vincular**, declara tu calidad legal y genera un QR o código.
2. En el teléfono del menor, elige **Este teléfono es del menor**, revisa qué señales se comparten y marca el asentimiento.
3. Escanea el QR o escribe los 6 dígitos. La app solo habilita el botón después del asentimiento.
4. El panel confirma en vivo por WebSocket y el teléfono pasa a la pantalla de estado.
5. Activa **Acceso de uso**, **Acceso a notificaciones** y **Batería sin restricciones**. Usa **Enviar señal de prueba** para verificar el flujo completo.

Estados de vinculación: `PENDIENTE`, `ACTIVO`, `REVOCADO`, `EXPIRADO`. Un código reutilizado responde de forma genérica (422); un QR reutilizado responde 409.

## API principal

| Método | Ruta | Uso |
| --- | --- | --- |
| `POST` | `/api/v1/auth/register` | Crea tutor y consentimientos |
| `POST` | `/api/v1/auth/login` | Login web/Android; devuelve tokens y cookies |
| `POST` | `/api/v1/auth/refresh` | Rotación atómica del refresh de tutor |
| `POST` | `/api/v1/auth/logout` | Revoca la sesión actual |
| `POST` | `/api/v1/auth/verify-email` | Consume token de verificación |
| `POST` | `/api/v1/auth/password/forgot` | Solicita enlace de recuperación |
| `POST` | `/api/v1/auth/password/reset` | Consume token y revoca sesiones |
| `POST` | `/api/v1/pairing/generate` | Crea sesión, QR, código y ticket WS |
| `POST` | `/api/v1/pairing/verify` | Vincula el teléfono con asentimiento |
| `POST` | `/api/v1/captures/sync` | Lote idempotente de vectores derivados |
| `POST` | `/api/v1/captures/evaluate` | Evalúa un vector con IA y despacha la alerta |
| `POST` | `/api/v1/realtime/ticket` | Ticket efímero para el stream del tutor |
| `POST` | `/api/v1/push/tokens` | Registra/reasigna el token FCM del tutor |
| `GET` | `/api/v1/push/tokens` | Lista tokens sin exponer el valor |
| `POST` | `/api/v1/push/tokens/unregister` | Desactiva el token al cerrar sesión |
| `POST` | `/api/v1/devices/refresh` | Renueva el access token del teléfono |
| `GET` | `/api/v1/devices` | Inventario de dispositivos |
| `POST` | `/api/v1/devices/{id}/revoke` | Revoca una vinculación |
| `GET` | `/api/v1/overview` | Resumen del panel |
| `GET` | `/api/v1/alerts` | Alertas explicables |
| `PATCH` | `/api/v1/alerts/{id}` | Marcar vista, reconocer o descartar |
| `GET` | `/api/v1/account/export` | Exportación JSON de los datos del tutor |
| `POST` | `/api/v1/account/delete` | Eliminación con frase de confirmación |
| `WS` | `/ws/pairing/{session_id}` | Confirmación en vivo; ticket en subprotocolo |
| `WS` | `/ws/tutor` | Alertas en vivo para el tutor; ticket `alerts:subscribe` |

## Seguridad y privacidad

- **Nunca** se persiste ni transmite el texto de una notificación. El `Sanitizer` lo usa en memoria y devuelve solo categoría y flags.
- El servidor rechaza campos extra en los vectores (`extra="forbid"`), como defensa contra PII accidental.
- El vector enviado contiene categoría de app, minutos, conteos, flags, ventana temporal y `event_id` determinista. `hashed_contact_id` existe en el contrato pero va `null` en 1.2.0.
- **IA:** el proveedor externo recibe solo ese vector. No hay ruta que acepte texto y `AI_SEND_SNIPPET` está cerrado sin llamadores. La respuesta se valida como JSON estricto y, si falla, se usa la heurística local.
- **Push:** FCM recibe `alert_id`, severidad, categoría, alias y hora. La narrativa, el resumen y cualquier texto quedan fuera. El token FCM es del tutor, nunca del menor.
- Los datos locales viven en SQLCipher; la frase se envuelve con una clave AES/GCM del Android Keystore.
- Web: cookies `httpOnly` (`SameSite=Lax`, `Secure` en producción), CSRF en métodos mutables, CSP sin `unsafe-inline` para scripts y `connect-src` limitado al backend configurado.
- Android tutor: Bearer + refresh **rotatorio** con `Mutex`; web serializa refresh con `navigator.locks` y una promesa compartida.
- Pairing: declaración de tutoría obligatoria, asentimiento del menor obligatorio, código HMAC con pepper, máximo 5 intentos, ticket WS de 60 s fuera de la URL y del access log.
- Correo: enlaces de verificación/reset en el **fragmento** de la URL (no llegan al servidor ni al `Referer`); SMTP con TLS y validación de certificado.
- Operación: rate limit, límite de tamaño de request, cabeceras de seguridad, retención automática, auditoría sin contenido y configuración de producción **fail-closed**.

## Pruebas

```powershell
# Backend: 74 pruebas
cd backend
.\.venv\Scripts\python.exe -m pytest -q

# Android: 15 pruebas unitarias
cd android
.\gradlew.bat :app:testDebugUnitTest

# Sintaxis del panel
node --check backend\app\web\app.js
node --check backend\app\web\legal.js
```

Las pruebas backend cubren auth por cookies/CSRF, rotación de refresh, verificación de correo, recuperación de contraseña, pairing con declaración/asentimiento, WebSocket por subprotocolo y rechazo del ticket en query, idempotencia, rechazo de PII, revocación, exportación, borrado, evaluación de IA con failover/caché/breaker, contrato de los proveedores Groq y Gemini (vector sin texto, JSON schema estricto, reintento de esquema y de `thinkingConfig`, errores 429/503), deduplicación de alertas, tokens push (incluido el rechazo de JWTs de sesión), contrato FCM data-only y configuración de producción fail-closed. Android cubre sanitización, composición de vectores y la política de evaluación prioritaria.

El push FCM se verificó de punta a punta en un emulador `Pixel_8_Pro_API_35` con un proyecto Firebase real: token registrado, `messages:send` con HTTP 200, alerta recibida por WebSocket con el aviso local suprimido, notificación mostrada en segundo plano y también tras cerrar la app, y apertura de la pestaña **Alertas** al tocar el aviso. La ruta de IA se verificó contra Groq real con vectores sintéticos (`--repeat 5`: 5/5 JSON válido, P50 765 ms, P95 1000 ms con `openai/gpt-oss-20b`), de punta a punta desde el emulador (`ai_eval provider=groq latency_ms=812`, una sola alerta con narrativa) y también desde un teléfono físico Samsung SM-S948B con Android 16 (`HTTP 200`, `provider=groq`, 733 ms y una sola alerta persistida). Antes se había verificado el camino con Gemini (`provider=gemini`, 11,6 s). El free tier de Groq puede responder 429 al agotar cuota; en ese caso el sistema degrada a heurística.

## Demo en la nube (gratuita)

La demo de la defensa corre sin PC: backend y panel en Render Free + PostgreSQL
en Neon Free, con el teléfono como dispositivo del menor y una APK release
firmada. URL: <https://guardiania-74l4.onrender.com>

Los límites son reales y conviene decirlos en voz alta: la instancia de Render
duerme a los 15 minutos y tarda ~50 s en despertar, el plan free da 750 horas de
instancia al mes y Neon 100 CU-horas. No se mantiene despierta 24/7 con un
pinger: se abre el panel unos minutos antes de la demo. El detalle operativo,
las variables de entorno, el checklist y la verificación de punta a punta están
en [`docs/despliegue-gratuito.md`](docs/despliegue-gratuito.md).

## Producción

El arranque en `ENVIRONMENT=production` **falla** si falta cualquiera de estas condiciones:

- HTTPS en `PUBLIC_BASE_URL`; PostgreSQL (no SQLite); `SEED_DEMO=false`.
- `JWT_PRIVATE_KEY_PEM` inyectada; peppers fuertes y fuera del repositorio.
- `COOKIE_SECURE=true`, `CORS_ORIGINS` y `TRUSTED_HOSTS` explícitos.
- `DOCS_ENABLED=false`, `REQUIRE_EMAIL_VERIFICATION=true`, `EMAIL_DELIVERY=smtp`.
- SMTP con `SMTP_SSL` o `SMTP_STARTTLS` y validación de certificado.
- `AI_SEND_SNIPPET=false` y, si `FCM_ENABLED=true`, la cuenta de servicio de Firebase presente.

La IA y FCM son opcionales: sin claves ni credenciales, el sistema funciona con heurística local y WebSocket. Ver [`docs/firebase-setup.md`](docs/firebase-setup.md) para configurar el push.

Revisa [`backend/.env.example`](backend/.env.example) y [`docs/architecture.md`](docs/architecture.md) antes de desplegar. Para PostgreSQL local:

```powershell
docker compose up -d postgres
```

```dotenv
DATABASE_URL=postgresql+asyncpg://guardiania:guardiania_dev@localhost:5432/guardiania
```

## Marco legal y límites

Diseñado para **Ley N.° 21.719** (Chile), principios de Privacy by Design e ISO 27001. La documentación de cumplimiento está en [`docs/compliance/`](docs/compliance/README.md) y las páginas públicas en `/legal/{privacidad,terminos,consentimiento,seguridad,retencion}`.

**Estado honesto:** el código está endurecido y probado localmente, pero **no está certificado** ni autorizado para datos reales de menores. Faltan la revisión de un/a abogado/a habilitado/a en Chile, la auditoría de seguridad independiente, los DPA con encargados y la prueba end-to-end en infraestructura real. Ver la compuerta de lanzamiento.

## Documentación

- [`DESIGN.md`](DESIGN.md) — arquitectura, privacidad, reglas de riesgo y sistema visual.
- [`docs/architecture.md`](docs/architecture.md) — diagramas y contratos de la base.
- [`docs/arquitectura-ia.md`](docs/arquitectura-ia.md) — flujo de IA, presupuesto de latencia, push y degradación.
- [`docs/firebase-setup.md`](docs/firebase-setup.md) — configuración de FCM y diagnóstico.
- [`docs/despliegue-gratuito.md`](docs/despliegue-gratuito.md) — despliegue en Render + Neon, límites del plan free y checklist de demo.
- [`android/README.md`](android/README.md) — build, permisos, roles y operación.
- [`docs/compliance/`](docs/compliance/README.md) — ROPA, DPIA, retención, incidentes y compuerta.
- [`PRODUCT.md`](PRODUCT.md) — brief de producto y principios.

---

Hecho en Chile. Interfaz en español de Chile; código e identificadores en inglés.
