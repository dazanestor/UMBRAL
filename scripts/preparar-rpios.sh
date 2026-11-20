#!/bin/bash
# preparar-rpios.sh - SE EJECUTA EN TU PC (no en la pantalla).
# Convierte la imagen oficial de Raspberry Pi OS Lite (armhf, 32-bit) en el PAQUETE que consume
# liberar.sh: rootfs.tar.xz (con propietarios/permisos reales) + los ficheros de arranque MINIMOS
# para el CM3+ (el boot de fabrica solo tiene 64 MB compartidos).
#
# Por que existe: el sistema de fabrica no tiene rsync y no cabe la imagen descomprimida
# (~2,6 GB) en su disco. Con este paquete la pantalla solo descomprime en streaming.
#
# Necesita Docker (monta la imagen con loop dentro de un contenedor privilegiado; sin sudo),
# sfdisk (util-linux) y xz.
#
# Uso:   scripts/preparar-rpios.sh RASPIOS.img[.xz] [SALIDA.tar]
# Vars:  DOCKER_IMAGE (defecto debian:stable-slim) | WORKDIR (temporal, defecto /tmp)
set -euo pipefail
say(){ printf '\033[1;36m[preparar]\033[0m %s\n' "$*" >&2; }
die(){ printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

SRC="${1:-}"; OUT="${2:-rpios-bundle.tar}"
[ -n "$SRC" ] && [ -f "$SRC" ] || die "uso: $0 RASPIOS.img[.xz] [SALIDA.tar]"
for c in docker sfdisk xz tar sha256sum awk; do command -v "$c" >/dev/null || die "falta $c"; done
docker info >/dev/null 2>&1 || die "docker no accesible para este usuario"
DOCKER_IMAGE="${DOCKER_IMAGE:-debian:stable-slim}"

WORK="$(mktemp -d "${WORKDIR:-/tmp}/qv-rpios.XXXXXX")"; trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/stage"

IMG="$SRC"
case "$SRC" in
  *.xz) say "descomprimiendo $SRC (necesita ~3 GB en ${WORKDIR:-/tmp})..."; IMG="$WORK/os.img"; xz -dc "$SRC" > "$IMG";;
esac

# Particiones de la imagen: p1 = boot (FAT), p2 = rootfs (ext4)
read -r S1 N1 < <(sfdisk -d "$IMG" | awk -F'[=, ]+' '/start=/ && NR>0 {n++; if(n==1) print $4, $6}')
read -r S2 N2 < <(sfdisk -d "$IMG" | awk -F'[=, ]+' '/start=/ {n++; if(n==2) print $4, $6}')
[ -n "${S1:-}" ] && [ -n "${S2:-}" ] || die "no encuentro p1/p2 en la imagen (sfdisk -d)"
say "p1: inicio=$S1 tam=$N1 | p2: inicio=$S2 tam=$N2 (sectores de 512 B)"

IMGDIR="$(cd "$(dirname "$IMG")" && pwd)"; IMGBASE="$(basename "$IMG")"
say "montando la imagen en un contenedor ($DOCKER_IMAGE) y generando el paquete..."
docker run --rm --privileged -v /dev:/dev \
  -v "$IMGDIR:/work:ro" -v "$WORK/stage:/out" \
  -e HOSTUID="$(id -u)" -e HOSTGID="$(id -g)" \
  -e IMGBASE="$IMGBASE" -e O1=$((S1*512)) -e L1=$((N1*512)) -e O2=$((S2*512)) -e L2=$((N2*512)) \
  "$DOCKER_IMAGE" bash -c '
set -euo pipefail
mkdir -p /mnt/b /mnt/r /out/boot/overlays
mount -o loop,ro,offset=$O1,sizelimit=$L1 /work/$IMGBASE /mnt/b
# Solo lo necesario para CM3+ (BCM2837, 32-bit): firmware, kernel7, dtb del CM3, overlays e initramfs7
for f in bootcode.bin start.elf fixup.dat kernel7.img bcm2710-rpi-cm3.dtb initramfs7 cmdline.txt; do
  if [ -f /mnt/b/$f ]; then cp -f /mnt/b/$f /out/boot/$f; else echo "AVISO: falta $f en la imagen" >&2; fi
done
cp -f /mnt/b/overlays/*.dtbo /out/boot/overlays/ 2>/dev/null || true
for f in kernel7.img start.elf fixup.dat bootcode.bin bcm2710-rpi-cm3.dtb; do
  [ -f /out/boot/$f ] || { echo "FALTA obligatorio: $f" >&2; exit 3; }
done
chown -R "$HOSTUID:$HOSTGID" /out   # que el usuario del host pueda limpiarlo
umount /mnt/b
mount -o loop,ro,offset=$O2,sizelimit=$L2 /work/$IMGBASE /mnt/r
# propietarios/permisos numericos reales; symlinks (merged-usr: /lib -> usr/lib) intactos
tar --numeric-owner --xattrs --acls -cf - -C /mnt/r .
umount /mnt/r
' | xz -T0 -6 > "$WORK/stage/rootfs.tar.xz"
[ -s "$WORK/stage/rootfs.tar.xz" ] || die "rootfs.tar.xz vacio: el contenedor fallo"

xz -t "$WORK/stage/rootfs.tar.xz" || die "rootfs.tar.xz corrupto"
{
  echo "UMBRAL rpios-bundle 1"
  echo "origen=$(basename "$SRC")"
  echo "fecha=$(date -u +%FT%TZ)"
  ( cd "$WORK/stage" && sha256sum rootfs.tar.xz && find boot -type f | sort | xargs sha256sum )
} > "$WORK/stage/MANIFEST"

say "empaquetando $OUT..."
tar -cf "$OUT" -C "$WORK/stage" boot rootfs.tar.xz MANIFEST
say "LISTO: $OUT ($(du -h "$OUT" | cut -f1)). Copialo a la pantalla (scp) y ejecuta liberar.sh con BUNDLE=..."
