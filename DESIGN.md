# Diseño y decisiones

IA Guardian es un proyecto de título sobre control parental con **privacidad por diseño**. La pregunta que ordena todo el producto no es “¿cómo vigilo más?”, sino “¿cuál es la mínima señal que permite a un tutor actuar sin leer la conversación de un niño?”.

Este documento registra la arquitectura, el modelo de privacidad, las reglas de riesgo, el sistema visual y las decisiones que sostienen el resultado. Los contratos detallados están en [`docs/architecture.md`](docs/architecture.md).

## 1. Tesis de producto

El tutor necesita dos respuestas cortas: **“¿está todo bien?”** y **“¿qué pasó que requiere una conversación?”**. No necesita un muro de mensajes, ni capturas de pantalla, ni un mapa en tiempo real.

Por eso el agente no envía contenido: envía un `DerivedFeatureVector` calculado en el teléfono. El panel no muestra conversaciones: muestra reglas explicadas y evidencia agregada. La revocación es una acción, no un trámite.

**Principio rector:** la privacidad se impone por arquitectura, no por una política de buenas intenciones. Si el servidor no tiene un campo para el texto, no puede filtrarlo.

## 2. Las tres capas

| Capa | Tecnología | Responsabilidad |
| --- | --- | --- |
| Agente | Kotlin, Compose, SQLCipher, WorkManager | Leer uso y metadatos, sanitizar en el dispositivo, encolar sin conexión, sincronizar |
| API | Python 3.11+, FastAPI async, SQLAlchemy 2, PostgreSQL/SQLite | Vinculación, autenticación, idempotencia, riesgo, outbox, WebSocket |
| Consola | HTML + CSS + JS sin build, fuentes locales | Resumen, vinculación, dispositivos, alertas, temas claro/oscuro |

No hay Redis ni cola externa. El **outbox transaccional** vive en la misma base y un dispatcher en proceso publica los eventos después del commit. Es suficiente para una demo de tesis y evita una dependencia operativa que no aporta valor al problema.

## 3. Modelo de privacidad

### Qué sale del teléfono

```json
{
  "category": "social",
  "flags": ["gambling"],
  "usage_duration_minutes": 0,
  "notification_count": 3,
  "time_window": { "start": "...", "end": "...", "tz_offset_minutes": -180 },
  "hashed_contact_id": null
}
```

- Categoría de la app (no el nombre del paquete en el panel, salvo el alias del dispositivo).
- Conteos y minutos, nunca títulos, remitentes, números ni extractos.
- Flags léxicos normalizados (`gambling`, `self_harm`, …), no el texto que los activó.
- Un hash salado y truncado de contacto, solo cuando la app expone un identificador estable. **No implementado en 1.7.12**: el campo va `null` y `contacto_recurrente` está reservada.
- Ventana temporal de las señales: 5 minutos (el servidor acepta hasta 60 minutos) y `event_id` determinista para idempotencia.

### Qué se queda en el teléfono

- Texto de la notificación: se lee en memoria, se normaliza (minúsculas, sin diacríticos) y se descarta al terminar `Sanitizer.sanitize`.
- Señales crudas de uso y notificación, en la cola local cifrada.
- Tokens de acceso y refresh, dentro de SQLCipher.
- La frase de cifrado de SQLCipher se genera al azar y se envuelve con una clave AES/GCM del Android Keystore (`SecretVault`). No hay preferencias en claro.

### Defensas

- `extra="forbid"` en `DeviceInfo`, `DerivedFeatureVector` y `SyncRequest`: un campo PII accidental rompe el contrato con 422 en vez de entrar a la base.
- El servidor valida que `device_id` coincida con el token; un teléfono no puede inyectar señales de otro.
- Los logs no reciben ni títulos ni texto; solo IDs, categorías y resultados.
- La revocación invalida refresh y access token en el siguiente uso (410), y el agente borra su estado local.
- El panel no tiene endpoint de contenido porque no existe esa entidad.

### Modelo de amenazas (resumen)

| Amenaza | Mitigación |
| --- | --- |
| Robo o pérdida del teléfono | SQLCipher + clave Keystore; tokens revocables; la cola no contiene texto |
| Fuga en la base del servidor | Solo vectores agregados y hashes; sin mensajes que filtrar |
| Intercepción de red | HTTPS en producción; JWT EdDSA; ticket WS de 60 s; CORS y CSP |
| Replay de capturas | `event_id` determinista + PK compuesta + cabecera de idempotencia |
| Fuerza bruta del código | 5 intentos por sesión, TTL de 10 min, HMAC con pepper, rate limit por IP |
| Reutilización de QR/código | QR 409; código 422 genérico; índice único entre sesiones pendientes |
| Tutor demasiado invasivo | El contrato no admite texto; la UI solo muestra reglas y evidencias |
| Notificación con contenido oculto | El listener lee solo lo que el sistema expone; el diseño asume que puede no haber preview |

## 4. Vinculación

El flujo está diseñado para funcionar en una cocina, con el tutor y el menor juntos, en menos de un minuto.

1. El panel o la app tutor crea una sesión `PENDIENTE`, un QR firmado (JWT, 10 min) y un código de 6 dígitos. El tutor debe declarar su calidad legal y el código se guarda como `HMAC-SHA256(pepper, code)`, nunca en claro.
2. El teléfono envía QR **o** código + metadata del dispositivo, junto con el asentimiento del menor.
3. El servidor incrementa intentos de forma atómica, valida, crea dispositivo y vinculación `ACTIVA`, marca la sesión `VERIFICADA` y escribe el evento de outbox **en una sola transacción**.
4. Tras el commit, el dispatcher publica `pairing_success` al WebSocket del panel. Si el WebSocket falla, la vinculación ya es válida y el panel se recupera al refrescar.
5. El teléfono guarda los tokens en SQLCipher y programa WorkManager cada 15 minutos.

**Refresh de dispositivo vs. tutor:** el refresh del teléfono del menor no rota a propósito: si la respuesta se pierde, el equipo no queda fuera sin que el tutor haya hecho nada. Se prefiere un refresh estable (30 días) y revocación explícita. En cambio, las sesiones de tutor (web/Android) sí rotan con compare-and-swap atómico y serialización en el cliente, porque ahí el riesgo de replay pesa más que la pérdida de una respuesta.

## 5. Captura y sincronización

- **Fuente de uso:** `UsageStatsManager` entrega minutos por app. Se agregan por categoría y hora.
- **Fuente de notificación:** `NotificationListenerService` entrega paquete, título y texto. Se usa `packageName` para categoría y `title + text` solo para reglas léxicas; luego se descarta.
- **Agrupación:** las señales se agrupan por `(categoría, bloque de 5 min)`. La captura de uso se agrupa por hora. Una repetición en un bloque posterior genera un evento nuevo y una alerta nueva.
- **Cola:** SQLCipher guarda hasta 4.000 señales; al superar el límite se descartan las más antiguas.
- **Envío:** WorkManager reintenta con backoff; el botón **Sincronizar ahora** usa el mismo motor.
- **Token expirado:** el agente recibe 401, llama a `/devices/refresh`, reintenta el lote una vez y, si el refresh falla o recibe 410, borra la vinculación local.
- **Idempotencia:** si el lote se reenvía, el servidor responde `duplicates` y no crea alertas nuevas.

Una limitación honesta: el listener no puede leer contenido que la app no expone en la notificación, ni mensajes cifrados de extremo a extremo más allá del preview. El producto no promete “ver todo”; promete **no invadir más de lo necesario**.

## 6. Motor de riesgo

Las reglas son deterministas y explicables. Cada alerta guarda el código, la etiqueta y una evidencia agregada (flag, ventana, minutos, repeticiones); nunca el texto.

| Regla | Disparador | Severidad | Score |
| --- | --- | --- | --- |
| `self_harm` | Frase de autolesión o plan suicida | CRÍTICA | 98 |
| `grooming` | Secreto/aislamiento o solicitud de fotos | CRÍTICA | 96 |
| `sextortion` | Chantaje con imágenes íntimas | CRÍTICA | 97 |
| `abuse` | Abuso o maltrato físico/sexual en el entorno cercano | CRÍTICA | 97 |
| `trafficking` | Trata, explotación o prostitución | CRÍTICA | 96 |
| `radicalization` | Reclutamiento o supremacía violenta | CRÍTICA | 92 |
| `sexual_content` | Términos sexuales explícitos | ALTA | 86 |
| `sexual_harassment` | Comentarios o tocamientos sexuales no deseados | ALTA | 86 |
| `violence` | Amenaza, golpiza o pelea | ALTA | 84 |
| `threat` | Amenaza ambigua o directa | ALTA | 76 |
| `weapons` | Armas o municiones | ALTA | 86 |
| `coercion` | Presión o chantaje | ALTA | 82 |
| `bullying` | Acoso o exclusión | ALTA | 80 |
| `hate_speech` | Discurso de odio o discriminación | ALTA | 78 |
| `insult_severe` | Insultos graves, slurs o deseo de muerte | ALTA | 78 |
| `insult_moderate` | Insultos directos frecuentes | MEDIA | 55 |
| `insult_mild` | Insultos leves o modismos despectivos | MEDIA | 44 |
| `eating_disorder` | Conductas de trastorno alimentario | ALTA | 80 |
| `doxxing` | Exposición de datos personales | ALTA | 82 |
| `stalking` | Acecho o seguimiento | ALTA | 82 |
| `runaway` | Fuga o riesgo de desaparición | ALTA | 84 |
| `dangerous_challenge` | Retos virales peligrosos | ALTA | 86 |
| `drugs` | Drogas, alcohol, vapeo | ALTA | 74 |
| `gambling` | Apuestas, casino, ruleta | MEDIA | 58 |
| `malestar` | Malestar emocional | MEDIA | 54 |
| `scam_points` | Canje de puntos, cupones o recompensas | MEDIA | 58 |
| `scam_casino` | Invitación a casino o bonos de apuestas | MEDIA | 60 |
| `scam_financial` | Inversión, cripto o rendimiento garantizado | MEDIA | 62 |
| `scam_account` | Phishing, suplantación o código de verificación | MEDIA | 64 |
| `scam_link` | Enlace acortado o host sospechoso con señuelo de saldo, premio o registro | MEDIA | 64 |
| `scam_prize` | Premios, sorteos o loterías sospechosas | MEDIA | 58 |
| `scam_delivery` | Paquete retenido, aduana o envío | MEDIA | 55 |
| `scam_job` | Oferta de trabajo fácil o ingresos rápidos | MEDIA | 55 |
| `scam_romance` | Estafa romántica o pedido de dinero | MEDIA | 60 |
| `scam` | Estafa o phishing genérico | MEDIA | 56 |
| `unknown_contact` | Regla reservada para contacto desconocido | MEDIA | 50 |
| `uso_nocturno` | Inicio local entre 00:00 y 06:00 con ≥ 20 min de uso | MEDIA | 55 |
| `uso_prolongado` | ≥ 180 min en la ventana | MEDIA / ALTA | 60 / 72 |
| `contacto_recurrente` | Mismo hash de contacto en ≥ 3 ventanas distintas en 24 h | ALTA | 76 |

La alerta se crea una vez por `(dispositivo, event_id, categoría)`. El panel ordena por fecha, permite filtrar por estado y ofrece acciones `VISTA`, `RECONOCIDA`, `DESCARTADA`.

**Ética del lenguaje:** los resúmenes están escritos como consejos para conversar (“Escucha sin minimizar…”) y no como diagnósticos. La interfaz repite que el motor es heurístico.

## 7. Sistema visual

**Dirección:** *la guardia* — una bitácora de turno de enfermería, no un panel de vigilancia. La consola se lee como un parte de novedades: tranquila, precisa, con la confianza de un instrumento.

- **Paleta:** pino profundo (`#081b17`–`#236052`) para el marco; papel clínico frío (`#eef2ed`) para el trabajo; salvia para calma; óxido (`#a33128`) solo para lo crítico; ámbar para atención. El modo oscuro invierte el papel, no la jerarquía.
- **Tipografía:** **Archivo** para UI y cifras; **Spline Sans Mono** para códigos, timestamps, eyebrows y la franja. Ambas se sirven localmente en woff2 (sin CDN).
- **Marca:** **Vínculo**, dos órbitas que se cuidan y dejan un espacio libre; el corte pequeño usa dos puntos para favicon y UI. El escudo anterior se retira. Wordmark Archivo 600 en curvas; fuentes y exports en `brand/`.
- **Firma:** la franja **Guardia** de 24 horas. Las señales aparecen como marcas, las horas nocturnas se sombrean y el presente se indica con una línea vertical. Es el único elemento “instrumental” y evita el gráfico de amenazas genérico.
- **Navegación:** índice de bitácora en el masthead (pestañas 01-04) con la pestaña activa unida a la hoja; dock flotante en móvil. En Android, isla flotante con indicador activo en salvia.
- **Componentes:** tarjetas de 14 px de radio, reglas de 1 px; píldoras de estado; tablas de dispositivos en dos filas en pantallas angostas.
- **Movimiento:** transiciones cortas (150–240 ms), `prefers-reduced-motion` respetado; la franja aparece sin animación de carga agresiva.
- **Accesibilidad:** foco visible, navegación por teclado, targets de 44 px, contraste AA, `aria-label` en iconos, texto de estado que no depende solo del color.

La app Android comparte la paleta y el lenguaje, pero cambia el registro: es una app de configuración y estado, no un panel de análisis. Usa Compose, tarjetas de papel, una cabecera de pino con el nombre del menor y una checklist de permisos con explicación de qué se comparte y qué no.

## 8. Decisiones y trade-offs

| Decisión | Alternativa descartada | Razón |
| --- | --- | --- |
| Kotlin nativo | Flutter | Acceso directo a UsageStats, NotificationListener, Keystore y SQLCipher; menos capas para una tesis |
| `AccessibilityService` opcional y sin contenido | Leer pantalla | Riesgo de política de Play y enorme superficie de privacidad; solo aporta la app en primer plano, nunca texto, y está apagado por defecto |
| SQLite para demo / PostgreSQL para prod | Solo PostgreSQL | La demo debe arrancar sin Docker; el mismo SQLAlchemy cubre ambos |
| SQLCipher + Keystore | Preferencias normales | El teléfono guarda señales y tokens; el cifrado en reposo es parte de la promesa |
| Outbox en proceso | Redis / Celery | Menos infraestructura, atomicidad con la transacción, suficiente para una instancia |
| Refresh no rotativo | Rotación estricta | Evita dejar al menor fuera por una respuesta perdida; la revocación resuelve el abuso |
| JWT EdDSA | HS256 | Llave pública verificable, firma moderna, `kid` para rotación |
| QR con ZXing embebido | ML Kit / CameraX | Build más simple y sin servicios de Google Play para el escáner |
| Seed sintético | Datos reales | Ética y reproducibilidad; todos los datos de la demo están etiquetados |

## 9. Limitaciones conocidas

- Las reglas léxicas son una heurística en español/inglés; pueden dar falsos positivos y falsos negativos. No son un diagnóstico clínico.
- El listener solo ve lo que Android expone en la notificación; una app puede ocultar el preview.
- `UsageStatsManager` es agregado y puede variar entre fabricantes.
- El rate limit es en memoria; para varias réplicas hay que moverlo a un almacén compartido.
- La verificación de correo y la recuperación de contraseña están implementadas; queda pendiente la publicación en Play Store y la entrega SMTP real.
- La app usa HTTP en claro solo para desarrollo; el build release exige HTTPS y no acepta certificados de usuario.
- La consola **web** no hace *streaming* de alertas: se actualiza al navegar/refrescar y su WebSocket se usa para la vinculación. La app **tutor Android** sí recibe alertas en vivo por `/ws/tutor` y, en segundo plano o con la app cerrada, por FCM. La entrega FCM es best-effort y depende del ahorro de batería del fabricante.
- El proyecto no sustituye asesoría legal; la implementación se inspira en la Ley N.° 21.719 y Privacy by Design, pero un despliegue real requiere revisión jurídica y de seguridad.

## 10. Pruebas

- **Backend:** **117 pruebas** con SQLite temporal (`test_api.py`, `test_ai_evaluator.py`, `test_gemini_provider.py`, `test_groq_provider.py`, `test_push.py`, `test_production.py`). Cubren vinculación con declaración/asentimiento, QR/código reutilizado, WebSocket por subprotocolo y rechazo del ticket en query, idempotencia, PII extra, revocación, auth por cookies/CSRF, rotación de refresh, verificación de correo, recuperación de contraseña, exportación, borrado, evaluación con IA (failover, caché, circuit breaker, presupuesto, multi-señal), contrato de Groq y Gemini (vector sin texto, JSON schema estricto, reintento de esquema y de `thinkingConfig`, errores 429/503), alertas y push (payload data-only, baja solo con `UNREGISTERED`/`NOT_FOUND`, rechazo de JWTs de sesión como token FCM), y configuración de producción fail-closed.
- **IA real:** `GroqProvider` probado contra la API de Groq: 8/8 evaluaciones con `provider=groq`, JSON válido, P50 765–780 ms y P95 1000 ms con `openai/gpt-oss-20b`, más alertas persistidas en emulador (`latency_ms=812`) y teléfono físico (`latency_ms=733`). `GeminiProvider` también se probó (JSON válido, 14,2 s). La degradación a heurística por 429/503 está cubierta por tests.
- **Push real:** verificado de punta a punta en emulador `Pixel_8_Pro_API_35` con un proyecto Firebase real: registro del token, `messages:send` 200, alerta por WebSocket sin aviso local duplicado, notificación en segundo plano y con la app cerrada, y apertura de **Alertas** al tocar el aviso.
- **Android:** 71 pruebas unitarias sobre lógica pura: `SanitizerTest` (56), `FeatureComposerTest` (6), `HighRiskPolicyTest` (6) y `ApiErrorsTest` (3), incluida la determinación de `event_id`, el agrupamiento por bloque de 5 min/categoría, la separación de repeticiones en bloques posteriores, la exclusión de señales viejas y la priorización de señales MEDIA/ALTA.
- **Humano/en navegador:** recorrido Playwright en Chrome headless 1440×1000: login demo, cookies `httpOnly`, consentimientos, exportación, logout, pairing bloqueado sin declaración, refresh concurrente con una sola rotación, `/recuperar` y token inválido de verificación sin fuga en la URL. Recorrido en emulador Pixel 8 Pro API 35: rol tutor, cambio a modo menor, pairing bloqueado sin asentimiento, vinculación exitosa y cambio de rol de vuelta; panel tutor completo: tarjetas de Seguridad/Mis datos/Historial de consentimientos, diálogo de cambio de contraseña con validaciones locales y error 400 del servidor («La contraseña actual no es correcta»), exportación que abre la hoja de compartir con el JSON (`FileProvider`), «cerrar sesión en todos los dispositivos» con confirmación y retorno al login, y reentrada con la clave demo intacta.

## 11. Procedencia de las capturas

Las capturas de `docs/screenshots/` se generaron el **22 de octubre de 2026** con datos sintéticos de la demo, sin información personal real.

| Archivo | Origen | Contexto |
| --- | --- | --- |
| `web-overview.png` | Panel, ruta `#/resumen` | Edge headless, 1440×1000, seed de 2 dispositivos |
| `web-alerts.png` | Panel, ruta `#/alertas` | Edge headless, 1440×1000, alertas sintéticas |
| `web-pairing.png` | Panel, ruta `#/vincular` | Edge headless, 1440×1000, sesión de pairing activa |
| `web-mobile.png` | Panel, ruta `#/resumen` | Edge headless, 390×844, DPR 2 |
| `android-pairing.png` | Agente, pantalla de vinculación | Emulador Pixel 8 Pro API 35, 1344×2992, build debug 1.2.0 |
| `android-status.png` | Agente, pantalla de estado | Mismo emulador; permisos activos y sync reciente |

Las cifras de uso y las alertas son parte del seed; no provienen de un menor real.
