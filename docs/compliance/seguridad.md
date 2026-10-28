# Política y arquitectura de seguridad

Versión: **2026-10-22** · Estado: **controles implementados en código; auditoría independiente pendiente**.

## 1. Modelo de amenazas

| Actor | Motivación | Vector | Control |
| --- | --- | --- | --- |
| Atacante externo | Robar datos de menores | API expuesta, credenciales débiles, dependencias | TLS, Argon2id, rate limit, bloqueo, validación estricta, actualizaciones |
| Tutor malicioso | Vigilar en exceso o suplantar | Acceso a cuenta, dispositivo compartido | Minimización, consentimiento/asentimiento, revocación, límites de UI |
| Persona con acceso físico | Leer el teléfono | Dispositivo desbloqueado, backup | SQLCipher, Keystore, `allowBackup=false`, pantalla de bloqueo del SO |
| Cliente modificado | Inyectar PII o eludir reglas | JSON adulterado | `extra="forbid"`, validación, hashes, límites de tamaño |
| Insider | Exfiltrar datos | Acceso a DB/logs | Mínimo privilegio, auditoría, separación de funciones, MFA del proveedor |
| Tercero/encargado | Incidente o transferencia indebida | Hosting, SMTP, backups | DPA, cifrado, evaluación de país, derecho de auditoría |

## 2. Controles implementados

### Autenticación y sesiones

- Contraseñas con **Argon2id**; nunca se almacenan en claro.
- Access tokens de tutor breves; refresh rotatorio con hash en servidor.
- `sid` y `ver` en el token; logout-all invalida por versión.
- Cookies web `HttpOnly`, `SameSite=Lax/Strict`, `Secure` en producción.
- CSRF de doble envío en escrituras con cookies.
- Bloqueo temporal por intentos fallidos y limitación de tasa.
- Verificación de correo y restablecimiento con tokens de un solo uso.
- Tokens de dispositivo separados y revocación inmediata.

### Entrada y API

- Esquemas Pydantic con `extra="forbid"` en vectores y dispositivos.
- Límite de tamaño de request (`MAX_REQUEST_BYTES`).
- IDs de request y cabeceras de seguridad (CSP, HSTS en producción, nosniff, frame-ancestors, permissions-policy).
- CORS con credenciales y orígenes explícitos.
- Hosts confiables en producción.
- Respuestas genéricas de autenticación; no se filtra si un correo existe.
- Manejador de errores genérico para no filtrar stack traces.

### Datos

- SQLCipher en Android; clave envuelta con AES/GCM del Android Keystore.
- Tokens y señales pendientes dentro de la base cifrada.
- El texto de notificaciones se procesa en memoria y se descarta.
- Retención automática y borrado/anonimización de cuenta.
- Exportación autenticada y auditada.
- Sin telemetría de contenido ni analítica de terceros.

### IA y notificaciones

- El evaluador recibe **solo** `DerivedFeatureVector`; no existe ruta con texto y `AI_SEND_SNIPPET` está cerrado.
- La respuesta del proveedor se valida como JSON estricto (nivel y categoría permitidos); un valor inválido cae a la heurística, no a la base.
- Presupuesto total de 1500 ms, caché por hash del vector y circuit breaker por proveedor para evitar dependencia y denegación de servicio.
- Las claves de IA y la cuenta de servicio de Firebase viven fuera del repositorio y nunca se imprimen en logs.
- El push FCM tiene payload mínimo (sin narrativa ni texto) y los tokens inválidos se desactivan automáticamente.
- Los tickets de WebSocket son efímeros, con scope, y viajan en `Sec-WebSocket-Protocol`, nunca en la URL.
- La app no registra el token FCM completo: solo una huella SHA-256 truncada en logs del backend.



- Configuración de producción **fail-closed**: exige HTTPS, PostgreSQL, JWT PEM, peppers fuertes, cookies seguras, CORS/trusted hosts explícitos, docs deshabilitados, SMTP y verificación de correo; rechaza demo y SQLite.
- Healthchecks, logs estructurados y auditoría sin contenido.
- Contenedor PostgreSQL opcional para desarrollo; producción debe usar servicio gestionado con cifrado y backups.

## 3. Brechas conocidas y compuertas

| Brecha | Riesgo | Requisito antes de producción |
| --- | --- | --- |
| Sin auditoría externa ni pentest | Alto | Contratar auditoría independiente; corregir hallazgos críticos/altos |
| Sin pinning/attestation de app | Medio | Evaluar Play Integrity / pinning y documentar |
| Sin MFA para tutores | Medio | Implementar TOTP/WebAuthn o justificar riesgo |
| Sin WAF/anti-bot gestionado | Medio | Configurar en el proveedor o justificar |
| Sin monitoreo/alertas 24/7 | Alto | SIEM/alertas, on-call y runbooks |
| Sin probar restauración de backups | Alto | Acta de restore y borrado |
| Secretos en variables de entorno | Medio | KMS/HSM y rotación documentada |
| Sin revisión de dependencias continua | Medio | SCA/renovate y política de parches |
| Proveedor de IA sin DPA ni retención cero | Alto | Firmar DPA, exigir no entrenamiento y evaluar alternativa local |
| Dependencia de disponibilidad de IA/FCM | Bajo | Heurística local y WebSocket como respaldo; el push es best-effort |
| Fuga de token FCM o notificación en pantalla bloqueada | Medio | Payload mínimo, reasignación por tutor, baja al cerrar sesión y desactivación de tokens inválidos |
| Sin MFA en el proveedor cloud | Alto | Exigir MFA y mínimo privilegio |
| Sin cifrado de backups verificado | Alto | Cifrado + prueba |

## 4. Gestión de secretos

- `JWT_PRIVATE_KEY_PEM`, `PAIRING_PEPPER`, `AUDIT_PEPPER`, claves de proveedores de IA, credenciales SMTP, base de datos y la cuenta de servicio de Firebase **nunca** se versionan.
- Producción debe inyectarlos desde un gestor de secretos (KMS/HSM/Vault) con auditoría y rotación.
- Rotar peppers invalida códigos/hashes previos; planificar migración.
- Rotar JWT requiere `kid` y ventana de convivencia.
- Los secretos no se imprimen en logs ni se incluyen en reportes de error.

## 5. Desarrollo seguro

- Revisión de código obligatoria para auth, pairing, captura y borrado.
- Pruebas de integración para CSRF, rotación de refresh, pairing, exportación y eliminación.
- R8/ProGuard en release; sin cleartext en release (solo debug).
- Firma de release con keystore externo y V2/V3; nunca claves en el repo.
- Dependencias fijadas; revisar CVEs antes de cada release.
- No incluir datos reales en fixtures, capturas ni demos.

## 6. Registro y monitoreo

- Auditoría: acción, usuario, entidad, resultado, hash IP/UA, fecha. Sin texto.
- Logs de aplicación: nivel, request ID, ruta, latencia, código. Sin cuerpos.
- Alertas mínimas: picos 401/403/429, errores 5xx, fallos de retención, accesos a export/delete, cambios de configuración.
- Retención de logs alineada a 365 días para auditoría; logs operativos según proveedor.

## 7. Divulgación responsable

`seguridad@guardiania.cl`. Confirmar recepción en 3 días hábiles, no acceder a datos de otros, no interrumpir el servicio y coordinar publicación después de la corrección.

## 8. Aprobación

Este documento no sustituye una auditoría. La aprobación de seguridad requiere firma de un auditor independiente y evidencia de cierre de hallazgos. Ver [`compuerta-lanzamiento.md`](compuerta-lanzamiento.md).
