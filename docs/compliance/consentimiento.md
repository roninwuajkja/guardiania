# Consentimiento informado y asentimiento del menor

Versión: **2026-10-22** · Documento público: `/legal/consentimiento` · Estado: **modelo para revisión legal**.

## 1. Doble registro

IA Guardian exige dos actos distintos:

1. **Declaración y consentimiento del tutor:** al registrarse acepta términos/privacidad y declara ejercer patria potestad, tutela o cuidado personal. Se registra en `ConsentRecord` con tipo `terminos`, `privacidad` y `tutoria`.
2. **Asentimiento de la persona menor:** en el dispositivo del menor, antes de verificar el código/QR, marca que comprende qué señales se comparten y acepta la vinculación. Se envía `child_assent=true` y el servidor registra `asentimiento_menor` asociado a la vinculación.

El asentimiento no reemplaza la autorización del tutor; la complementa.

## 2. Contenido mínimo explicado al menor

- Qué se comparte: categorías de uso, minutos, conteos de indicadores y ventana temporal.
- Qué **no** se comparte: textos, fotos, audio, contactos en claro, ubicación precisa.
- Para qué: recibir alertas que ayuden a conversar con su tutor.
- Quién lo verá: el tutor vinculado.
- Cómo detenerlo: pedir al tutor que revoque el dispositivo; también puede plantearlo por el canal de privacidad.
- Límites: puede haber falsos positivos; no es un diagnóstico ni una prueba.

## 3. Momento y forma

- La explicación ocurre antes de generar/verificar la vinculación.
- El menor debe estar presente y usar su propio dispositivo.
- La casilla no viene premarcada.
- Si no acepta, no se envía la verificación y no se crea la vinculación.
- El tutor recibe una guía de conversación en el panel y en la documentación.

## 4. Registro técnico

| Campo | Contenido |
| --- | --- |
| Tipo | `terminos`, `privacidad`, `tutoria`, `asentimiento_menor` |
| Versión | `LEGAL_VERSION` (2026-10-22) |
| Fecha | UTC |
| Tutor | ID interno |
| Vinculación | ID de la vinculación, cuando aplica |
| Origen | `seed_demo`, `dispositivo_menor`, panel |
| Red | Hash de IP y user-agent (no IP en claro) |

No se guarda el texto de la conversación ni una firma manuscrita.

## 5. Retiro

- El tutor puede revocar el dispositivo desde el panel.
- El tutor puede eliminar la cuenta; se borran consentimientos y datos asociados.
- El menor puede solicitar la revocación; el procedimiento está en [`derechos.md`](derechos.md).
- El retiro no afecta la licitud del tratamiento previo.

## 6. Revisión legal requerida

Antes de producción, un/a abogado/a chileno/a debe confirmar:

- [ ] Redacción y edad de comprensión.
- [ ] Base de licitud y tratamiento de datos de menores.
- [ ] Validez del consentimiento electrónico y del asentimiento.
- [ ] Deberes de información y plazos de actualización.
- [ ] Interacción con la patria potestad, tutela y cuidado personal.
- [ ] Vía de reclamo ante la autoridad competente.

## 7. Texto público

El texto completo está en [`backend/app/web/legal/consentimiento.html`](../../backend/app/web/legal/consentimiento.html). Cualquier cambio material requiere nueva versión, aviso y nuevo registro.
