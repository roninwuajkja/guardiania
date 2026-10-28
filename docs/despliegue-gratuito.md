# Despliegue gratuito (demo sin depender del PC)

Este documento describe la instalación que se usó para la defensa: backend y
panel en la nube, sin computador encendido, y el teléfono como dispositivo del
menor con una APK **release** firmada.

> Es una **demo**, no producción. `ENVIRONMENT=dev` a propósito: mantener la
> cuenta demo sembrada y evitar la exigencia de SMTP real, correo verificado y
> `SEED_DEMO=false`. Para datos reales de menores aplica la compuerta de
> lanzamiento y el resto de [`docs/compliance/`](compliance/README.md).

## Topología

| Pieza | Servicio | Plan |
|---|---|---|
| Backend FastAPI + panel web | Render (`virginia`, Python 3.12) | Free |
| Base de datos | Neon Postgres 17.11 | Free |
| Cliente del menor | APK release en teléfono Android | — |
| Panel del tutor | Navegador (PC o teléfono), HTTPS | — |

URL pública de la demo: `https://guardiania-74l4.onrender.com`

`https://guardiania.onrender.com` **no** sirve: es de otro usuario y muestra la
pantalla "WELCOME TO RENDER". El sufijo `-74l4` es parte del nombre.

## Variables de entorno

Se configuran en Render → Environment. Los valores **no** van al repositorio.

| Variable | Para qué |
|---|---|
| `DATABASE_URL` | Conexión a Neon (ver formato abajo) |
| `JWT_PRIVATE_KEY_PEM` | Clave de firma de tokens; pegar el bloque PEM completo |
| `PAIRING_PEPPER`, `AUDIT_PEPPER` | Peppers de hashing de códigos y auditoría |
| `COOKIE_SECURE`, `CORS_ORIGINS`, `TRUSTED_HOSTS` | Cookies `__Host-` y orígenes permitidos |
| `ENVIRONMENT=dev`, `SEED_DEMO=true` | Cuenta demo sembrada para la defensa |
| `AI_ENABLED`, `GROQ_API_KEY` | Motor de IA (opcional; sin clave degrada a heurística) |
| `PUBLIC_BASE_URL` | Se deriva de `RENDER_EXTERNAL_URL`; no hace falta fijarla |

### Formato de `DATABASE_URL`

```
postgresql+asyncpg://USUARIO:CLAVE@HOST/guardiania?ssl=require
```

Dos errores que cuestan tiempo:

- **No usar `sslmode=require`**: `asyncpg` lanza
  `TypeError: connect() got an unexpected keyword argument 'sslmode'`. El
  parámetro correcto es `ssl=require`.
- **No usar el host `-pooler`** de Neon para esta combinación de driver y
  sesión: se usa el host directo del proyecto.

Si la clave trae caracteres especiales, hay que percent-encodearla.

## Límites reales del plan gratuito

Medidos en esta instalación, no teóricos:

| Límite | Valor | Consecuencia práctica |
|---|---|---|
| Render duerme tras inactividad | 15 min | El primer request tarda ~50 s |
| Horas de instancia Render | 750 h/mes | Alcanza para ~31 días de 24 h, pero sin margen si hay más servicios |
| Neon compute | 100 CU-horas/mes | Con 0.25 CU, ~400 h encendida al mes |
| Neon autosuspend | ~5 min sin consultas | Despertar la base suma unos segundos |
| Groq free tier | ~30 RPM | Al agotar cuota responde 429 y el sistema degrada a heurística |

**No mantener Render despierto 24/7 con un pinger.** El healthcheck
`/api/v1/health` no consulta la base, así que un monitor no despierta Neon; pero
mantener el proceso vivo todo el mes consume las 750 h de Render y deja el
sistema sin margen. La estrategia usada es: dormir normalmente y despertar
antes de la demo.

### Arranque en frío y timeouts

El cliente Android usa `connectTimeout=12 s`, `readTimeout=75 s` y
`writeTimeout=30 s`. El `readTimeout` se subió desde 20 s justamente porque el
primer request contra una instancia dormida tarda ~50 s. Además, `SyncWorker`
devuelve `Result.retry()` ante `IOException`, por lo que WorkManager reintenta
con backoff si el arranque en frío excede la ventana.

## Checklist para el día de la defensa

1. Abrir `https://guardiania-74l4.onrender.com` **3 minutos antes** y esperar a
   que cargue el panel (la primera carga despierta la instancia).
2. Iniciar sesión con la cuenta demo y confirmar que el panel lista los
   dispositivos.
3. Verificar que el teléfono muestre **VINCULACIÓN ACTIVA** y los tres permisos
   en **ACTIVO**: uso, notificaciones y batería sin restricciones.
4. Enviar una señal de prueba real (mensaje con la palabra `apuesta`) y
   confirmar que aparece la alerta en el panel.
5. Si la demo se graba, no dejar el pinger encendido después.

## Verificación realizada

| Prueba | Resultado |
|---|---|
| Panel por HTTPS público | `200`, CSP con `wss://…` |
| Login demo | `200`, cookies `__Host-` |
| Teléfono vinculado | `SM-S948B`, API 36, `ACTIVO` |
| Señal real desde el teléfono | captura `social` + flag `gambling` |
| Evaluación IA | `source=ai`, `provider=groq`, nivel `MEDIO` |
| Alerta en el panel | severidad `MEDIA`, score 56, estado `NUEVA` |
| WebSocket del tutor | `wss://` conectado, `tutor_ready`, `ping` cada 25 s |
| Privacidad | la tabla `capturas_features` no contiene texto del mensaje |

## Actualizar la APK

La firma release usa un keystore **fuera del repositorio**
(`C:\Users\Usuario\.guardiania\release.jks`) con sus credenciales en
`release.properties`. Sin ese par no se pueden publicar actualizaciones.

```powershell
cd android
Get-Content "C:\Users\Usuario\.guardiania\release.properties" | ForEach-Object {
    $i = $_.IndexOf('='); if ($i -gt 0) { Set-Item -Path ("Env:" + $_.Substring(0, $i)) -Value $_.Substring($i + 1) }
}
.\gradlew.bat testDebugUnitTest assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

`install -r` conserva la vinculación y los permisos porque la firma no cambia.
Instalar una build con **otra** firma obliga a desinstalar y volver a vincular.
