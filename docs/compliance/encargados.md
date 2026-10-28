# Encargados, subencargados y transferencias

Versión: **2026-10-22** · Estado: **por designar antes de producción**.

IA Guardian no requiere servicios externos para la demo. Un despliegue real sí necesitará, como mínimo, hosting, base de datos gestionada, correo transaccional, backups y monitoreo. La evaluación con IA y el push FCM son **opcionales**: sin claves ni credenciales el sistema sigue operando con heurística local y WebSocket. Ninguno de estos proveedores está autorizado todavía para datos reales de menores.

## 1. Categorías de encargados

| Categoría | Función | Datos | País | Estado |
| --- | --- | --- | --- | --- |
| Hosting/API | Ejecutar FastAPI y panel | Todos los datos del servicio | Por definir | **No designado** |
| Base de datos gestionada | PostgreSQL, backups | Cuenta, señales, alertas, auditoría | Por definir | **No designado** |
| Correo transaccional | Verificación y reset | Correo, token, metadatos | Por definir | **No designado** |
| Monitoreo de errores | Logs y alertas | IDs, rutas, errores; sin contenido | Por definir | **No designado** |
| Proveedor de IA (Groq / Gemini / OpenAI / Anthropic) | Clasificación de riesgo | **Solo vector derivado**: categoría, flags anonimizados, duración, conteos, hora local. Sin texto ni identificadores de contacto | EE. UU. / global (por confirmar) | **No designado**; requiere DPA, retención cero y prohibición de entrenamiento. Groq no entrena con inputs/outputs por defecto y no retiene la inferencia salvo logs de abuso/fallos (hasta 30 días); el free tier de Gemini puede usar los datos para mejorar el producto. Solo demo sintética hasta firmar el DPA |
| Firebase Cloud Messaging (Google) | Entrega de push | `alert_id`, severidad, categoría, alias del equipo y token FCM del tutor. Sin narrativa ni contenido | Global (Google) | **No designado**; FCM es opcional y se apaga con `FCM_ENABLED=false` |
| Soporte/helpdesk | Tickets | Datos que el tutor envíe | Por definir | **No designado** |
| Pasarela de pago (si aplica) | Cobro | Datos de facturación | Por definir | **No aplica aún** |
| Auditoría/forense | Incidentes | Evidencia acotada | Por definir | **No designado** |

## 2. Requisitos contractuales (DPA)

Cada encargado debe aceptar por escrito:

- Tratar datos solo según instrucciones documentadas.
- Confidencialidad y formación del personal.
- Medidas de seguridad equivalentes o superiores (cifrado, acceso mínimo, MFA).
- Notificar incidentes sin demora y colaborar en la investigación.
- No usar datos para fines propios, publicidad ni entrenamiento de modelos.
- **Proveedor de IA:** retención cero, sin entrenamiento, sin subencargados sin autorización, límites de tasa y registro de la región de procesamiento.
- **FCM:** usar solo el payload mínimo documentado; no enviar narrativa, texto ni identificadores del menor.
- Subencargados solo con autorización y mismas obligaciones.
- Asistencia en derechos (acceso, exportación, borrado).
- Borrado/devolución al terminar, incluidas copias.
- Permitir auditorías y responder cuestionarios.
- Cláusulas de transferencia internacional y evaluación de país.
- Plazos de notificación y responsables de contacto.

## 3. Transferencias internacionales

Antes de autorizar una transferencia se debe documentar:

1. País y marco legal de destino.
2. Necesidad de la transferencia y alternativa local.
3. Cláusulas contractuales o mecanismo válido.
4. Evaluación de acceso gubernamental y medidas suplementarias.
5. Cifrado en tránsito/reposo y gestión de claves.
6. Registro en ROPA y comunicación en la política de privacidad.

**No se autoriza transferir datos de menores a un país sin esta evaluación.**

## 4. Inventario que debe completarse

| Proveedor | Servicio | DPA firmado | Región | Subencargados | Última revisión |
| --- | --- | --- | --- | --- | --- |
| — | — | — | — | — | — |

## 5. Criterios de selección

- Certificaciones vigentes (ISO 27001, SOC 2) cuando existan.
- **IA:** retención cero verificable, no entrenamiento con los datos, región conocida y opción de desactivar la nube.
- **Push:** soporte de payload data-only/minimal y baja de tokens.
- Cifrado en reposo y claves gestionadas.
- MFA obligatoria y roles granulares.
- Backups cifrados y restauración probada.
- Registro de accesos y retención configurable.
- Transparencia de subencargados.
- Capacidad de borrado y exportación.
- Jurisdicción y mecanismos de transferencia.
- Historial de incidentes y respuesta.

## 6. Responsable

Privacidad: `privacidad@guardiania.cl` · Seguridad: `seguridad@guardiania.cl`.

La designación y firma de DPAs es una **compuerta bloqueante** en [`compuerta-lanzamiento.md`](compuerta-lanzamiento.md).
