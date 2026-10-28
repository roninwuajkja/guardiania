# Paquete de cumplimiento — IA Guardian

> **Estado:** artefactos técnicos y legales en **borrador de producción**. No constituyen certificación jurídica ni una auditoría de seguridad independiente. Antes de tratar datos reales de niñas, niños y adolescentes, un/a abogado/a habilitado/a en Chile debe revisar y aprobar los documentos legales, y un equipo independiente debe auditar la seguridad. Ver [`compuerta-lanzamiento.md`](compuerta-lanzamiento.md).

Este directorio reúne la documentación que un responsable de tratamiento necesita para operar IA Guardian con datos reales: registro de actividades, evaluación de impacto, retención, incidentes, seguridad, encargados y procedimiento de derechos. Las páginas públicas equivalentes se sirven en `/legal/{privacidad,terminos,consentimiento,seguridad,retencion}`.

## Índice

| Documento | Uso | Estado |
| --- | --- | --- |
| [`registro-tratamiento.md`](registro-tratamiento.md) | ROPA, finalidades, bases de licitud y categorías | Borrador técnico |
| [`evaluacion-impacto.md`](evaluacion-impacto.md) | DPIA para datos de menores y señales de riesgo | Borrador; requiere revisión |
| [`retencion.md`](retencion.md) | Plazos, borrado, exportación y copias | Implementado + pendiente de prueba |
| [`incidentes.md`](incidentes.md) | Respuesta, severidad y notificación | Procedimiento interno |
| [`seguridad.md`](seguridad.md) | Controles, modelo de amenazas y brechas | Implementado parcialmente |
| [`encargados.md`](encargados.md) | Subencargados, DPA y transferencias | Por designar |
| [`derechos.md`](derechos.md) | ARCOP, tutores y personas menores | Procedimiento interno |
| [`consentimiento.md`](consentimiento.md) | Consentimiento y asentimiento | Modelo para revisión |
| [`compuerta-lanzamiento.md`](compuerta-lanzamiento.md) | Checklist de aprobación antes de datos reales | **Bloqueante** |

## Principios que no se negocian

1. **Minimización radical:** el texto de notificaciones, fotos, audio y contactos en claro nunca se persisten ni se transmiten. Solo viajan señales derivadas.
2. **Dos voluntades:** el tutor declara su calidad legal y la persona menor otorga asentimiento en su propio dispositivo. Ambos quedan registrados con versión y fecha.
3. **Derechos ejecutables:** exportación y eliminación están en el producto, no solo en un correo de soporte.
4. **Trazabilidad sin contenido:** la auditoría registra acciones, identificadores y hashes de red; no guarda conversaciones.
5. **Fallo cerrado:** la configuración de producción rechaza SQLite, demo, HTTP, secretos de desarrollo y ausencia de verificación de correo.
6. **Nada de marketing con menores:** no se usan datos para publicidad, perfilado comercial ni entrenamiento de terceros.

## Responsable y contactos

Los datos de razón social, RUT, domicilio y representante legal deben completarse antes de la aprobación. Mientras tanto:

- Privacidad: `privacidad@guardiania.cl`
- Seguridad: `seguridad@guardiania.cl`
- Soporte: `soporte@guardiania.cl`

## Cómo mantener este paquete

- Cambiar una finalidad, categoría de dato o encargado exige actualizar ROPA y DPIA, subir la versión legal y volver a obtener consentimiento cuando corresponda.
- Cambiar la retención exige actualizar `RETENTION_DAYS`/`AUDIT_RETENTION_DAYS`, las páginas legales y este paquete.
- Cada release debe adjuntar la checklist firmada de `compuerta-lanzamiento.md`.
- Los documentos públicos y este paquete deben versionarse juntos; la versión vigente es `2026-10-28` (release 1.2.0, que agrega evaluación con IA y push como encargados).
