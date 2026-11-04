# Changelog

Todas las versiones relevantes de IA Guardian. El detalle técnico vive en
[`docs/architecture.md`](docs/architecture.md) y
[`docs/arquitectura-ia.md`](docs/arquitectura-ia.md).

> Las notas de cada versión se publican en [GitHub Releases](https://github.com/roninwuajkja/guardiania/releases). Este archivo conserva el historial completo.

## 1.7.2 — 2026-11-03

### Notificaciones del menor

- El filtro persistido de deduplicación se aplica también a los avisos en
  tiempo real: si Samsung reenvía una notificación ya procesada al reconectar
  el listener, no se genera una alerta duplicada.
- La marca usa la hora original del aviso y solo avanza, incluso si dos
  escrituras coinciden.
- Android `1.7.2` (`versionCode` 11); backend `1.7.2`.

### Verificación

- Un aviso nuevo de “Te voy a tocar” generó exactamente **una** alerta de
  amenaza.
- Tras desactivar y reactivar el listener con ese aviso ya procesado, no
  apareció una segunda alerta.
- 61 pruebas unitarias Android y 108 backend.

## 1.7.1 — 2026-11-03

### Notificaciones del menor

- El listener de notificaciones ahora pide **reconexión explícita** al sistema
  cuando se desenlaza, en lugar de esperar a que Samsung lo vuelva a enlazar.
- El servicio en primer plano reintenta la reconexión cada 5 minutos y al
  arrancar, para reducir los huecos en los que se pierden notificaciones.
- **Escaneo de notificaciones activas**: al conectar y cada 3 minutos se revisa
  la bandeja para recuperar avisos que Samsung no entregó mientras el teléfono
  estaba bloqueado. La deduplicación usa la hora real del aviso y una marca
  persistida, así que no se repiten alertas tras reiniciar ni se saltan avisos
  del mismo lote.
- Android `1.7.1` (`versionCode` 10); backend `1.7.1`.

### Verificación

- Al instalar la APK, un aviso de “drogas” que seguía en la bandeja se recuperó
  y el tutor recibió la alerta correspondiente.
- Con el listener desactivado se publicó “Te odio”; al reactivarlo, el escaneo
  recuperó el aviso y el tutor recibió **Insulto leve**.
- 61 pruebas unitarias Android y 108 backend.

## 1.7.0 — 2026-11-03

### Catálogo local ampliado

- Nuevas señales: **acoso sexual**, **amenaza** y **acecho/seguimiento**.
- Expansión de todas las familias: autolesión, grooming, sextorsión, abuso,
  trata, radicalización, violencia, armas, coerción, bullying, discurso de odio,
  insultos, trastorno alimentario, doxxing, fuga, retos peligrosos, drogas,
  apuestas, malestar y estafas.
- Se incorporan variantes chilenas, modismos, insultos leves/moderados/graves y
  frases como “te voy a tocar”, “a ver tu culo”, “te odio”, “tonta/o” y “drogas”.
- Normalización anti-ofuscación: quita caracteres invisibles, convierte leetspeak
  (`t0nt@`, `dr0g4s`) y colapsa letras repetidas (`toooonta`).
- Se evitan falsos positivos evidentes como “te voy a tocar el timbre” o
  “corté el pasto”.

### Verificación

- 108 pruebas backend y 61 pruebas unitarias Android.
- Android `1.7.0` (`versionCode` 9) y backend `1.7.0`.

## 1.6.1 — 2026-11-03

### Notificaciones del tutor

- La prueba push ahora se **programa con 10 segundos de retraso** para que el
  tutor pueda cerrar la app antes de que FCM la entregue.
- La app muestra “Push programada” y el número de tokens; si no hay token o FCM
  no está configurado, lo indica explícitamente.
- La tarea programada se mantiene viva hasta que FCM confirma el envío, incluso
  después de responder la petición.
- Las notificaciones de insultos y estafas usan su etiqueta específica en lugar
  de “Señal de riesgo”.
- Android `1.6.1` (`versionCode` 8) y backend `1.6.1`.

## 1.6.0 — 2026-11-03

### Detección de lenguaje y estafas

- Insultos en tres niveles: **leve, moderado y grave**, con modismos chilenos y
  términos despectivos.
- Familias de estafa separadas: canje de puntos, invitación a casino/apuestas,
  inversión o cripto, suplantación/phishing, premios, envíos/aduanas, ofertas de
  trabajo y estafa romántica.
- La IA recibe las señales por separado. Un insulto grave o una estafa que pide
  dinero, códigos o datos sube a **ALTO**; dos señales distintas también.
- El catálogo local pasa de 21 a **32 señales**.

### Versión y distribución

- Backend y Android **1.6.0**; APK `versionCode` 7.
- Nuevo release `v1.6.0` con APK y changelog. `v1.5.0` y `v1.4.0` quedan como
  releases anteriores.

## 1.5.0 — 2026-11-03

### Panel del tutor

- **Guardia de hoy** rediseñada: 24 columnas horarias con barras apiladas de
  actividad, atención y alertas, conteos visibles y eje 00–24.
- La banda nocturna se conserva y las marcas se agrupan por hora para evitar
  solapamientos cuando hay muchas señales.
- Se consolidan los ajustes de lectura: QR web escalable, código manual centrado
  en Android, mensajes de alerta sin duplicado y detalle solo cuando aporta
  evidencia o consejo.

### Versión y distribución

- Backend y Android **1.5.0**; APK `versionCode` 6.
- Nuevo tag y release `v1.5.0` con APK firmada y changelog adjunto.
- `v1.4.0` queda como release anterior y no se vuelve a mover.

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

### Correcciones de UI

- El QR del panel web ahora incluye `viewBox` y escala completo dentro de su tarjeta.
- El código manual de la app Android quedó centrado, junto con su etiqueta y el botón de copiar.
- En “Señales recientes”, el mensaje solo se puede expandir si no cabe en dos líneas; al expandirlo se ve una sola vez, sin duplicar el texto.
- En **Alertas**, “Por qué se generó” ya no repite el mensaje: aparece solo cuando aporta evidencia o consejo, y el texto largo se expande en su lugar.

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
