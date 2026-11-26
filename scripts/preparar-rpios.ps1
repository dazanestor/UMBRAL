# preparar-rpios.ps1 - Envoltorio para WINDOWS de scripts/preparar-rpios.sh (genera el paquete de liberar.sh).
# preparar-rpios.sh necesita Linux (loop mounts dentro de un contenedor privilegiado): se ejecuta dentro de WSL2.
# Requisitos: WSL2 con una distro (p. ej. Ubuntu), Docker (Docker Desktop con integracion WSL, o Docker en la
# distro), y dentro de la distro: sfdisk (util-linux), xz y sha256sum.
# Uso:  .\scripts\preparar-rpios.ps1 C:\ruta\raspios.img.xz [C:\ruta\salida.tar]
# NO PROBADO en Windows; si falla el montaje de la imagen, ejecuta el .sh en un Linux (o una VM).
param([Parameter(Mandatory=$true)][string]$Imagen, [string]$Salida = "rpios-bundle.tar")
$ErrorActionPreference = "Stop"
if (-not (Get-Command wsl -ErrorAction SilentlyContinue)) { throw "Falta WSL2 (wsl --install)" }
$script = (Resolve-Path (Join-Path $PSScriptRoot "preparar-rpios.sh")).Path
$img = (Resolve-Path $Imagen).Path
$out = [System.IO.Path]::GetFullPath($Salida)
$wslScript = (wsl wslpath -a "$script").Trim()
$wslImg = (wsl wslpath -a "$img").Trim()
$wslOut = (wsl wslpath -a "$out").Trim()
wsl bash "$wslScript" "$wslImg" "$wslOut"
if ($LASTEXITCODE -ne 0) { throw "preparar-rpios.sh fallo (codigo $LASTEXITCODE)" }
Write-Host "Paquete generado: $out"
