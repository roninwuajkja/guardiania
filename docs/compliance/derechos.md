# Procedimiento de derechos (ARCOP) y solicitudes de menores

Versión: **2026-10-22** · Estado: **procedimiento interno; requiere responsable y plazos legales confirmados por abogado/a**.

## 1. Alcance

Aplica a solicitudes de acceso, rectificación, cancelación, oposición y portabilidad (ARCOP), revocación de consentimiento, información sobre tratamiento y oposición a decisiones automatizadas, presentadas por:

- El tutor titular de la cuenta.
- La persona menor vinculada, directamente o con apoyo de un adulto de confianza.
- Un representante legal acreditado.

## 2. Canales

- Producto: **Privacidad y datos → Descargar mis datos / Eliminar cuenta** (tutor autenticado).
- Correo: `privacidad@guardiania.cl`.
- Seguridad: `seguridad@guardiania.cl` para incidentes, no para derechos.

No se solicitan datos innecesarios. Para proteger a un menor, la verificación debe ser proporcional y no convertirse en una nueva recolección.

## 3. Flujo

1. **Recepción:** registrar fecha, canal, solicitante y tipo de derecho. No incluir contenido conversacional.
2. **Verificación:** confirmar identidad del tutor mediante sesión autenticada o mecanismo alternativo razonable. Para el menor, verificar vínculo y priorizar su interés superior.
3. **Clasificación:** acceso/portabilidad, rectificación, cancelación, oposición, revocación o reclamo.
4. **Búsqueda:** localizar datos en cuenta, dispositivos, alertas, señales, auditoría y backups.
5. **Respuesta:** entregar exportación o confirmar acción; explicar límites legales si existen.
6. **Cierre:** registrar fecha, resultado y reclamación posterior. Auditar sin conservar el contenido de la solicitud más de lo necesario.

## 4. Plazos y costos

- Acusar recibo dentro de **5 días hábiles**.
- Resolver dentro del plazo legal aplicable; objetivo interno: **20 días hábiles**, prorrogable por complejidad con aviso.
- Primera copia gratuita; solicitudes manifiestamente abusivas o repetitivas pueden tener costo razonable o rechazo motivado.
- Si se rechaza, explicar base legal y vía de reclamo.

## 5. Derechos y respuesta

| Derecho | Acción | Límite |
| --- | --- | --- |
| Acceso | Exportación JSON + explicación de categorías | No revelar datos de terceros |
| Rectificación | Corregir correo/alias; no se puede "corregir" una señal histórica, se anota | Datos de auditoría inmutables |
| Cancelación | Eliminar cuenta o datos específicos si es posible | Conservación legal/seguridad |
| Oposición | Cesar tratamiento no esencial; evaluar base de licitud | Seguridad y obligaciones legales |
| Portabilidad | JSON estructurado | Datos derivados del titular |
| Revocación | Desvincular dispositivo y borrar datos locales/remotos | No afecta tratamiento previo |

## 6. Solicitudes de la persona menor

- Se escucha y registra su solicitud, incluso si el tutor la contradice.
- Se prioriza su interés superior y su derecho a la privacidad.
- Si pide revocar la vinculación, se informa al tutor y se facilita la revocación; si hay riesgo inmediato, se activa el protocolo de seguridad.
- No se comparte con el tutor información sensible adicional más allá de lo necesario para gestionar la solicitud.

## 7. Registro

| Campo | Ejemplo |
| --- | --- |
| ID de solicitud | `ARCOP-2026-0001` |
| Fecha recepción | UTC |
| Solicitante | Tutor / menor / representante |
| Tipo | Acceso, cancelación, etc. |
| Verificación | Método y fecha |
| Resultado | Entregado / corregido / denegado con base |
| Fecha de cierre | UTC |
| Reclamación | Autoridad / tribunal |

El registro no debe contener el contenido de conversaciones ni datos excesivos del menor.

## 8. Responsables

- Coordinación: por designar.
- Apoyo legal: abogado/a externo.
- Seguridad: `seguridad@guardiania.cl`.
- Privacidad: `privacidad@guardiania.cl`.

## 9. Pruebas

- [ ] Exportación y eliminación desde el panel.
- [ ] Solicitud por correo con verificación de identidad.
- [ ] Solicitud de un menor y respuesta adaptada.
- [ ] Denegación motivada y vía de reclamo.
- [ ] Borrado en backups y constancia.
- [ ] Simulacro de plazo y escalamiento.
