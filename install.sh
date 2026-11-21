#!/bin/bash
# install.sh - Provisiona un Raspberry Pi OS Lite (CM3+, placa Qvadis) como videoportero
# abierto Qvadis: dependencias, ficheros (deploy/), arranque/panel, secretos, servicios y A/B.
#
# Ejecutar como root SOBRE un RPi OS Lite ya arrancado en la placa, desde la raiz del repo:
#   sudo ./install.sh                 # interactivo (pregunta los secretos)
#   sudo ./install.sh --answers f.conf --yes
# Banderas:
#   --answers FILE  lee secretos de un fichero (ver config.local.example)
#   --no-ab         no configura el A/B (segundo slot) ni formatea nada
#   --no-reboot     no reinicia al terminar
#   --yes           no pide confirmaciones
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DEPLOY="$HERE/deploy"
ANSWERS=""; DO_AB=1; DO_REBOOT=1; ASSUME_YES=0
while [ $# -gt 0 ]; do case "$1" in
  --answers) ANSWERS="$2"; shift 2;; --no-ab) DO_AB=0; shift;;
  --no-reboot) DO_REBOOT=0; shift;; --yes|-y) ASSUME_YES=1; shift;;
  *) echo "opcion desconocida: $1"; exit 2;; esac; done

say(){ printf '\033[1;36m[qvadis]\033[0m %s\n' "$*"; }
err(){ printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; }
# Sin terminal (ssh sin -t, automatizacion) /dev/tty no existe: los valores quedan vacios en vez de abortar con set -u
ask(){ local p="$1" d="${2:-}" v=""; if [ "$ASSUME_YES" = 1 ] && [ -n "$d" ]; then echo "$d"; return; fi
  read -r -p "$p${d:+ [$d]}: " v 2>/dev/null </dev/tty || true; echo "${v:-$d}"; }
asksecret(){ local p="$1" v=""; [ "$ASSUME_YES" = 1 ] && { echo ""; return 0; }
  read -r -s -p "$p: " v 2>/dev/null </dev/tty || true; echo >&2; echo "$v"; }
confirm(){ [ "$ASSUME_YES" = 1 ] && return 0; local a=""; read -r -p "$1 [s/N]: " a 2>/dev/null </dev/tty || true; [ "$a" = s ] || [ "$a" = S ]; }

# ---------- 0) pre-flight ----------
[ "$(id -u)" = 0 ] || { err "ejecuta como root (sudo)"; exit 1; }
[ -d "$DEPLOY" ] || { err "no encuentro deploy/ junto a install.sh"; exit 1; }
[ -d /boot/firmware ] || { err "no es un RPi OS reciente (falta /boot/firmware)"; exit 1; }
grep -qi raspberry /proc/device-tree/model 2>/dev/null || say "aviso: el modelo no dice 'Raspberry'; continuo igual"
id pi >/dev/null 2>&1 || { err "falta el usuario 'pi' (los servicios corren como pi). Crealo antes (Raspberry Pi Imager / userconf.txt)"; exit 1; }
ROOTPUUID="$(findmnt -no PARTUUID / 2>/dev/null || true)"
[ -n "$ROOTPUUID" ] || { err "no puedo determinar el PARTUUID de /"; exit 1; }
PFX="${ROOTPUUID%-*}"
say "Raiz actual: $ROOTPUUID (prefijo $PFX)"
[ -f "$ANSWERS" ] && { say "leyendo respuestas de $ANSWERS"; . "$ANSWERS"; }

# ---------- 1) dependencias ----------
PKGS="nodejs cog libwpewebkit-2.0-1 libgles2 libegl1 libgbm1 mesa-libgallium fontconfig fonts-inter \
asterisk baresip alsa-utils pigpio ffmpeg iw rfkill rsync curl openssl python3 wpasupplicant fail2ban nftables \
pulseaudio pulseaudio-utils libwebrtc-audio-processing-1-3 \
plymouth plymouth-themes baresip-x11 rtkit asterisk-core-sounds-es-gsm"
say "instalando dependencias (apt)..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
# Kernels v6/v8 sobran en el CM3+ (solo v7): liberan espacio y evitan que raspi-firmware copie kernels e
# initramfs extra a la p1 de 64 MB. Va ANTES de instalar paquetes: el postinst de plymouth genera un
# initramfs (~14 MB) por CADA kernel instalado y desbordaria la p1 (como se hizo en la unidad de pruebas)
if [ "$(uname -m)" = armv7l ] && uname -r | grep -q -- '-rpi-v7$'; then
  OLDK="$(dpkg -l 'linux-image-*rpi-v6' 'linux-image-*rpi-v8' 'linux-headers-*rpi-v6' 'linux-headers-*rpi-v8' 'linux-base-*rpi-v6' 'linux-base-*rpi-v8' 2>/dev/null | awk '/^ii/{print $2}' || true)"
  # shellcheck disable=SC2086
  [ -z "$OLDK" ] || { say "quitando kernels v6/v8 no usados..."; apt-get purge -y $OLDK || true; apt-get autoremove --purge -y || true; }
fi

# shellcheck disable=SC2086
apt-get install -y --no-install-recommends $PKGS || { err "fallo apt; revisa red/espacio"; exit 1; }

# ---------- 2) ficheros (deploy -> /) ----------
say "copiando ficheros del sistema (deploy/)..."
# opt, usr, var, root, y etc SIN pisar los secretos reales que ya pudiera haber
rsync -aHAX "$DEPLOY/opt/"  /opt/
rsync -aHAX "$DEPLOY/usr/"  /usr/
# el estado del usuario (config.json, calls.json) nunca viaja en deploy/
rsync -aHAX --exclude=/lib/qvadis/config.json --exclude=/lib/qvadis/calls.json "$DEPLOY/var/" /var/
# Secretos/estado: NO se pisan si ya estan configurados (volver a ejecutar install.sh no debe romper SIP ni WiFi)
rsync -aHAX --exclude=/.baresip/accounts "$DEPLOY/root/" /root/
rsync -aHAX --exclude=/asterisk/pjsip.conf --exclude=/wpa_supplicant/wpa_supplicant-wlan0.conf "$DEPLOY/etc/" /etc/
[ -e /root/.baresip/accounts ] || install -D -m0600 "$DEPLOY/root/.baresip/accounts" /root/.baresip/accounts
[ -e /etc/wpa_supplicant/wpa_supplicant-wlan0.conf ] || install -D -m0600 "$DEPLOY/etc/wpa_supplicant/wpa_supplicant-wlan0.conf" /etc/wpa_supplicant/wpa_supplicant-wlan0.conf
# pjsip.conf: el del paquete Asterisk NO es el nuestro; el nuestro lleva la marca "; Qvadis - PJSIP"
grep -q '^; Qvadis - PJSIP' /etc/asterisk/pjsip.conf 2>/dev/null || install -D -m0640 -o asterisk -g asterisk "$DEPLOY/etc/asterisk/pjsip.conf" /etc/asterisk/pjsip.conf
# Red: DHCP por defecto SOLO si no hay ya un fichero (no se pisa una IP fija; la OTA tampoco lo toca)
[ -f /etc/systemd/network/10-wlan0.network ] || install -D -m0644 /usr/share/qvadis/10-wlan0.network.default /etc/systemd/network/10-wlan0.network
# Version instalada = la del repo (el OTA la compara con VERSION remoto)
install -m0644 "$HERE/VERSION" /etc/qvadis-version 2>/dev/null || true
# Grupos de pi: asterisk (socket de control de Asterisk), audio/video/render/input (kiosco, audio), gpio (pigpio), netdev (wpa_supplicant)
for g in asterisk audio video render input gpio netdev; do getent group "$g" >/dev/null 2>&1 && usermod -aG "$g" pi; done
chmod 0440 /etc/sudoers.d/qvadis 2>/dev/null || true
chmod 0755 /usr/local/sbin/qvadis-* 2>/dev/null || true
mkdir -p /var/lib/qvadis/snaps
# Nombre del equipo (en la unidad de pruebas: qvadis-rpi). Cambialo con QV_HOSTNAME si tienes varias pantallas.
QVHOST="${QV_HOSTNAME:-qvadis-rpi}"
if [ -z "${QV_HOSTNAME:-}" ] && [ "$(cat /etc/hostname 2>/dev/null || true)" != raspberrypi ]; then
  say "nombre del equipo conservado: $(cat /etc/hostname)"
elif [[ "$QVHOST" =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]]; then
  OLDHOST="$(cat /etc/hostname 2>/dev/null || true)"
  hostnamectl set-hostname "$QVHOST" 2>/dev/null || { echo "$QVHOST" > /etc/hostname; hostname "$QVHOST" 2>/dev/null || true; }
  echo "$QVHOST" > /etc/hostname
  if [ -n "$OLDHOST" ] && [ "$OLDHOST" != "$QVHOST" ]; then sed -i "s/\b$OLDHOST\b/$QVHOST/g" /etc/hosts; fi
  grep -q "$QVHOST" /etc/hosts || printf '127.0.1.1\t%s\n' "$QVHOST" >> /etc/hosts
else
  say "aviso: QV_HOSTNAME='$QVHOST' no es valido (a-z, 0-9 y -); dejo el nombre actual"
fi

# ---------- 3) arranque / panel (config.txt + cmdline.txt) ----------
say "configurando arranque y panel..."
CFG=/boot/firmware/config.txt; CMD=/boot/firmware/cmdline.txt
cp -n "$CFG" "$CFG.qvadis-orig" 2>/dev/null || true
cp -n "$CMD" "$CMD.qvadis-orig" 2>/dev/null || true
# Anadir bloque Qvadis a config.txt (idempotente): lineas del panel (gpio/kms/goodix/hdmi/splash...)
# Solo se anaden las lineas que aun no estan (tras liberar.sh el config.txt ya es el nuestro: no se duplica nada)
NEWCFG="$(grep -E '^(arm_64bit|kernel=|enable_uart|dtparam=|display_rotate|gpio=|dtoverlay=vc4-kms|dtoverlay=goodix|hdmi_|disable_splash|auto_initramfs|disable_overscan|framebuffer_|max_framebuffers)' "$DEPLOY/boot/firmware/config.txt" \
  | while IFS= read -r l; do grep -qxF "$l" "$CFG" || printf '%s\n' "$l"; done)"
if [ -n "$NEWCFG" ] && ! grep -q "QVADIS-BEGIN" "$CFG"; then
  # el vc4-kms de serie (sin ,audio=off) se sustituye por el nuestro (evita cargarlo dos veces)
  sed -i -E 's/^(dtoverlay=vc4-f?kms-v3d)$/#\1  # QVADIS: sustituido abajo/' "$CFG"
  { echo ""; echo "# QVADIS-BEGIN (panel Qvadis; no editar a mano)"; printf '%s\n' "$NEWCFG"; echo "# QVADIS-END"; } >> "$CFG"
fi
# cmdline: fusionar nuestros parametros, conservando el root REAL de esta placa
CUR="$(cat "$CMD")"
for p in dwc_otg.lpm_enable=0 dwc_otg.speed=1 cfg80211.ieee80211_regdom=ES quiet loglevel=3 logo.nologo \
         vt.global_cursor_default=0 plymouth.ignore-serial-consoles splash; do
  grep -qw -- "$p" <<<"$CUR" || CUR="$CUR $p"
done
CUR="$(sed -E 's/console=tty1/console=tty3/' <<<"$CUR")"
echo "$CUR" > "$CMD"
# overlay(s) personalizados del panel (p.ej. goodix-qv.dtbo, tactil Goodix)
if ls "$DEPLOY"/boot/firmware/overlays/*.dtbo >/dev/null 2>&1; then
  mkdir -p /boot/firmware/overlays
  cp -f "$DEPLOY"/boot/firmware/overlays/*.dtbo /boot/firmware/overlays/
fi
# plymouth tema umbral
plymouth-set-default-theme -R umbral 2>/dev/null || true

# ---------- 4) secretos (WiFi / SIP / PIN / zona) ----------
say "configuracion del equipo (secretos)"
WIFI_SSID="${WIFI_SSID:-$(ask 'SSID de tu WiFi')}"
WIFI_PASS="${WIFI_PASS:-$(asksecret 'Contraseña WiFi')}"
SIP_PASS="${SIP_PASS:-$(asksecret 'Contraseña SIP (para panel y moviles)')}"
CJ=/var/lib/qvadis/config.json
PIN_KEEP=0
if [ -z "${PANEL_PIN:-}" ] && [ -f "$CJ" ] && grep -q '"pin"' "$CJ"; then PIN_KEEP=1; say "PIN del panel existente: se conserva"
else PANEL_PIN="${PANEL_PIN:-$(ask 'PIN del panel (4 digitos)' 1234)}"; fi
DEFTZ=Europe/Madrid; [ -f "$CJ" ] && [ -s /etc/timezone ] && DEFTZ="$(cat /etc/timezone)"
TZ="${TZ:-$(ask 'Zona horaria' "$DEFTZ")}"

# --- validacion: estos valores se escriben en ficheros de configuracion; caracteres raros los romperian en silencio ---
case "$WIFI_SSID$WIFI_PASS" in *[[:cntrl:]]*) err "SSID/clave WiFi con caracteres de control"; exit 1;; esac
if [ -n "$WIFI_SSID" ] && [ -n "$WIFI_PASS" ]; then
  { [ ${#WIFI_PASS} -ge 8 ] && [ ${#WIFI_PASS} -le 63 ]; } || { err "la clave WiFi debe tener entre 8 y 63 caracteres (si no, la WiFi no conectara al reiniciar)"; exit 1; }
fi
if [ -n "$SIP_PASS" ] && ! [[ "$SIP_PASS" =~ ^[A-Za-z0-9._~+-]{8,64}$ ]]; then
  err "la clave SIP debe tener 8-64 caracteres entre letras, numeros y . _ ~ + - (otros romperian pjsip.conf/baresip)"; exit 1; fi
if [ "$PIN_KEEP" != 1 ] && ! [[ "${PANEL_PIN:-}" =~ ^[0-9]{4,6}$ ]]; then err "el PIN del panel debe tener 4-6 digitos"; exit 1; fi
# Clave SIP vacia: NUNCA dejar la de plantilla (CHANGE_ME es publica y el UDP 5060 esta abierto en la LAN): se genera una al azar.
# No hace falta recordarla: panel y baresip la leen de los ficheros y los moviles se emparejan por QR.
if [ -z "$SIP_PASS" ] && grep -qs CHANGE_ME /etc/asterisk/pjsip.conf /root/.baresip/accounts; then
  SIP_PASS="$(openssl rand -hex 16)"; say "clave SIP generada al azar (no hace falta recordarla: los moviles se emparejan por QR)"
fi

WPA=/etc/wpa_supplicant/wpa_supplicant-wlan0.conf
if [ -n "$WIFI_SSID" ]; then
  PMK="$(wpa_passphrase "$WIFI_SSID" "$WIFI_PASS" 2>/dev/null | grep -oP '^\s*psk=\K[0-9a-f]{64}' || true)"
  {
    echo "ctrl_interface=DIR=/run/wpa_supplicant GROUP=netdev"; echo "update_config=1"; echo "country=ES"
    echo "network={"; echo "	ssid=$(printf '%s' "$WIFI_SSID" | od -An -tx1 | tr -d ' \n')"; echo "	scan_ssid=1"; echo "	key_mgmt=WPA-PSK"
    [ -n "$PMK" ] && echo "	psk=$PMK" || echo "	psk=\"$WIFI_PASS\""
    echo "	ieee80211w=0"; echo "}"
  } > "$WPA"; chmod 600 "$WPA"
fi
if [ -n "$SIP_PASS" ]; then
  sed -i "s/password=CHANGE_ME/password=$SIP_PASS/g" /etc/asterisk/pjsip.conf 2>/dev/null || true
  sed -i "s/auth_pass=CHANGE_ME/auth_pass=$SIP_PASS/g" /root/.baresip/accounts 2>/dev/null || true
fi
# PIN en la config del manager
if [ "$PIN_KEEP" = 1 ]; then :
elif [ -f "$CJ" ]; then
  python3 - "$CJ" "$PANEL_PIN" <<'PY' 2>/dev/null || true
import json,sys
p,pin=sys.argv[1],sys.argv[2]
try: d=json.load(open(p))
except Exception: d={}
d['pin']=pin; json.dump(d,open(p,'w'),indent=2)
PY
else
  mkdir -p /var/lib/qvadis; echo "{\"pin\":\"$PANEL_PIN\"}" > "$CJ"
fi
chown -R pi:pi /var/lib/qvadis 2>/dev/null || true
timedatectl set-timezone "$TZ" 2>/dev/null || { ln -sf "/usr/share/zoneinfo/$TZ" /etc/localtime; echo "$TZ" > /etc/timezone; }

# ---------- 4b) certificado TLS autofirmado (SIP-TLS/SRTP; la app lo fija por pinning) ----------
say "certificado TLS autofirmado..."
mkdir -p /etc/asterisk/keys
if [ ! -f /etc/asterisk/keys/qvadis.crt ]; then
  LANIP="$(hostname -I | awk '{print $1}')"
  SAN="DNS:qvadis,DNS:localhost${LANIP:+,IP:$LANIP}${DOMAIN:+,DNS:$DOMAIN}"
  openssl req -x509 -newkey rsa:2048 -keyout /etc/asterisk/keys/qvadis.key \
    -out /etc/asterisk/keys/qvadis.crt -days 3650 -nodes \
    -subj "/CN=qvadis-videoportero" -addext "subjectAltName=$SAN" >/dev/null 2>&1 || say "aviso: no se pudo generar el cert"
fi
chown -R asterisk:asterisk /etc/asterisk/keys 2>/dev/null || true
chmod 644 /etc/asterisk/keys/qvadis.crt 2>/dev/null || true
chmod 600 /etc/asterisk/keys/qvadis.key 2>/dev/null || true
# copia legible por el manager (pi) para el HTTPS remoto (:8443)
mkdir -p /var/lib/qvadis/certs
cp -f /etc/asterisk/keys/qvadis.crt /var/lib/qvadis/certs/qvadis.crt
cp -f /etc/asterisk/keys/qvadis.key /var/lib/qvadis/certs/qvadis.key
chown -R pi:pi /var/lib/qvadis/certs 2>/dev/null || true
chmod 644 /var/lib/qvadis/certs/qvadis.crt; chmod 600 /var/lib/qvadis/certs/qvadis.key

# ---------- 4b) audio: PulseAudio como pi, persistente ----------
# El intercom local y baresip (audio del movil) usan los dispositivos AEC de PulseAudio,
# que corre como 'pi'. Linger = el Pulse de pi arranca al boot aunque nadie inicie sesion.
say "configurando PulseAudio (pi, persistente)..."
loginctl enable-linger pi 2>/dev/null || true
sudo -u pi XDG_RUNTIME_DIR=/run/user/1000 systemctl --user enable pulseaudio.socket pulseaudio.service 2>/dev/null || true
chmod 0755 /var/lib/qvadis/audio-aec-up.sh /opt/qvadis-ui/intercom-on.sh /opt/qvadis-ui/intercom-off.sh /opt/qvadis-ui/audio-on.sh 2>/dev/null || true

# ---------- 5) servicios ----------
say "habilitando servicios..."
systemctl daemon-reload
for s in ssh pigpiod systemd-networkd wpa_supplicant@wlan0 nftables fail2ban \
         qvadis-manager qvadis-kiosk qvadis-sip qvadis-wifi-powersave \
         qvadis-wifi-reset qvadis-wifi-reset.timer qvadis-ota-confirm; do
  systemctl enable "$s" 2>/dev/null || say "aviso: no se pudo habilitar $s"
done
# Sin asistentes interactivos de primer arranque (la pantalla no tiene teclado)
systemctl mask userconfig.service systemd-firstboot.service 2>/dev/null || true
# Asterisk: sin chan_sip (usamos PJSIP); el modules.conf de deploy ya lo gestiona
systemctl enable asterisk 2>/dev/null || true
# Bluetooth: deshabilitado (combo rtl8723bu WiFi+BT; su coexistencia tira el WiFi y no se usa).
# El blacklist de /etc/modprobe.d/qvadis-no-bt.conf evita cargar btusb en el proximo arranque.
systemctl disable --now bluetooth 2>/dev/null || true
rfkill block bluetooth 2>/dev/null || true

# ---------- 6) A/B (slot B en la particion libre) ----------
if [ "$DO_AB" = 1 ]; then
  # slot inactivo = la "otra" particion de 3,5G (02 si corremos en 03, y viceversa)
  case "$ROOTPUUID" in *-03) BSUF=02;; *-02) BSUF=03;; *) BSUF="";; esac
  BDEV=""; [ "$BSUF" = 02 ] && BDEV=/dev/mmcblk0p2; [ "$BSUF" = 03 ] && BDEV=/dev/mmcblk0p3
  if [ -n "$BDEV" ] && [ -b "$BDEV" ]; then
    if [ -f /boot/firmware/cmdline_a.txt ] && [ -f /boot/firmware/cmdline_b.txt ] && [ "${FORCE_AB:-0}" != 1 ]; then
      say "A/B ya configurado: omitido (FORCE_AB=1 lo rehace y BORRA el slot B)"
    elif confirm "Configurar A/B: FORMATEAR $BDEV como slot B y clonar el sistema (para OTA con rollback)?"; then
      say "preparando slot B en $BDEV (esto borra su contenido)..."
      umount "$BDEV" 2>/dev/null || true
      mkfs.ext4 -F -L rootfsB "$BDEV" >/dev/null
      mkdir -p /mnt/ab; mount "$BDEV" /mnt/ab
      rsync -aHAXx --delete --exclude=/tmp/* --exclude=/var/tmp/* \
        --exclude=/var/cache/apt/archives/* --exclude=/var/log/* \
        --exclude=/mnt/* --exclude=/media/* --exclude=/lost+found / /mnt/ab/
      sed -i "/[[:space:]]\/[[:space:]].*ext4/ s/PARTUUID=[0-9a-f-]*/PARTUUID=$PFX-$BSUF/" /mnt/ab/etc/fstab
      umount /mnt/ab
      # ficheros de cmdline por slot + tryboot (los usa qvadis-ota)
      sed "s/root=PARTUUID=[0-9a-f-]*/root=PARTUUID=$PFX-03/" "$CMD" > /boot/firmware/cmdline_a.txt
      sed "s/root=PARTUUID=[0-9a-f-]*/root=PARTUUID=$PFX-02/" "$CMD" > /boot/firmware/cmdline_b.txt
      say "A/B listo (slot B = $PFX-$BSUF). El OTA usara tryboot con rollback."
    else
      say "A/B omitido."
    fi
  else
    say "no hay segunda particion de 3,5G -> A/B omitido (OTA de ficheros seguira disponible sin doble slot)."
  fi
fi

say "Instalacion completada."
grep -qE '"pin": *"1234"' /var/lib/qvadis/config.json 2>/dev/null && say "AVISO: el PIN del panel es el de FABRICA (1234). Cambialo en Ajustes > Seguridad · PIN."
if [ "$DO_REBOOT" = 1 ] && confirm "Reiniciar ahora para arrancar el videoportero?"; then
  sync; reboot
else
  say "Reinicia cuando quieras: sudo reboot"
fi
