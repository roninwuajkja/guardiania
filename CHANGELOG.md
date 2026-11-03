# Changelog

Todas las versiones relevantes de IA Guardian. El detalle técnico vive en
[`docs/architecture.md`](docs/architecture.md) y
[`docs/arquitectura-ia.md`](docs/arquitectura-ia.md).

## 1.4.0 — 2026-11-03

### Detección de riesgo

- El teléfono reconoce **21 señales** (antes 10), con reglas locales para
  sextorsión, abuso o maltrato, trata o explotación, radicalización, armas,
  discurso de odio, trastorno alimentario, doxxing, fuga, retos virales
  peligrosos y estafas/phishing.
- La IA recibe `flags_criticos`, `flags_graves` y `cantidad_flags`; una señal
  crítica o grave, o dos señales distintas, elevan el nivel a ALTO.
- Sextorsión, abuso, trata, radicalización, autolesión y grooming se reportan
  como **CRÍTICA**.
- Las señales que el servidor aún no conoce ya no se ignoran: generan una alerta
  MEDIA genérica.
- El panel muestra el nombre específico de la señal (“Sextorsión”,
  “Radicalización”) en lugar de la categoría genérica de IA.

### App Android

- Nuevo botón **Definir código / Cambiar código** en **Equipos**. El tutor puede
  crear, cambiar o quitar un PIN de 6 dígitos; el teléfono lo exige para
  desvincularse. El tutor siempre puede revocar el acceso sin el PIN.
- El diálogo usa el mismo lenguaje visual que el resto de la app y valida que el
  código tenga 6 dígitos y que la confirmación coincida.
- El header del menor respeta la barra de estado y el logo nuevo se aplica al
  launcher y a la marca interna.
- Versión `1.4.0` (`versionCode` 5), APK release firmada.

### Documentación y presentación

- README sin capturas: solo queda el banner de portada y la documentación.
- Diagramas ASCII reemplazados por Mermaid en `android/README.md`,
  `docs/arquitectura-ia.md` y `docs/compliance/registro-tratamiento.md`.
- Banner y logo nuevos, con transparencia real fuera de la tarjeta.
- Documentación de los límites de cobertura: la app ve notificaciones y uso, no
  chats, fotos, audio ni conversaciones dentro de las apps.

### Verificación

- 104 pruebas backend y 39 pruebas unitarias Android.
- Flujo probado en teléfono físico y emulador: PIN exigido al desvincular,
  alerta CRÍTICA de sextorsión y alerta de radicalización.

## 1.3.0 — 2026-11-02

- QR de vinculación corto (59 caracteres, 37×37 módulos) y revinculación del
  mismo teléfono.
- Código de desvinculación en el panel web y equipos desvinculados ocultables.
- Aviso de teléfono sin señal/reconexión; estado de permisos y No molestar.
- Push FCM para todas las severidades, con severidad, categoría y resumen
  derivado; botón de prueba en la app.
- Servicio en primer plano para el listener y el sync prioritario.
- Panel en vivo con WebSocket, respaldo cada 15 s y estáticos versionados.
- IA con mínimos por contexto y red de seguridad determinista.
- Logo, banner, modales del tutor y detalle completo de “Por qué se generó”.
