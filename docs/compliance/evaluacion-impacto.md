# Evaluación de impacto en protección de datos (DPIA)

Versión: **2026-10-22** · Estado: **borrador técnico; requiere revisión de abogado/a y responsable de tratamiento** · Sistema: IA Guardian (control parental con señales derivadas).

## 1. ¿Por qué se requiere una DPIA?

El tratamiento combina tres factores de alto riesgo:

1. **Personas menores de edad**, que requieren protección reforzada y asentimiento comprensible.
2. **Inferencias sobre bienestar y riesgo** (autolesión, violencia, grooming, consumo, etc.) a partir de señales de uso y metadatos de notificaciones.
3. **Asimetría de poder** entre tutor y menor, con riesgo de vigilancia, estigmatización o uso punitivo de una alerta.

Aunque no se trate texto conversacional, las señales pueden revelar hábitos íntimos y relaciones. La DPIA debe confirmar necesidad, proporcionalidad y salvaguardas antes de producción.

## 2. Descripción del tratamiento

| Elemento | Descripción |
| --- | --- |
| Datos | Cuenta del tutor, dispositivo, señales derivadas, alertas, auditoría |
| Titulares | Tutor adulto y persona menor vinculada |
| Origen | Registro del tutor; captura local en Android; metadatos de notificaciones |
| Destino | API FastAPI, PostgreSQL, panel web, correo transaccional, proveedor de IA (solo vector) y FCM (payload mínimo) |
| Lógica | Reglas deterministas + evaluación de IA multi-proveedor con degradación a heurística; no se envía contenido |
| Retención | 90 días señales/alertas; 365 auditoría; tokens al vencer; el proveedor de IA no debe retener |
| Derechos | Exportar, eliminar, revocar, consentir/retirar, reclamar |

## 3. Necesidad y proporcionalidad

**Necesidad.** El objetivo es apoyar una conversación temprana de cuidado. Se evita la vigilancia de contenido y se limita la captura a señales agregadas.

**Proporcionalidad.** La alternativa menos intrusiva es no recolectar; si se recolecta, el mínimo es categoría, duración, conteos y ventana temporal. El panel no muestra texto ni capturas. La retención es corta y el tutor puede revocar.

**Riesgo de exceso.** Un tutor podría usar alertas para castigar o aislar. Mitigaciones: lenguaje no acusatorio, explicación de falsos positivos, límites en la UI, guía de conversación y posibilidad de que el menor pida revisión.

## 4. Riesgos identificados

| ID | Riesgo | Probabilidad | Impacto | Mitigación implementada | Riesgo residual |
| --- | --- | --- | --- | --- | --- |
| R1 | Exposición de datos de menores por brecha | Media | Alto | TLS, SQLCipher, minimización, auth robusta, auditoría, retención | Medio; falta auditoría externa |
| R2 | Falso positivo que estigmatiza al menor | Media | Alto | Alertas explicables, severidad, copy no acusatorio, sin texto | Medio; requiere guía y revisión |
| R3 | Falso negativo que da falsa tranquilidad | Media | Alto | Lenguaje de límites; no prometer detección total | Medio; comunicar claramente |
| R4 | Tutor no autorizado o suplantación | Baja | Alto | Verificación de correo, contraseña Argon2id, bloqueo, sesión rotatoria | Bajo/medio |
| R5 | Reidentificación por contacto hasheado | Baja | Medio | El campo `hashed_contact_id` queda **reservado** y va `null` en el agente 1.2.0, por lo que esta regla no se activa; si se activa en el futuro, exigirá hash con pepper y acceso restringido | Bajo (no aplicable en 1.2.0) |
| R6 | Acceso físico al teléfono del menor | Media | Medio | SQLCipher, Keystore, pantalla de bloqueo del SO | Medio; depende del dispositivo |
| R7 | Captura excesiva o campos PII inyectados | Baja | Alto | Esquemas `extra=forbid`, sanitizer local, revisión de logs | Bajo |
| R8 | Uso de alertas para control coercitivo | Media | Alto | Consentimiento/asentimiento, revocación, copy, límites | Medio; requiere educación |
| R9 | Transferencia internacional sin garantías | Media | Medio | DPA y cláusulas exigidas; proveedores por designar | **Alto hasta designar** |
| R10 | Retención excesiva o backups no borrados | Baja | Medio | Job de retención, backups 35 días propuestos | Medio; falta prueba documentada |
| R11 | Proveedor de IA retiene o entrena con el vector | Baja | Alto | Solo se envía el vector sin texto; `AI_SEND_SNIPPET=false`; timeout, caché y degradación local; el proveedor se puede desactivar | **Medio hasta firmar DPA con retención cero y no entrenamiento** |
| R12 | Datos visibles en la pantalla bloqueada por push | Media | Medio | Payload mínimo sin narrativa; la notificación muestra solo severidad y alias; el detalle exige sesión autenticada | Bajo |
| R13 | Fuga o reutilización del token FCM | Baja | Medio | Token del tutor, reasignación al iniciar sesión, baja al cerrar sesión, desactivación de tokens inválidos, huella en logs | Bajo |
| R14 | Inyección de instrucciones a través de contenido | Baja | Bajo | El proveedor no recibe texto; la respuesta se valida como JSON estricto y cualquier desviación cae a la heurística | Bajo |

## 5. Salvaguardas específicas para menores

- Declaración expresa de tutoría y asentimiento separado en el dispositivo del menor.
- Lenguaje adaptado y posibilidad de detener la vinculación.
- Prohibición de publicidad y venta de datos.
- Minimización y retención corta.
- Acceso del tutor limitado a sus propios dispositivos.
- Registro de consentimiento con versión y fecha.
- Canal de privacidad para el tutor y para el menor.

## 6. Consulta y aprobación

Antes de producción se requiere:

- [ ] Revisión de este documento por abogado/a chileno/a.
- [ ] Confirmación de base de licitud y mayoría de edad del tutor.
- [ ] Revisión de la información al menor y del asentimiento.
- [ ] Auditoría independiente de seguridad y prueba de penetración.
- [ ] Designación de encargados (incluidos IA y FCM) y evaluación de transferencias.
- [ ] DPA del proveedor de IA con retención cero y prohibición de entrenamiento.
- [ ] Prueba de retención, exportación y borrado.
- [ ] Aprobación firmada del responsable.

**Conclusión provisional:** el diseño es proporcional y minimiza el contenido, pero el riesgo residual no es despreciable por tratarse de menores. La incorporación de IA y push no cambia la Regla Cero (no viaja texto), pero agrega terceros que deben quedar cubiertos por DPA. **No debe operar con datos reales hasta completar las casillas anteriores.**

## 7. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 2026-10-22 | 2026-10-22 | Evaluación inicial alineada al código endurecido |
| 1.2.0 | 2026-10-28 | Se agregan riesgos de proveedor de IA, push y token FCM |
