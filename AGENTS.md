# Reglas de trabajo — IA Guardian

## Atlas de arquitectura (obligatorio)

Todo cambio en IA Guardian debe dejar actualizado el atlas publicado en
<https://roninwuajkja.github.io/guardiania-atlas/> y versionado en
`roninwuajkja/guardiania-atlas`.

Al cerrar cualquier cambio:

1. Actualizar `CHANGELOG.md`.
2. Identificar los módulos del atlas afectados (01–07).
3. Regenerar o ajustar los diagramas de esos módulos en
   `C:\Users\Usuario\Desktop\Diagramas IA Guardian Archify` con Archify.
4. Actualizar la versión y la revisión en el `README.md` y el `index.html` del
   atlas (el script lo hace).
5. Pasar las cuatro compuertas de Archify de cada diagrama tocado:
   validación, entrega, chequeo estático y chequeo de navegador.
6. Publicar con:

   ```powershell
   .\scripts\publish-atlas.ps1
   ```

   Si regeneraste solo algunos diagramas, limita la publicación:

   ```powershell
   .\scripts\publish-atlas.ps1 -Files 03-captura-privacidad/01-architecture.html,05-riesgo-ia/01-architecture.html
   ```

   Para revisar sin commitear:

   ```powershell
   .\scripts\publish-atlas.ps1 -DryRun
   ```

7. Verificar que GitHub Pages responda y muestre la versión nueva.

## Qué se versiona en el atlas

- Los 70 HTML canónicos (7 módulos × 10 lecturas).
- `index.html`, `README.md`, `favicon.svg`.
- Los archivos propios del sitio: `404.html`, `robots.txt`, `.nojekyll` y
  `.github/workflows/pages.yml`.

No se versionan los JSON de evidencia de Archify, los `visual-check` ni los
recibos de `finalize`/`browser-check`. La carpeta de trabajo puede contenerlos,
pero el repositorio publica solo el sitio.

## Regla de privacidad

El atlas describe arquitectura y flujos. No debe incluir conversaciones, datos
reales de menores, credenciales, tokens ni capturas con contenido personal.
