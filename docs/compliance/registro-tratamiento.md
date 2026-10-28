# Registro de actividades de tratamiento (ROPA)

Versión: **2026-10-22** · Estado: **borrador para revisión jurídica** · Responsable: por completar (razón social, RUT, domicilio).

## 1. Actividades

| # | Actividad | Titulares | Categorías | Finalidad | Base de licitud | Destinatarios | Retención |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | Registro y autenticación de tutor | Tutor adulto | Correo, alias, hash Argon2id, sesiones, versión de consentimiento, hash IP/UA | Crear cuenta, autenticar, prevenir abuso, acreditar consentimiento | Ejecución de contrato; interés legítimo en seguridad | Hosting; correo transaccional | Cuenta activa; auditoría 365 días |
| A2 | Verificación de correo y recuperación | Tutor | Correo, token hasheado, fecha, hash IP/UA | Confirmar identidad y permitir recuperación | Ejecución de contrato; seguridad | SMTP | Token 24 h / 1 h o al uso |
| A3 | Vinculación tutor–menor | Tutor y menor | IDs, alias del menor, modelo/API, install_id hasheado, fechas, declaración de tutoría | Crear y mantener la vinculación | Consentimiento del tutor; asentimiento del menor | Hosting | Mientras la vinculación esté activa; auditoría 365 días |
| A4 | Captura local de señales | Menor | Categoría de app, minutos, conteos léxicos, ventana temporal (el campo de contacto hasheado queda **reservado**: no se envía en 1.2.0) | Generar señales de bienestar y alertas explicables | Consentimiento/asentimiento; interés legítimo con salvaguardas | Hosting (solo vector derivado) | 90 días por defecto |
| A5 | Evaluación asistida por IA | Tutor y menor | Vector derivado (categoría, flags anonimizados, duración, conteos, hora local); narrativa y consejo resultantes | Priorizar señales y sugerir una conversación de cuidado | Consentimiento/asentimiento; interés legítimo con salvaguardas | Proveedor de IA (solo vector, con DPA y sin entrenamiento); hosting | El proveedor no debe retener; resultado en alertas 90 días |
| A6 | Notificación al tutor (WebSocket/FCM) | Tutor | `alert_id`, severidad, categoría, alias del equipo, token FCM del tutor | Avisar de una señal sin exponer contenido | Ejecución del servicio; consentimiento | Firebase Cloud Messaging (Google); hosting | Token mientras la sesión esté activa; alerta 90 días |
| A7 | Generación de alertas | Tutor y menor | Severidad, reglas activadas, resumen generado, estado | Facilitar una conversación de cuidado | Consentimiento/asentimiento | Hosting | 90 días |
| A8 | Panel y soporte | Tutor | Datos de cuenta, mensajes de soporte | Atender consultas y operar el servicio | Ejecución de contrato | Soporte; hosting | Mientras sea necesario |
| A9 | Seguridad y auditoría | Tutor, menor, atacantes | Acción, IDs, resultado, hash IP/UA, fechas | Detectar abuso, investigar incidentes, demostrar trazabilidad | Interés legítimo; obligación legal cuando aplique | Hosting; auditoría externa bajo NDA | 365 días |
| A10 | Facturación (si se cobra) | Tutor | Datos de facturación | Cobro y obligaciones tributarias | Obligación legal / contrato | Pasarela de pago (por designar) | Plazo tributario aplicable |

## 2. Datos que NO se tratan

- Texto de notificaciones, mensajes, correos o chats.
- Fotos, videos, audio, archivos.
- Ubicación precisa, contactos en claro, agenda, historial de llamadas.
- Credenciales de terceros o contenido de pantalla.
- Datos biométricos o de salud.
- Datos de menores con fines publicitarios o de perfilado comercial.

El servidor valida `extra="forbid"` en los vectores, de modo que un cliente modificado no pueda inyectar texto libre en campos no previstos. Esto es una defensa técnica, no una autorización para enviar contenido.

## 3. Clasificación de datos

| Nivel | Ejemplos | Controles |
| --- | --- | --- |
| **Restringido** | Tokens de sesión, refresh tokens, claves, credenciales SMTP | Cifrado en tránsito/reposo, acceso mínimo, rotación, nunca en logs |
| **Sensible contextual** | Señales de riesgo de un menor identificable, alertas (el campo `hashed_contact_id` existe pero va `null` en 1.2.0) | Cifrado, acceso por tutor, retención corta, auditoría, sin texto crudo |
| **Confidencial** | Correo, alias, IDs de dispositivo, historial de auditoría | Acceso autenticado, minimización, retención |
| **Interno** | Métricas agregadas, versiones, runbooks | Acceso del equipo, sin identificación innecesaria |

Los datos de menores son **sensibles por contexto** aunque no sean categorías especiales clásicas: revelan hábitos, relaciones y posibles riesgos. Se aplica el estándar más alto de necesidad y proporcionalidad.

## 4. Flujos y encargados

```text
Menor (SQLCipher) --vector derivado/TLS--> API FastAPI --PostgreSQL--> Panel tutor
                                             |--> Proveedor de IA (solo vector; sin texto)
                                             |--> FCM (alert_id, severidad, categoría, alias)
                                             |--> SMTP (correo transaccional)
                                             |--> logs/auditoría (sin contenido)
```

Los encargados concretos, países y contratos están en [`encargados.md`](encargados.md). Cualquier transferencia internacional requiere cláusulas contractuales y evaluación del país destino antes de producción.

## 5. Evaluación de necesidad y proporcionalidad

- La captura ocurre en el dispositivo y descarta el contenido antes de persistir.
- Solo se conservan señales agregadas y una ventana temporal acotada.
- El panel muestra explicaciones, no pantallas ni texto.
- La retención por defecto es 90 días para señales y alertas; 365 para auditoría.
- El tutor puede exportar y eliminar; el menor puede pedir la revocación.
- No se usan datos para publicidad ni venta.

## 6. Brechas y decisiones pendientes

- Completar identidad del responsable y representante legal.
- Designar hosting, SMTP, monitoreo, backups, **proveedor de IA** y **FCM** con DPA.
- Exigir al proveedor de IA retención cero y prohibición de entrenamiento con los vectores.
- Validar la base de licitud para cada país de operación.
- Aprobar la DPIA y la compuerta de lanzamiento.
- Definir si se cobrará y, en tal caso, el encargado de pagos.
- Verificar obligaciones de registro/notificación ante la autoridad chilena cuando corresponda.

## 7. Historial de versiones

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 2026-10-22 | 2026-10-22 | Versión inicial de producción endurecida |
| 1.2.0 | 2026-10-28 | Se agregan evaluación con IA (solo vector) y notificación push (payload mínimo) como actividades y encargados |
