# Retención, exportación y eliminación

Versión: **2026-10-22** · Implementación: `backend/app/services/retention.py`, `backend/app/routers/account.py` · Estado: **código implementado; pruebas de operación pendientes**.

## 1. Plazos

| Dato | Configuración | Valor por defecto | Acción al vencer |
| --- | --- | --- | --- |
| Señales derivadas (`capturas_features`) | `RETENTION_DAYS` | 90 días | Borrado físico |
| Alertas (`alertas_riesgo`) | `RETENTION_DAYS` | 90 días | Borrado físico |
| Sesiones de tutor vencidas/revocadas | fijo | 30 días | Borrado físico |
| Tokens de verificación/restablecimiento | fijo | 24 h / 1 h o al uso | Borrado al usar/vencer |
| Auditoría (`eventos_auditoria`) | `AUDIT_RETENTION_DAYS` | 365 días | Borrado físico |
| Outbox publicado | fijo | 30 días | Borrado físico |
| Cuenta eliminada | inmediato | — | Anonimización + borrado relacionado |
| Copias de seguridad | propuesto | 35 días máximo | Rotación; no restaurar datos eliminados salvo recuperación |

El trabajo de retención corre al inicio y periódicamente mientras la API está viva. Registra conteos agregados, no identificadores.

## 2. Exportación

- Ruta: `GET /api/v1/account/export` con sesión de tutor.
- Formato: JSON descargable con perfil, consentimientos, dispositivos, vinculaciones, alertas, señales y auditoría reciente.
- Requiere autenticación; no se entrega a terceros.
- La descarga queda auditada.
- El panel web ofrece **Privacidad y datos → Descargar mis datos**.

## 3. Eliminación de cuenta

- Ruta: `POST /api/v1/account/delete`.
- Requiere contraseña y la frase exacta `ELIMINAR MI CUENTA`.
- Orden transaccional: consentimientos → sesiones/tokens → alertas → capturas → vinculaciones → dispositivos → sesiones de vinculación → cuenta.
- La fila de usuario se **anonimiza** (`eliminado-<uuid>@invalid.local`, hash inválido, alias "Cuenta eliminada") para preservar integridad referencial y evitar reutilización. No queda correo, nombre ni credencial utilizable.
- Se eliminan datos de dispositivos y señales; se conserva solo la constancia mínima de seguridad exigida.
- La eliminación revoca dispositivos y cierra sesiones.

## 4. Derechos y excepciones

- El tutor puede exportar y eliminar desde el producto.
- El menor o su representante pueden solicitar información, revocación o borrado por el canal de privacidad.
- Se puede conservar información específica ante obligación legal, orden judicial o investigación de seguridad; se limita el acceso, se documenta la base y se borra al cesar la obligación.
- No se conservan datos "por si acaso".

## 5. Copias de seguridad

Antes de producción se debe:

- [ ] Cifrar backups en reposo y en tránsito.
- [ ] Limitar acceso y registrar restauraciones.
- [ ] Definir retención máxima de 35 días.
- [ ] Probar restauración y posterior borrado.
- [ ] Documentar que una eliminación se propaga al siguiente ciclo.
- [ ] Verificar que los backups no contienen texto conversacional (no existe en el modelo).

## 6. Verificación operativa

| Prueba | Estado | Evidencia requerida |
| --- | --- | --- |
| Exportación JSON autenticada | Implementada; test automatizado | Captura/log de test |
| Eliminación con contraseña + frase | Implementada; test automatizado | Log de test |
| Borrado de señales/alertas por retención | Implementado | Ejecución programada y conteos |
| Borrado de tokens vencidos | Implementado | Conteos del job |
| Restauración de backup | **Pendiente** | Acta de prueba |
| Borrado en backups | **Pendiente** | Acta de prueba |
| Solicitud de derechos fuera del panel | **Pendiente** | Runbook y responsable |

## 7. Responsables

- Operaciones: por designar.
- Privacidad: `privacidad@guardiania.cl`.
- Seguridad: `seguridad@guardiania.cl`.

Cualquier cambio de plazo debe reflejarse en `RETENTION_DAYS`, `AUDIT_RETENTION_DAYS`, las páginas `/legal/retencion` y `/legal/privacidad`, y este documento.
