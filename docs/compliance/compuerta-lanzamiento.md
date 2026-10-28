# Compuerta de lanzamiento — datos reales

> **No se debe procesar información real de niñas, niños o adolescentes hasta que todas las casillas bloqueantes estén firmadas.** Este documento es una lista de verificación, no una certificación. La aprobación legal y la auditoría de seguridad son externas al equipo de desarrollo.

Versión: **2026-10-28** · Release objetivo: `1.2.0` · Responsable de la compuerta: por designar.

## A. Legal y privacidad (bloqueante)

- [ ] Abogado/a habilitado/a en Chile revisó y aprobó privacidad, términos y consentimiento.
- [ ] Se confirmó la base de licitud para datos de menores y la validez del asentimiento.
- [ ] Se completó la identidad del responsable (razón social, RUT, domicilio, representante).
- [ ] Se revisó la DPIA y se firmó su aprobación.
- [ ] Se definieron plazos de respuesta ARCOP y vía de reclamo.
- [ ] Se revisaron obligaciones de registro/notificación ante la autoridad competente.
- [ ] Se aprobó el texto de retención y el de encargados.
- [ ] Se verificó que la política publicada coincide con la configuración (`LEGAL_VERSION`, `RETENTION_DAYS`).

**Firma legal:** ____________________  Fecha: __________

## B. Seguridad (bloqueante)

- [ ] Auditoría independiente y prueba de penetración completadas.
- [ ] Hallazgos críticos y altos cerrados o aceptados formalmente con compensación.
- [ ] Revisión de configuración cloud: MFA, mínimo privilegio, red privada, cifrado.
- [ ] Secretos en KMS/HSM; rotación probada.
- [ ] HTTPS obligatorio, HSTS, cookies seguras y CSRF verificados en el dominio real.
- [ ] Rate limit y bloqueo por intentos probados en producción.
- [ ] Monitoreo, alertas y on-call activos.
- [ ] Backups cifrados, restauración y borrado probados.
- [ ] Plan de incidentes y contactos actualizados; simulacro realizado.
- [ ] Dependencias escaneadas; sin CVEs críticos sin mitigar.
- [ ] App release firmada, sin cleartext, R8 activo y keystore fuera del repo.
- [ ] Se evaluó pinning/Play Integrity o se documentó la decisión.
- [ ] Claves de IA y cuenta de servicio de Firebase fuera del repo; rotación probada.
- [ ] `AI_SEND_SNIPPET=false` verificado en producción y ninguna ruta acepta texto.
- [ ] Push probado con FCM real en app abierta, en segundo plano y cerrada.

**Firma seguridad:** ____________________  Fecha: __________

## C. Producto y operación

- [ ] Flujo real: registro → verificación de correo → login → pairing con declaración y asentimiento.
- [ ] Panel web: cookie/CSRF, refresh, logout, exportación, consentimientos, borrado y cambio de clave.
- [ ] Android: modo tutor y modo menor; cambio de rol; pairing; revocación; refresh.
- [ ] Stream del tutor (`/ws/tutor`) probado con ticket y rechazo de query string.
- [ ] Evaluación con IA probada en modo degradado (sin claves) y con proveedor real.
- [ ] Push verificado con la app cerrada y con permiso de notificaciones denegado.
- [ ] Retención ejecutada y verificada con datos de prueba.
- [ ] Exportación JSON revisada por privacidad (sin contenido ni datos de terceros).
- [ ] Eliminación deja la cuenta inutilizable y borra datos relacionados.
- [ ] Correos de verificación/reset probados con SMTP real.
- [ ] Accesibilidad básica: contraste, foco, tamaños táctiles, textos claros.
- [ ] Copias de seguridad y continuidad documentadas.
- [ ] Soporte y canal de privacidad con responsables y plazos.

## D. Encargados y transferencias

- [ ] Hosting, base de datos, SMTP, monitoreo, backups, **proveedor de IA** y **FCM** designados.
- [ ] DPA firmado con cada encargado.
- [ ] DPA de IA con retención cero, no entrenamiento y región conocida.
- [ ] Subencargados identificados y aprobados.
- [ ] Transferencias internacionales evaluadas y documentadas.
- [ ] Registro actualizado en [`encargados.md`](encargados.md) y ROPA.

## E. Datos y demo

- [ ] `SEED_DEMO=false` en producción.
- [ ] Base de datos PostgreSQL; SQLite solo desarrollo.
- [ ] `ENVIRONMENT=production`, `PUBLIC_BASE_URL=https://...`.
- [ ] `JWT_PRIVATE_KEY_PEM`, `PAIRING_PEPPER`, `AUDIT_PEPPER` fuertes y fuera del repo.
- [ ] `COOKIE_SECURE=true`, `CORS_ORIGINS` y `TRUSTED_HOSTS` explícitos.
- [ ] `DOCS_ENABLED=false`, `REQUIRE_EMAIL_VERIFICATION=true`, `EMAIL_DELIVERY=smtp`.
- [ ] `AI_SEND_SNIPPET=false`; claves de IA fuera del repo (o `AI_ENABLED=false`).
- [ ] `FCM_ENABLED` coherente con la cuenta de servicio; `google-services.json` no versionado.
- [ ] Se borraron datos sintéticos/demo de cualquier entorno con datos reales.

## F. Criterio de liberación

La compuerta se considera **abierta** solo si:

1. A y B están firmadas.
2. No quedan hallazgos críticos/altos abiertos sin aceptación formal.
3. Se ejecutó un ensayo end-to-end con datos sintéticos en la infraestructura real.
4. Existe un plan de rollback y contacto de guardia.
5. La decisión queda registrada con fecha, versión y responsables.

## G. Estado actual (honesto)

| Área | Estado |
| --- | --- |
| Código backend endurecido | Implementado; **74 pruebas pasan localmente** |
| Web cookie/CSRF/legal/cuenta | Implementado; verificado en navegador local (cookies `httpOnly`, consentimientos, exportación, logout, refresh rotatorio, pairing con declaración, recuperación) |
| IA de riesgo (1.2.0) | Implementada con Groq → Gemini → OpenAI → Anthropic → heurística, caché, circuit breaker y presupuesto configurable; **verificada contra Groq real** con vectores sintéticos (8/8 JSON válido, P95 1000 ms) y alertas persistidas en emulador (`provider=groq`, 812 ms) y teléfono físico (`provider=groq`, 733 ms). Gemini queda como alternativa (20 req/día, 4–18 s). Las claves viven solo en el backend y no se versionan |
| WebSocket del tutor (1.2.0) | `/ws/tutor` con ticket `alerts:subscribe`; probado con ticket válido, query string rechazada y scope incorrecto rechazado |
| Push FCM (1.2.0) | Cliente HTTP v1 y app Android implementados; **verificado end-to-end en emulador** con proyecto Firebase real: token registrado, `messages:send` 200, aviso en segundo plano y con la app cerrada, y apertura de Alertas al tocar. La entrega sigue siendo best-effort y no se probó en hardware físico |
| Android tutor + menor | Implementado (1.2.0); verificado en emulador: rol tutor (inicio, alertas, dispositivos, vinculación con asentimiento, ajustes completos) y modo menor (permisos, señal de prueba, sincronización, desvinculación); **release sin firmar** compilado; **15 pruebas unitarias OK** |
| WebSocket de pairing | Ticket en `Sec-WebSocket-Protocol`; verificado sin ticket en URL ni access log |
| Correo y recuperación | Código implementado; enlaces en fragmento y TLS con validación; **SMTP real no probado** |
| Documentos legales | Borradores completos; **sin revisión de abogado/a** |
| Auditoría de seguridad | **No realizada** |
| Encargados/DPA | **No designados** (incluye IA y FCM) |
| Infraestructura real | **No probada** (backups, monitoreo, on-call, restore) |
| Producción | **No autorizada con datos reales** |

**Conclusión:** IA Guardian 1.2.0 está listo para una revisión seria y para una demo controlada con datos sintéticos. La IA y el push amplían la superficie de terceros, pero no cambian la Regla Cero: no viaja texto. **No está certificado ni autorizado todavía para datos reales de menores.**
