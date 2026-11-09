# IA Guardian — guía de marca

## 1. La idea

**Vínculo** son dos órbitas que se cuidan y dejan un espacio libre. No es un escudo ni un ojo: es una guardia que acompaña, no que encierra. El corte pequeño reemplaza la órbita interior por dos puntos —el tutor y el menor— para sobrevivir a 16 px.

## 2. Versiones

| Versión | Cuándo | Archivo |
|---|---|---|
| Símbolo completo | Banner, lockups, app icon, piezas grandes | `source/b-vinculo-symbol.svg` |
| Corte pequeño | Favicon, marca de UI, 16–32 px | `source/b-vinculo-symbol-small.svg` |
| Horizontal | Cabeceras y README | `source/b-vinculo-horizontal.svg` |
| Stacked | Formatos cuadrados y sociales | `source/b-vinculo-stacked.svg` |
| Wordmark | Cuando el símbolo ya se reconoce | `source/b-vinculo-wordmark.svg` |

Usa el **corte pequeño** por debajo de 32 px. Usa el **símbolo completo** desde 32 px. No uses el símbolo completo como favicon: se embota.

## 3. Espacio libre y tamaño mínimo

- **Espacio libre:** el diámetro del punto central del corte pequeño (20 unidades sobre 256) en todos los lados.
- **Símbolo completo:** mínimo 32 px en pantalla, 10 mm en impresión.
- **Corte pequeño:** mínimo 16 px en pantalla, 5 mm en impresión.
- **Horizontal:** mínimo 140 px de ancho.
- **Stacked:** mínimo 100 px de ancho.

## 4. Color

| Nombre | HEX | RGB | CMYK aprox. | Uso |
|---|---|---|---|---|
| Pine 950 | `#081B17` | 8 27 23 | 70 0 15 89 | Fondo oscuro, app icon |
| Pine 700 | `#1B4A3E` | 27 74 62 | 64 0 16 71 | Órbita exterior en color |
| Pine 600 | `#236052` | 35 96 82 | 64 0 15 62 | Acentos de UI |
| Sage 500 | `#5E8F7B` | 94 143 123 | 34 0 14 44 | Órbita interior en color |
| Sage 300 | `#A9C6B8` | 169 198 184 | 15 0 7 22 | Reverso, texto secundario |
| Paper | `#EEF2ED` | 238 242 237 | 2 0 2 5 | Fondo claro, marca reversa |
| Ink | `#10201B` | 16 32 27 | 50 0 16 87 | Texto y marca de una tinta |
| Oxblood | `#A33128` | 163 49 40 | 0 70 75 36 | Solo alertas críticas |
| Amber | `#8D620F` | 141 98 15 | 0 30 89 45 | Solo atención |

Los Pantone deben verificarse contra una guía física antes de imprimir. Para web y pantalla usa HEX/RGB; para impresión, CMYK con prueba de color.

**Parejas aprobadas:** marca Pine 900 sobre Paper o Card · marca reversa Paper/Sage 300 sobre Pine 950 · una tinta Ink sobre Paper · una tinta Paper sobre Pine 950. No pongas la marca Pine sobre Pine 950.

## 5. Tipografía

- **Wordmark:** Archivo 600, convertido a curvas en `source/b-vinculo-wordmark.svg`. No lo reescribas con otra fuente ni lo escribas a mano.
- **UI y titulares:** Archivo 400–700, servida localmente desde `backend/app/web/fonts/`.
- **Eyebrows, códigos y líneas de apoyo:** Spline Sans Mono 400/500.
- **Licencias:** Archivo y Spline Sans Mono se usan bajo SIL Open Font License 1.1. El wordmark outline no depende de la fuente instalada; la UI sí.

## 6. Lockups

- **Horizontal:** símbolo a la izquierda, wordmark a la derecha; el símbolo mide ~2,5 veces la altura de mayúscula del wordmark.
- **Stacked:** símbolo arriba, wordmark centrado abajo; usa esta versión en avatares y formatos cuadrados.
- **Símbolo solo:** app icon, favicon, avatar.
- No reorganices las partes, no cambies la distancia símbolo–wordmark y no agregues tagline dentro del lockup: la tagline va aparte.

## 7. Usos incorrectos

No rotar, estirar, comprimir, recolorear fuera de la paleta, agregar sombras, biseles, gradientes, contornos o efectos. No cambiar la abertura de las órbitas ni la posición de los puntos. No usar el símbolo completo a 16 px. No recrear el wordmark escribiéndolo. No colocar la marca sobre fotos sin una versión reversa o un contenedor.

## 8. Archivos

- `source/`: maestros SVG (símbolo, corte pequeño, lockups, wordmark).
- `web-icons/`: favicon.ico, favicon-16/32/48, apple-touch-icon, icon-192/512, maskable-512, `site.webmanifest`.
- `banner/github-banner.png`: banner de README, 1600 × 500.
- `banner/github-social-preview.png`: vista previa social, 1200 × 630.
- `docs/screenshots/banner.png`: copia publicada del banner.
- `docs/screenshots/logo.png`: símbolo en color para documentación.

## 9. GitHub

El README usa `docs/screenshots/banner.png`. La vista previa social se sube en **Settings → Social preview** del repositorio (1200 × 630). Mantén el símbolo y el wordmark dentro del área central segura; no recortes los puntos.

## 10. Registro

Este trabajo no incluye una búsqueda de marcas. Antes de lanzar, haz una búsqueda profesional en registros de marcas y una búsqueda inversa de imágenes. La marca es un identificador, no una garantía legal.
