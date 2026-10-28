# Respuesta a incidentes de seguridad y privacidad

Versión: **2026-10-22** · Estado: **procedimiento interno; requiere simulacro y responsables designados**.

## 1. Principios

1. Contener primero, investigar después.
2. Preservar evidencia sin copiar datos innecesarios de menores.
3. No ocultar incidentes ni culpar a la persona afectada.
4. Documentar decisiones y tiempos.
5. Notificar cuando la ley o el contrato lo exijan, con asesoría legal.

## 2. Clasificación

| Severidad | Ejemplos | Acción inicial |
| --- | --- | --- |
| **S1 Crítico** | Acceso no autorizado a datos de menores, fuga de tokens, borrado masivo, ransomware | Incidente formal, contención inmediata, aviso a dirección y seguridad en 1 h |
| **S2 Alto** | Account takeover de tutor, bypass de autenticación, exposición de auditoría con IDs | Contención en 4 h, análisis y notificación según evaluación |
| **S3 Medio** | Abuso de rate limit, error de configuración sin exposición confirmada, pérdida de disponibilidad | Corrección en 24 h |
| **S4 Bajo** | Vulnerabilidad teórica, dependencia con CVSS bajo sin explotación | Backlog con plazo |

Un incidente que involucre datos de menores se trata como **S1/S2** hasta confirmar lo contrario.

## 3. Flujo

1. **Detección y reporte:** `seguridad@guardiania.cl`, alertas de monitoreo o reporte de usuario. Registrar hora, fuente y descripción.
2. **Triaje:** asignar severidad, incident commander y canal seguro. No usar chats con datos personales.
3. **Contención:** revocar sesiones/tokens, deshabilitar cuentas, bloquear IP, rotar secretos, aislar servicios. Preservar logs.
4. **Evaluación:** qué datos, cuántos titulares, menores involucrados, causa, alcance, si hubo exfiltración.
5. **Erradicación y recuperación:** corregir causa raíz, restaurar servicio, verificar integridad y monitorear.
6. **Notificación:** decidir con abogado/a si corresponde informar a titulares, tutores, autoridad y encargados. Plazos según normativa aplicable y contratos; objetivo interno: decisión documentada dentro de 72 h desde el conocimiento.
7. **Cierre:** informe post-incidente, acciones, responsables y fechas. Actualizar DPIA/ROPA si cambia el riesgo.

## 4. Evidencia mínima

- Cronología en UTC.
- Sistemas y versiones afectadas.
- Acciones tomadas y quién las autorizó.
- Datos afectados **sin incluir contenido conversacional** (no existe en el modelo).
- Copia de logs de auditoría y de acceso.
- Comunicaciones con titulares/autoridad.
- Causa raíz y medidas preventivas.

## 5. Comunicación a titulares

El mensaje debe explicar, en lenguaje claro: qué pasó, qué datos se vieron afectados, qué se hizo, qué puede hacer la persona, cómo contactar y qué no se sabe aún. Si hay menores, adaptar el mensaje al tutor y evitar estigmatización.

## 6. Roles

| Rol | Responsable | Nota |
| --- | --- | --- |
| Incident commander | por designar | Autoridad para contener |
| Seguridad técnica | por designar | Forense y remediación |
| Privacidad/legal | por designar / abogado/a externo | Notificaciones y DPIA |
| Comunicaciones | por designar | Mensajes aprobados |
| Soporte | por designar | Recepción de reportes |

## 7. Pruebas

- [ ] Simulacro de account takeover de tutor.
- [ ] Simulacro de fuga de base de datos.
- [ ] Simulacro de token de dispositivo robado.
- [ ] Prueba de rotación de secretos y revocación masiva.
- [ ] Prueba de restauración desde backup.
- [ ] Revisión de contactos y plazos con abogado/a.

## 8. Contacto

Reportes: `seguridad@guardiania.cl`. No incluir datos de menores en el reporte inicial; usar identificadores internos.
