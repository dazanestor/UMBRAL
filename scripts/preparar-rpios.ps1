# preparar-rpios.ps1 - Envoltorio para WINDOWS de scripts/preparar-rpios.sh (genera el paquete de liberar.sh).
# preparar-rpios.sh necesita Linux (loop mounts dentro de un contenedor privilegiado): se ejecuta dentro de WSL2.
# Requisitos: WSL2 con una distro (p. ej. Ubuntu), Docker (Docker Desktop con integracion WSL, o Docker en la
# distro), y dentro de la distro: sfdisk (util-linux), xz y sha256sum.
# Uso:  .\scripts\preparar-rpios.ps1 C:\ruta\raspios.img.xz [C:\ruta\salida.tar]
# Variables de entorno (opcionales, igual que en preparar-rpios.sh): DOCKER_IMAGE, WORKDIR.
#   $env:DOCKER_IMAGE = 'debian:stable-slim'; $env:WORKDIR = 'C:\tmp'; .\scripts\preparar-rpios.ps1 ...
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
# `wsl` arranca un shell NUEVO que no hereda las variables de entorno de PowerShell: antes
# DOCKER_IMAGE/WORKDIR, que preparar-rpios.sh documenta como configurables, se perdian sin avisar
# y el .sh siempre usaba sus valores por defecto. Se reenvian explicitamente si estan puestas
# (WORKDIR, si parece una ruta de Windows "C:\...", se traduce igual que la imagen/salida).
$envArgs = @()
if ($env:DOCKER_IMAGE) { $envArgs += "DOCKER_IMAGE=$($env:DOCKER_IMAGE)" }
if ($env:WORKDIR) {
  $wd = $env:WORKDIR
  if ($wd -match '^[A-Za-z]:\\') { $wd = (wsl wslpath -a "$wd").Trim() }
  $envArgs += "WORKDIR=$wd"
}
wsl env @envArgs bash "$wslScript" "$wslImg" "$wslOut"
if ($LASTEXITCODE -ne 0) { throw "preparar-rpios.sh fallo (codigo $LASTEXITCODE)" }
Write-Host "Paquete generado: $out"
