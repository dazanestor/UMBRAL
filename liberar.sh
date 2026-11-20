#!/bin/bash
# liberar.sh - Libera una pantalla Qvadis "ladrillo" (stock Yocto) por SSH, SIN hardware
# adicional: instala Raspberry Pi OS Lite en la particion libre y conmuta el arranque.
# Despues, install.sh provisiona la pila Qvadis + A/B.
#
#  ####################  AVISO IMPORTANTE  ####################
#  - OPERACION DE IDA: el boot (p1) es compartido y pequeno (64 MB). Al poner el firmware
#    de RPi OS, el arranque del stock (p2) DEJA DE FUNCIONAR. No hay rollback al stock sin
#    re-flashear con una IO board. EL UNICO SEGURO ES EL BACKUP COMPLETO de la eMMC.
#  - NO PROBADO en banco (la unidad de desarrollo ya estaba liberada). Revisa antes de usar.
#  - Ejecutar como root en el stock, por SSH (clave service.qvadis).
#
# Uso (en el equipo stock):
#   sudo IMG=/ruta/raspios-lite.img.xz ./liberar.sh
#   (IMG puede ser .img o .img.xz; si no se da y hay red+espacio, se intenta descargar)
# Variables:
#   IMG=...         imagen de Raspberry Pi OS Lite (armhf/32-bit) .img o .img.xz
#   SPARE=/dev/...  particion destino (por defecto, la "otra" de 3,5G)
#   SKIP_BACKUP=1   omite el recordatorio de backup (NO recomendado)
set -euo pipefail
say(){ printf '\033[1;36m[liberar]\033[0m %s\n' "$*"; }
err(){ printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; }
die(){ err "$*"; exit 1; }
confirm(){ local a; read -r -p "$1 [escribe SI]: " a </dev/tty || true; [ "$a" = SI ]; }

[ "$(id -u)" = 0 ] || die "ejecuta como root"
command -v losetup >/dev/null || die "falta losetup (util-linux)"
command -v rsync   >/dev/null || die "falta rsync"
command -v blkid   >/dev/null || die "falta blkid"

DISK=/dev/mmcblk0
RUNPART="$(findmnt -no SOURCE / 2>/dev/null || true)"
say "arranque actual desde: ${RUNPART:-?}"
# Particion destino = la otra de ~3,5G que NO es la de arranque
if [ -z "${SPARE:-}" ]; then
  for p in ${DISK}p3 ${DISK}p2; do
    [ "$p" = "$RUNPART" ] && continue
    sz=$(blockdev --getsize64 "$p" 2>/dev/null || echo 0)
    [ "$sz" -gt 1000000000 ] && { SPARE="$p"; break; }
  done
fi
[ -n "${SPARE:-}" ] && [ -b "$SPARE" ] || die "no encuentro particion destino libre (define SPARE=/dev/mmcblk0pN)"
[ "$SPARE" = "$RUNPART" ] && die "la particion destino es la de arranque; aborto"
BOOT=/boot/firmware; [ -d "$BOOT" ] || BOOT=/boot
say "destino (slot nuevo): $SPARE | boot: $BOOT"

cat <<EOF

  Se va a INSTALAR Raspberry Pi OS en $SPARE y CAMBIAR el arranque.
  El sistema stock (p2) quedara inarrancable (boot compartido). Operacion de IDA.
  Recuperacion ante fallo: SOLO re-flasheando con IO board desde un backup.
EOF
if [ "${SKIP_BACKUP:-0}" != 1 ]; then
  echo
  say "HAZ UN BACKUP COMPLETO AHORA, desde TU PC (no desde aqui):"
  echo "    ssh root@<ip-de-la-pantalla> 'dd if=$DISK bs=4M | gzip -1' > qvadis-backup.img.gz"
  echo
  confirm "¿Tienes ya el backup completo y quieres continuar?" || die "cancelado (haz el backup)"
fi
confirm "Ultima confirmacion: liberar e instalar RPi OS en $SPARE" || die "cancelado"

# --------- obtener imagen ---------
WORK="$(mktemp -d)"; trap 'umount "$WORK"/img-* 2>/dev/null || true; losetup -D 2>/dev/null || true; rm -rf "$WORK"' EXIT
if [ -z "${IMG:-}" ]; then
  die "define IMG=/ruta/raspios-lite.img(.xz). Descargala en tu PC y copiala con scp, o pasa la ruta."
fi
[ -f "$IMG" ] || die "no existe la imagen: $IMG"
RAW="$IMG"
case "$IMG" in
  *.xz) say "descomprimiendo imagen..."; RAW="$WORK/os.img"; xz -dc "$IMG" > "$RAW";;
esac

# --------- mapear particiones de la imagen ---------
say "mapeando particiones de la imagen..."
LOOP="$(losetup -Pf --show "$RAW")" || die "losetup fallo"
IMGBOOT="${LOOP}p1"; IMGROOT="${LOOP}p2"
[ -b "$IMGBOOT" ] && [ -b "$IMGROOT" ] || die "la imagen no tiene p1(boot)+p2(root) esperadas"
mkdir -p "$WORK/img-boot" "$WORK/img-root"
mount -o ro "$IMGBOOT" "$WORK/img-boot"
mount -o ro "$IMGROOT" "$WORK/img-root"

# --------- rootfs -> particion destino (rsync: merged-usr SEGURO) ---------
say "formateando $SPARE e instalando rootfs (rsync, preserva symlinks)..."
umount "$SPARE" 2>/dev/null || true
mkfs.ext4 -F -L rootfs "$SPARE" >/dev/null
mkdir -p "$WORK/dst"; mount "$SPARE" "$WORK/dst"
rsync -aHAX "$WORK/img-root/." "$WORK/dst/"
SPUUID="$(blkid -s PARTUUID -o value "$SPARE")"
# fstab del nuevo sistema: root = destino, boot = p1 real
cat > "$WORK/dst/etc/fstab" <<EOF
proc            /proc           proc    defaults          0 0
PARTUUID=$(blkid -s PARTUUID -o value ${DISK}p1)  /boot/firmware  vfat  defaults          0 2
PARTUUID=$SPUUID  /               ext4  defaults,noatime  0 1
EOF
sync; umount "$WORK/dst"
say "rootfs instalada en $SPARE (PARTUUID=$SPUUID)"

# --------- boot (p1, 64 MB) : firmware/kernel de RPi OS + overlays + config del panel ---------
say "poblando $BOOT con el boot de RPi OS (seleccion que cabe en 64 MB)..."
cp -a "$BOOT" "${BOOT}.prelib.$(date +%s)" 2>/dev/null || true
# borra kernels/elfs viejos del stock para hacer sitio, conserva nada critico del stock
# (el stock ya no arrancara; es intencionado)
HERE="$(cd "$(dirname "$0")" && pwd)"
# copia selectiva: firmware GPU, kernel ARMv7 (kernel7l/kernel7), dtbs, overlays
for f in bootcode.bin start.elf start_cd.elf start_x.elf fixup.dat fixup_cd.dat fixup_x.dat \
         kernel7l.img kernel7.img kernel.img; do
  [ -f "$WORK/img-boot/$f" ] && cp -f "$WORK/img-boot/$f" "$BOOT/" || true
done
# DTBs (incluye el del CM3) y overlays
cp -f "$WORK"/img-boot/*.dtb "$BOOT/" 2>/dev/null || true
mkdir -p "$BOOT/overlays"; cp -f "$WORK"/img-boot/overlays/*.dtbo "$BOOT/overlays/" 2>/dev/null || true
# overlay(s) personalizados del repo (p.ej. goodix-qv.dtbo) si estan presentes
cp -f "$HERE"/deploy/boot/firmware/overlays/*.dtbo "$BOOT/overlays/" 2>/dev/null || true
# initramfs si la imagen lo trae
cp -f "$WORK"/img-boot/initramfs* "$BOOT/" 2>/dev/null || true
# config.txt del panel (del repo) + cmdline con root = destino
if [ -f "$HERE/deploy/boot/firmware/config.txt" ]; then
  cp -f "$HERE/deploy/boot/firmware/config.txt" "$BOOT/config.txt"
fi
# cmdline: base de RPi OS + nuestros parametros + root al destino
BASECMD="$(cat "$WORK/img-boot/cmdline.txt" 2>/dev/null || echo 'console=serial0,115200 rootfstype=ext4 fsck.repair=yes rootwait')"
CMD="$(sed -E 's#root=[^ ]+##; s/console=tty1/console=tty3/' <<<"$BASECMD")"
for p in dwc_otg.lpm_enable=0 dwc_otg.speed=1 console=tty3 root=PARTUUID=$SPUUID quiet loglevel=3 \
         logo.nologo vt.global_cursor_default=0 plymouth.ignore-serial-consoles splash; do
  grep -qw -- "$p" <<<"$CMD" || CMD="$CMD $p"
done
echo "$CMD" | tr -s ' ' > "$BOOT/cmdline.txt"
# tamano p1
USED=$(du -sm "$BOOT" | cut -f1); say "uso de $BOOT: ${USED} MB (limite ~64)"
[ "$USED" -gt 62 ] && err "AVISO: $BOOT casi lleno; revisa que no falten ficheros de arranque"

sync
say "LISTO. cmdline -> root=PARTUUID=$SPUUID"
say "Tras reiniciar arrancara RPi OS Lite. Luego ejecuta install.sh (provision + A/B)."
say "AVISO overlay: si el panel no da imagen/tactil, falta el overlay personalizado goodix-qv.dtbo en $BOOT/overlays (anadelo al repo en deploy/boot/firmware/overlays)."
if confirm "Reiniciar ahora"; then sync; reboot; else say "Reinicia con: reboot -f (el inittab del stock puede colgar en reboot limpio)"; fi
