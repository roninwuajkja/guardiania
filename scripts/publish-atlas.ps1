[CmdletBinding()]
param(
    [string]$Source = 'C:\Users\Usuario\Desktop\Diagramas IA Guardian Archify',
    [string]$Repo = 'C:\Users\Usuario\Desktop\guardiania-atlas',
    [string]$ProjectRoot = '',
    [string]$Version = '',
    [string]$Revision = '',
    [string[]]$Files = @(),
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)

if (-not $ProjectRoot) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$Files = @(
    $Files |
        ForEach-Object { $_ -split ',' } |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ }
)
if (-not (Test-Path $Source)) { throw "No existe la carpeta del atlas: $Source" }
if (-not (Test-Path (Join-Path $Repo '.git'))) { throw "No existe el clon del atlas: $Repo" }

if (-not $Version) {
    $gradle = Join-Path $ProjectRoot 'android\app\build.gradle.kts'
    $match = [regex]::Match((Get-Content $gradle -Raw), 'versionName\s*=\s*"([^"]+)"')
    if (-not $match.Success) { throw "No pude leer versionName de $gradle" }
    $Version = $match.Groups[1].Value
}
if (-not $Revision) { $Revision = (git -C $ProjectRoot rev-parse HEAD).Trim() }
if ($Revision -notmatch '^[0-9a-f]{40}$') { throw "Revision invalida: $Revision" }
$short = $Revision.Substring(0, 7)

$dirty = git -C $Repo status --porcelain
if ($dirty) { throw "El clon del atlas tiene cambios sin commitear. Limpialo antes de publicar." }

Write-Output "Atlas: $Version ($short)"
Write-Output "Origen: $Source"
Write-Output "Destino: $Repo"
git -C $Repo pull --ff-only | Out-Host

function Write-IfChanged {
    param([string]$Path, [string]$Content)
    $existing = ''
    if (Test-Path $Path) { $existing = [IO.File]::ReadAllText($Path, $utf8) }
    $newNorm = $Content.Replace("`r`n", "`n")
    $oldNorm = $existing.Replace("`r`n", "`n")
    if ($newNorm -eq $oldNorm) { return $false }
    $eol = if ($existing -match "`r`n") { "`r`n" } else { "`n" }
    [IO.File]::WriteAllText($Path, ($newNorm -replace "`n", $eol), $utf8)
    return $true
}

# La carpeta de trabajo es la fuente del sitio: actualiza version y revision.
$readme = Join-Path $Source 'README.md'
$text = [IO.File]::ReadAllText($readme, $utf8)
$text = [regex]::Replace($text, 'IA Guardian \d+\.\d+\.\d+', "IA Guardian $Version")
$text = [regex]::Replace($text, '\b[0-9a-f]{40}\b', $Revision)
[IO.File]::WriteAllText($readme, $text, $utf8)

$index = Join-Path $Source 'index.html'
$html = [IO.File]::ReadAllText($index, $utf8)
$html = [regex]::Replace($html, '(?<=versi.n )\d+\.\d+\.\d+', $Version)
$html = [regex]::Replace($html, '(?<=base <b>)[0-9a-f]{7}(?=</b>)', $short)
$html = [regex]::Replace($html, '(?<=Fuente: repositorio en )[0-9a-f]{7}', $short)
[IO.File]::WriteAllText($index, $html, $utf8)

# Root site files.
foreach ($name in @('index.html', 'README.md', 'favicon.svg')) {
    $sourcePath = Join-Path $Source $name
    $targetPath = Join-Path $Repo $name
    Write-IfChanged $targetPath ([IO.File]::ReadAllText($sourcePath, $utf8)) | Out-Null
}

# Canonical diagrams: all by default, or only the regenerated ones passed with -Files.
$canonical = @()
if ($Files.Count -gt 0) {
    $canonical = $Files
} else {
    foreach ($module in Get-ChildItem $Source -Directory -Filter '0*') {
        Get-ChildItem $module.FullName -File -Filter '*.html' |
            Where-Object { $_.Name -match '^\d{2}-[a-z]+\.html$' } |
            ForEach-Object { $canonical += ($module.Name + '/' + $_.Name) }
    }
}

foreach ($rel in $canonical) {
    if ($rel -notmatch '^0[1-7]-[^/]+/\d{2}-[a-z]+\.html$') {
        throw "Archivo de atlas invalido: $rel"
    }
    $sourcePath = Join-Path $Source $rel
    $targetPath = Join-Path $Repo $rel
    if (-not (Test-Path $sourcePath)) { throw "No existe el diagrama: $sourcePath" }
    $body = [IO.File]::ReadAllText($sourcePath, $utf8)
    if ($body -notmatch 'rel="icon"') {
        $head = $body.IndexOf('<head>')
        if ($head -ge 0) {
            $insert = "<head>`r`n  <link rel=""icon"" href=""../favicon.svg"" type=""image/svg+xml"">"
            $body = $body.Substring(0, $head) + $insert + $body.Substring($head + 6)
        }
    }
    Write-IfChanged $targetPath $body | Out-Null
}

$indexTarget = Join-Path $Repo 'index.html'
$indexBody = [IO.File]::ReadAllText($indexTarget, $utf8)
if ($indexBody -notmatch 'name="robots"') {
    $needle = '<meta name="viewport" content="width=device-width, initial-scale=1">'
    $indexBody = $indexBody.Replace($needle, $needle + "`r`n<meta name=""robots"" content=""noindex, nofollow"">")
    Write-IfChanged $indexTarget $indexBody | Out-Null
}

git -C $Repo diff --quiet
$changed = $LASTEXITCODE -ne 0
if (-not $changed) {
    Write-Output 'Sin cambios que publicar.'
    exit 0
}
git -C $Repo diff --stat | Out-Host

if ($DryRun) {
    Write-Output 'DryRun: no se creo commit ni se hizo push. Los cambios quedan en el working tree para revisarlos.'
    exit 0
}

git -C $Repo add -A
git -C $Repo commit -m "docs(atlas): actualizar a IA Guardian $Version ($short)"
git -C $Repo push origin main
Write-Output 'Atlas publicado. Verifica https://roninwuajkja.github.io/guardiania-atlas/'
