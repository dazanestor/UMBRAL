#!/bin/bash
# liberar.sh - Libera una pantalla Qvadis "ladrillo" (stock Yocto) por SSH, SIN hardware
# adicional: instala Raspberry Pi OS Lite en la particion libre y conmuta el arranque.
# Despues, install.sh provisiona la pila Qvadis + A/B.
#
#  ####################  AVISO IMPORTANTE  ####################
#  - OPERACION DE IDA: el boot (p1) es compartido y pequeno (64 MB). Al poner el firmware
#    de RPi OS, el arranque del stock (p2) DEJA DE FUNCIONAR. No hay rollback al stock sin
#    re-flashear con una IO board. EL UNICO SEGURO ES EL BACKUP COMPLETO de la eMMC.
#  - NO PROBADO en una pantalla real. Se ha probado la logica en un disco simulado (ver README).
#  - Ejecutar como root en el stock, por SSH. Las credenciales/clave de acceso al stock
#    NO se incluyen aqui: consiguelas por tus medios (foros/comunidad) y usalas solo en tu equipo.
#
# El stock NO tiene rsync ni espacio para la imagen descomprimida (~2,6 GB), por eso NO se usa la
# imagen directamente: en tu PC genera el PAQUETE con scripts/preparar-rpios.sh y copialo aqui.
#
# Uso (en el equipo stock; el repo copiado junto a este script, con deploy/):
#   sudo BUNDLE=/ruta/rpios-bundle.tar ./liberar.sh
# Variables:
#   BUNDLE=...      paquete generado por scripts/preparar-rpios.sh   (obligatorio)
#   PI_PASS=...     contrasena del usuario 'pi' (si no, la pregunta)
#   WIFI_SSID= WIFI_PASS=   WiFi para el primer arranque (si no, las pregunta). SIN WiFi la placa
#                   queda sin red (no tiene Ethernet) y no podras entrar por SSH.
#   SSH_PUBKEY=...  clave publica (texto o ruta a .pub) para entrar por SSH sin contrasena (opcional)
#   SPARE=/dev/...  particion destino (por defecto, la "otra" de ~3,5G)
#   SKIP_BACKUP=1   omite el recordatorio de backup (NO recomendado)
#   ASSUME_YES=1    no pide confirmaciones (pruebas)      NO_REBOOT=1  no reinicia
#   DISK= BOOT= RUNPART=   solo para pruebas en discos simulados
set -euo pipefail
say(){ printf '\033[1;36m[liberar]\033[0m %s\n' "$*"; }
err(){ printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; }
die(){ err "$*"; exit 1; }
confirm(){ [ "${ASSUME_YES:-0}" = 1 ] && return 0; local a=""; read -r -p "$1 [escribe SI]: " a 2>/dev/null </dev/tty || true; [ "$a" = SI ]; }
ask(){ local v=""; read -r -p "$1: " v 2>/dev/null </dev/tty || true; printf '%s' "$v"; }
askhidden(){ local v=""; read -r -s -p "$1: " v 2>/dev/null </dev/tty || true; echo >&2; printf '%s' "$v"; }

[ "$(id -u)" = 0 ] || die "ejecuta como root"
for c in tar xz mkfs.ext4 blkid blockdev findmnt mount umount cp mktemp sed awk df du cmp od; do
  command -v "$c" >/dev/null || die "falta $c"; done
# Solo para el sistema de FABRICA (Yocto). En una pantalla ya liberada machacaria el slot inactivo y el kernel en uso.
if grep -qiE '^(ID|ID_LIKE)=.*(debian|raspbian)' /etc/os-release 2>/dev/null && [ "${FORCE_NOT_STOCK:-0}" != 1 ]; then
  die "este equipo ya corre Debian/Raspberry Pi OS (no es el sistema de fábrica): liberar.sh no se ejecuta aqui. Para actualizar usa install.sh o 'qvadis-ota'."
fi
HERE="$(cd "$(dirname "$0")" && pwd)"
for f in deploy/boot/firmware/config.txt deploy/boot/firmware/overlays/goodix-qv.dtbo \
         deploy/etc/NetworkManager/conf.d/99-unmanaged-wlan0.conf deploy/etc/modprobe.d/qvadis-no-bt.conf \
         deploy/usr/share/qvadis/10-wlan0.network.default; do
  [ -f "$HERE/$f" ] || die "falta $f (copia el repo COMPLETO junto a liberar.sh)"; done

BUNDLE="${BUNDLE:-${IMG:-}}"
[ -n "$BUNDLE" ] && [ -f "$BUNDLE" ] || die "define BUNDLE=/ruta/rpios-bundle.tar (genéralo en tu PC con scripts/preparar-rpios.sh)"

DISK="${DISK:-/dev/mmcblk0}"
RUNPART="${RUNPART:-$(findmnt -no SOURCE / 2>/dev/null || true)}"
case "$RUNPART" in ""|/dev/root|rootfs|overlay)   # el stock puede mostrar /dev/root
  RUNPART="$(sed -n 's/.*root=\([^ ]*\).*/\1/p' /proc/cmdline)"
  case "$RUNPART" in PARTUUID=*) RUNPART="$(blkid -t "$RUNPART" -o device 2>/dev/null | head -1)";; esac;; esac
say "arranque actual desde: ${RUNPART:-?}"
if [ -z "${BOOT:-}" ]; then BOOT=/boot/firmware; [ -d "$BOOT" ] || BOOT=/boot; fi
[ "$(findmnt -no SOURCE "$BOOT" 2>/dev/null)" = "${DISK}p1" ] || die "$BOOT no esta montado desde ${DISK}p1 (no toco nada)"
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
grep -q "^$SPARE " /proc/mounts && die "$SPARE esta montada; aborto"
say "destino (slot nuevo): $SPARE | boot: $BOOT"

WORK="$(mktemp -d)"
cleanup(){ umount "$WORK/dst" 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT

# --------- 1) validar el paquete y el espacio ANTES de tocar nada ---------
say "comprobando el paquete..."
tar -tf "$BUNDLE" > "$WORK/list" || die "el paquete no es un tar valido"
for m in rootfs.tar.xz boot/kernel7.img boot/start.elf boot/fixup.dat boot/bootcode.bin boot/bcm2710-rpi-cm3.dtb boot/cmdline.txt; do
  grep -qx "$m" "$WORK/list" || die "el paquete no contiene $m (¿lo generó scripts/preparar-rpios.sh?)"; done
tar -xf "$BUNDLE" -C "$WORK" boot
NEED_K=$(du -sk "$WORK/boot" | cut -f1); NEED_K=$((NEED_K + $(du -sk "$HERE/deploy/boot/firmware/overlays" | cut -f1)))
FREE_K=$(df -Pk "$BOOT" | awk 'NR==2{print $4}')
say "p1: necesito ~$((NEED_K/1024)) MB temporales, libres $((FREE_K/1024)) MB"
[ "$FREE_K" -gt $((NEED_K + 2048)) ] || die "p1 no tiene espacio suficiente; no toco nada"

# --------- 2) datos del primer arranque ---------
PI_PASS="${PI_PASS:-}"
if [ -z "$PI_PASS" ]; then
  PI_PASS="$(askhidden 'Contraseña para el usuario pi')"; P2="$(askhidden 'Repite la contraseña')"
  [ "$PI_PASS" = "$P2" ] || die "las contraseñas no coinciden"
fi
[ -n "$PI_PASS" ] || die "la contraseña de pi no puede estar vacia"
WIFI_SSID="${WIFI_SSID:-$(ask 'SSID de tu WiFi (vacio = sin WiFi)')}"
WIFI_PASS="${WIFI_PASS:-}"
if [ -n "$WIFI_SSID" ]; then
  [ -n "$WIFI_PASS" ] || WIFI_PASS="$(askhidden 'Contraseña WiFi (vacio = red abierta)')"
  [ -z "$WIFI_PASS" ] || { [ ${#WIFI_PASS} -ge 8 ] && [ ${#WIFI_PASS} -le 63 ] || die "la clave WiFi debe tener 8-63 caracteres"; }
  case "$WIFI_SSID$WIFI_PASS" in *[[:cntrl:]]*) die "SSID/clave con caracteres de control";; esac
else
  err "SIN WiFi: la placa no tiene Ethernet y quedara sin red (no podras entrar por SSH ni ejecutar install.sh)."
  confirm "¿Continuar sin WiFi?" || die "cancelado"
fi
hashpw(){ if command -v openssl >/dev/null 2>&1; then printf '%s' "$1" | openssl passwd -6 -stdin
          else python3 -c 'import crypt,sys;print(crypt.crypt(sys.stdin.read(),crypt.mksalt(crypt.METHOD_SHA512)))' <<<"$1"; fi; }
PI_HASH="$(hashpw "$PI_PASS")"; [ -n "$PI_HASH" ] || die "no pude generar el hash de la contraseña"
SSHKEY=""; if [ -n "${SSH_PUBKEY:-}" ]; then if [ -f "$SSH_PUBKEY" ]; then SSHKEY="$(cat "$SSH_PUBKEY")"; else SSHKEY="$SSH_PUBKEY"; fi; fi

cat <<EOF

  Se va a INSTALAR Raspberry Pi OS en $SPARE (se BORRA su contenido) y CAMBIAR el arranque.
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

# --------- 3) rootfs -> particion destino (streaming, sin imagen intermedia) ---------
say "formateando $SPARE e instalando la rootfs (tar en streaming, conserva symlinks y propietarios)..."
mkfs.ext4 -F -L rootfs "$SPARE" >/dev/null
mkdir -p "$WORK/dst"; mount "$SPARE" "$WORK/dst"
D="$WORK/dst"
TAROPTS=(-xpf - --numeric-owner -C "$D")
tar --help 2>&1 | grep -q -- '--xattrs' && TAROPTS+=(--xattrs)
tar --help 2>&1 | grep -q -- '--acls'   && TAROPTS+=(--acls)
tar -xOf "$BUNDLE" rootfs.tar.xz | xz -dc | tar "${TAROPTS[@]}"
# verificacion (leccion del merged-usr: /lib DEBE ser un symlink a usr/lib)
[ -L "$D/lib" ] && [ -x "$D/usr/bin/bash" ] && [ -f "$D/etc/passwd" ] && [ -d "$D/boot/firmware" ] \
  || die "la rootfs extraida no es valida (¿/lib symlink?). NO he tocado el arranque."
SPUUID="$(blkid -s PARTUUID -o value "$SPARE")"; P1UUID="$(blkid -s PARTUUID -o value "${DISK}p1")"
[ -n "$SPUUID" ] && [ -n "$P1UUID" ] || die "no obtengo los PARTUUID; no toco el arranque"
cat > "$D/etc/fstab" <<EOF
proc            /proc           proc    defaults          0 0
PARTUUID=$P1UUID  /boot/firmware  vfat  defaults          0 2
PARTUUID=$SPUUID  /               ext4  defaults,noatime  0 1
EOF

# --------- 4) primer arranque: usuario pi, SSH, WiFi, red (sin depender de cloud-init ni NM) ---------
say "configurando el primer arranque (usuario pi, SSH, WiFi)..."
addmember(){ awk -F: -v OFS=: -v g="$1" -v u=pi '$1==g{ if($4=="")$4=u; else if(index(","$4",",","u",")==0)$4=$4","u } {print}' \
  "$D/etc/group" > "$WORK/group.new" && cat "$WORK/group.new" > "$D/etc/group"; }
if grep -q '^pi:' "$D/etc/passwd"; then
  awk -F: -v OFS=: '$1=="pi"{$6="/home/pi";$7="/bin/bash"} {print}' "$D/etc/passwd" > "$WORK/p.new" && cat "$WORK/p.new" > "$D/etc/passwd"
else
  echo 'pi:x:1000:1000:,,,:/home/pi:/bin/bash' >> "$D/etc/passwd"
  grep -q '^pi:' "$D/etc/group" || echo 'pi:x:1000:' >> "$D/etc/group"
fi
if grep -q '^pi:' "$D/etc/shadow"; then
  awk -F: -v OFS=: -v h="$PI_HASH" '$1=="pi"{$2=h;$3=20000} {print}' "$D/etc/shadow" > "$WORK/s.new" && cat "$WORK/s.new" > "$D/etc/shadow"
else
  echo "pi:$PI_HASH:20000:0:99999:7:::" >> "$D/etc/shadow"
fi
[ -f "$D/etc/gshadow" ] && ! grep -q '^pi:' "$D/etc/gshadow" && echo 'pi:!::' >> "$D/etc/gshadow"
for g in adm dialout cdrom sudo audio video plugdev games users input render netdev spi i2c gpio; do addmember "$g"; done
PIUID="$(awk -F: '$1=="pi"{print $3}' "$D/etc/passwd")"; PIGID="$(awk -F: '$1=="pi"{print $4}' "$D/etc/passwd")"
mkdir -p "$D/home/pi"
[ -n "$(ls -A "$D/home/pi" 2>/dev/null)" ] || cp -a "$D/etc/skel/." "$D/home/pi/" 2>/dev/null || true
if [ -n "$SSHKEY" ]; then
  mkdir -p "$D/home/pi/.ssh"; printf '%s\n' "$SSHKEY" >> "$D/home/pi/.ssh/authorized_keys"
  chmod 700 "$D/home/pi/.ssh"; chmod 600 "$D/home/pi/.ssh/authorized_keys"
fi
chown -R "$PIUID:$PIGID" "$D/home/pi"
chmod 640 "$D/etc/shadow" 2>/dev/null || true

# cloud-init fuera (su red pasa por NetworkManager, que NO asocia con el chip WiFi rtl8723bu)
mkdir -p "$D/etc/cloud"; : > "$D/etc/cloud/cloud-init.disabled"
# habilitar servicios a mano (symlinks, como haria systemctl enable)
W="$D/etc/systemd/system"; U=/usr/lib/systemd/system
mkdir -p "$W/multi-user.target.wants" "$W/sockets.target.wants" "$W/sysinit.target.wants"
en(){ [ -e "$D$U/$2" ] || die "la imagen no trae la unidad $2"; ln -sf "$U/$2" "$W/$1.wants/${3:-$2}"; }
en multi-user.target ssh.service
en multi-user.target wpa_supplicant@.service wpa_supplicant@wlan0.service
en multi-user.target systemd-networkd.service
en sockets.target systemd-networkd.socket
ln -sf "$U/systemd-networkd.service" "$W/dbus-org.freedesktop.network1.service"
# sin asistentes interactivos de primer arranque (la pantalla no tiene teclado)
ln -sf /dev/null "$W/userconfig.service"; ln -sf /dev/null "$W/systemd-firstboot.service"
[ -e "$D$U/systemd-network-generator.service" ] && en sysinit.target systemd-network-generator.service
# red: DHCP por systemd-networkd en wlan0; NM no gestiona wlan0; DNS fijo (sin systemd-resolved)
mkdir -p "$D/etc/systemd/network" "$D/etc/NetworkManager/conf.d" "$D/etc/modprobe.d"
cp -f "$HERE/deploy/usr/share/qvadis/10-wlan0.network.default" "$D/etc/systemd/network/10-wlan0.network"
cp -f "$HERE/deploy/etc/NetworkManager/conf.d/99-unmanaged-wlan0.conf" "$D/etc/NetworkManager/conf.d/"
cp -f "$HERE/deploy/etc/modprobe.d/qvadis-no-bt.conf" "$D/etc/modprobe.d/"
rm -f "$D/etc/resolv.conf"; printf 'nameserver 1.1.1.1\nnameserver 9.9.9.9\n' > "$D/etc/resolv.conf"
# WiFi bloqueado por rfkill hasta fijar pais: desbloquearlo antes de wpa_supplicant
cat > "$W/qvadis-rfkill-unblock.service" <<'EOF'
[Unit]
Description=Desbloquear WiFi (rfkill) antes de wpa_supplicant
Before=wpa_supplicant@wlan0.service wpa_supplicant.service
After=systemd-rfkill.service
[Service]
Type=oneshot
ExecStart=-/usr/sbin/rfkill unblock wifi
[Install]
WantedBy=multi-user.target
EOF
ln -sf /etc/systemd/system/qvadis-rfkill-unblock.service "$W/multi-user.target.wants/qvadis-rfkill-unblock.service"
if [ -n "$WIFI_SSID" ]; then
  mkdir -p "$D/etc/wpa_supplicant"
  SSIDHEX="$(printf '%s' "$WIFI_SSID" | od -An -tx1 | tr -d ' \n')"
  {
    echo "ctrl_interface=DIR=/run/wpa_supplicant GROUP=netdev"; echo "update_config=1"; echo "country=ES"
    echo "network={"; echo "	ssid=$SSIDHEX"; echo "	scan_ssid=1"
    if [ -n "$WIFI_PASS" ]; then echo "	key_mgmt=WPA-PSK"; echo "	psk=\"$WIFI_PASS\""; echo "	ieee80211w=0"; else echo "	key_mgmt=NONE"; fi
    echo "}"
  } > "$D/etc/wpa_supplicant/wpa_supplicant-wlan0.conf"
  chmod 600 "$D/etc/wpa_supplicant/wpa_supplicant-wlan0.conf"
fi
sync; umount "$D"
say "rootfs instalada en $SPARE (PARTUUID=$SPUUID)"

# --------- 5) boot (p1, 64 MB): se prepara TODO como *.qvnew y se renombra al final ---------
say "preparando $BOOT (copias .qvnew; el stock sigue intacto hasta el renombrado)..."
cp -a "$BOOT" "/root/boot-stock-$(date +%s)" 2>/dev/null || true
cp -f "$BOOT/config.txt"  "$BOOT/config.txt.stock"  2>/dev/null || true
cp -f "$BOOT/cmdline.txt" "$BOOT/cmdline.txt.stock" 2>/dev/null || true
mkdir -p "$BOOT/overlays"
( cd "$WORK/boot" && for f in $(find . -type f ! -name cmdline.txt | sed 's|^\./||'); do
    mkdir -p "$BOOT/$(dirname "$f")"; cp -f "$f" "$BOOT/$f.qvnew"; done )
for f in "$HERE"/deploy/boot/firmware/overlays/*.dtbo; do cp -f "$f" "$BOOT/overlays/$(basename "$f").qvnew"; done
# cmdline: base de RPi OS (sin root=/resize/init=) + nuestros parametros + root = destino
BASECMD="$(cat "$WORK/boot/cmdline.txt")"
CMD="$(sed -E 's#(^| )root=[^ ]+##; s#(^| )resize( |$)# #; s#(^| )init=[^ ]+##; s/console=tty1/console=tty3/' <<<"$BASECMD")"
for p in dwc_otg.lpm_enable=0 dwc_otg.speed=1 cfg80211.ieee80211_regdom=ES console=tty3 root=PARTUUID=$SPUUID quiet loglevel=3 \
         logo.nologo vt.global_cursor_default=0 plymouth.ignore-serial-consoles splash; do
  grep -qw -- "$p" <<<"$CMD" || CMD="$CMD $p"
done
echo "$CMD" | tr -s ' ' | sed 's/^ //' > "$BOOT/cmdline.txt.qvnew"
cp -f "$HERE/deploy/boot/firmware/config.txt" "$BOOT/config.txt.qvnew"
sync
# verificar las copias antes de renombrar
bad=0; for n in $(find "$BOOT" -name '*.qvnew'); do
  b="${n%.qvnew}"; src=""
  case "$b" in "$BOOT/cmdline.txt"|"$BOOT/config.txt") continue;; esac
  rel="${b#$BOOT/}"; [ -f "$WORK/boot/$rel" ] && src="$WORK/boot/$rel" || src="$HERE/deploy/boot/firmware/$rel"
  cmp -s "$src" "$n" || { err "copia corrupta: $rel"; bad=1; }
done
[ "$bad" = 0 ] || die "copias del boot corruptas; el stock sigue intacto (quita los *.qvnew)"
# renombrado: firmware/kernel/dtb/overlays primero, config y cmdline los ULTIMOS
for n in $(find "$BOOT" -name '*.qvnew' ! -name 'config.txt.qvnew' ! -name 'cmdline.txt.qvnew'); do mv -f "$n" "${n%.qvnew}"; done
mv -f "$BOOT/config.txt.qvnew"  "$BOOT/config.txt"
mv -f "$BOOT/cmdline.txt.qvnew" "$BOOT/cmdline.txt"
for f in kernel7.img start.elf fixup.dat bootcode.bin bcm2710-rpi-cm3.dtb overlays/goodix-qv.dtbo overlays/vc4-kms-v3d.dtbo; do
  [ -f "$BOOT/$f" ] || err "AVISO: falta $BOOT/$f tras la copia"; done
sync
USED=$(du -sm "$BOOT" | cut -f1); say "uso de $BOOT: ${USED} MB (limite ~64)"

say "LISTO. cmdline -> root=PARTUUID=$SPUUID"
say "Tras reiniciar arrancara RPi OS Lite (tarda ~1 min en dar red). Para entrar:"
say "    usuario:     pi"
say "    contraseña:  la que acabas de introducir (NO se guarda en ningun sitio: apuntala)"
say "    direccion:   ssh pi@raspberrypi.local   (si tu PC resuelve .local) o la IP que el router asigne a 'raspberrypi'"
[ -n "$SSHKEY" ] && say "    (tambien entra con la clave SSH publica que has indicado)"
say "Despues copia el repo y ejecuta install.sh."
if [ "${NO_REBOOT:-0}" = 1 ]; then exit 0; fi
if confirm "Reiniciar ahora"; then sync; reboot -f; else say "Reinicia con: reboot -f (el inittab del stock puede colgar en reboot limpio)"; fi
