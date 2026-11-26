#!/bin/bash
# anular-crons-stock.sh - Se ejecuta EN la pantalla de fabrica (Yocto), como root.
# Desactiva los crons del stock que reinician la placa o relanzan la interfaz, para que no
# interrumpan una liberacion (liberar.sh) que dura varios minutos.
#
# Que anula (los mueve a una carpeta de respaldo; NADA se borra):
#   /etc/cron.d/{reboot,watchdog_gui,watchdog_pulse,watchdog_server,remoteCommand}
#     - reboot            reinicio programado (lunes 01:00, con retardo aleatorio de hasta 1 h)
#     - watchdog_pulse    si falta el audio USB: reinicia pulseaudio y la GUI cada minuto y reinicia la placa
#     - watchdog_server   reinicia la placa si falla el servidor (API :5000)
#     - watchdog_gui      relanza la GUI si no ve la ventana
#     - remoteCommand     sondeo a la nube de Qvadis (ya cerrada)
#   Se queda /etc/cron.d/datetime (ajusta la hora; no reinicia nada).
# Y para los procesos de esos scripts que ya estuvieran en marcha (sobre todo un qvReboot.sh
# dormido que reiniciaria la placa al despertar).
#
# Uso:
#   anular-crons-stock.sh              anula
#   anular-crons-stock.sh --restaurar  devuelve los crons a /etc/cron.d
#   anular-crons-stock.sh --estado     solo muestra el estado
# Variables (pruebas): QV_ROOT=/ruta (raiz alternativa)  QV_NOKILL=1 (no tocar procesos)
#                      QV_SCRIPTS_DIR=/ruta (donde estan los scripts del stock)
set -u
ROOT="${QV_ROOT:-}"
CRON="$ROOT/etc/cron.d"
BAK="$ROOT/home/root/qvadis-cron-backup"
KEEP="datetime"
SCRIPTS="${QV_SCRIPTS_DIR:-/usr/local/qvadis/resources/scripts}"
ANULAR="reboot watchdog_gui watchdog_pulse watchdog_server remoteCommand"
say(){ printf '[crons] %s\n' "$*"; }
die(){ printf '[crons] ERROR: %s\n' "$*" >&2; exit 1; }

MODE="anular"
case "${1:-}" in
  ""|--anular) MODE=anular;; --restaurar) MODE=restaurar;; --estado) MODE=estado;;
  *) die "uso: $0 [--anular|--restaurar|--estado]";;
esac

[ -z "$ROOT" ] && [ "$(id -u)" != 0 ] && die "ejecuta como root"
[ -d "$CRON" ] || die "no existe $CRON"
# Solo en el stock de fabrica: en Debian/Raspberry Pi OS no hay nada que anular
if [ -z "$ROOT" ] && grep -qiE '^(ID|ID_LIKE)=.*(debian|raspbian)' /etc/os-release 2>/dev/null; then
  say "este equipo ya corre Debian/Raspberry Pi OS: no hay crons del stock que anular"; exit 0
fi

estado(){
  say "crons activos en $CRON: $(ls "$CRON" 2>/dev/null | tr '\n' ' ')"
  [ -d "$BAK" ] && say "en respaldo ($BAK): $(ls "$BAK" 2>/dev/null | tr '\n' ' ')" || say "sin respaldo todavia"
}

# PIDs de este proceso y de todos sus ancestros (shell, sudo, sshd...): NUNCA se matan, aunque su
# linea de comandos mencione el script (p. ej. un "ssh 'bash ...qvReboot.sh...'").
ancestros(){
  local p=$$ out=" "
  while [ -n "$p" ] && [ "$p" -gt 1 ] 2>/dev/null; do
    out="$out$p "
    p=$(awk '{print $4}' "/proc/$p/stat" 2>/dev/null)
  done
  echo "$out"
}
matar(){
  [ -n "${QV_NOKILL:-}" ] && return 0
  local prot; prot=$(ancestros)
  # procesos de los scripts del stock que reinician o relanzan. Se busca la RUTA COMPLETA como
  # primer argumento de un interprete, no cualquier linea que contenga el nombre.
  for name in qvReboot.sh watchdog_pulse.sh watchdog_server.sh watchdog_gui.sh; do
    for pid in $(pgrep -f "^(/bin/|/usr/bin/)?(ba)?sh( -[a-z]+)* $SCRIPTS/$name( |\$)" 2>/dev/null); do
      case "$prot" in *" $pid "*) continue;; esac
      kill "$pid" 2>/dev/null && say "parado proceso $pid ($name)"
    done
  done
  # Red de seguridad: la regex de arriba tiene grupos y alternancias cuyo soporte en el pgrep de
  # busybox del stock no esta garantizado (parte del userland es busybox; ver notas del
  # 2026-11-24 sobre 'tar'). Si por eso no encontrara nada (en vez de no haber nada que matar), un
  # qvReboot.sh dormido seguiria vivo sin que nadie se enterase. Se comprueba con un pgrep mas
  # simple (solo subcadena, sin grupos: deberia funcionar en cualquier pgrep) y, si queda algo
  # vivo que el de arriba no paro, se avisa FUERTE en vez de seguir en silencio.
  for name in qvReboot.sh watchdog_pulse.sh watchdog_server.sh watchdog_gui.sh; do
    for pid in $(pgrep -f "$SCRIPTS/$name" 2>/dev/null); do
      case "$prot" in *" $pid "*) continue;; esac
      kill -0 "$pid" 2>/dev/null || continue
      say "AVISO: sigue vivo el proceso $pid ($name) tras anular (¿el pgrep con filtros no funciona en este stock?); matalo a mano: kill $pid"
    done
  done
}

case "$MODE" in
  estado) estado;;
  anular)
    mkdir -p "$BAK" || die "no puedo crear $BAK"
    n=0
    for c in $ANULAR; do
      if [ -f "$CRON/$c" ]; then
        # si ya hay uno con ese nombre en el respaldo, no se pisa
        dest="$BAK/$c"; [ -e "$dest" ] && dest="$BAK/$c.$(date +%Y%m%d%H%M%S)"
        mv "$CRON/$c" "$dest" || die "no pude mover $c"
        say "anulado: $c -> $dest"; n=$((n+1))
      fi
    done
    [ "$n" = 0 ] && say "no habia nada que anular (ya estaba hecho)"
    matar
    # cualquier otro cron raro distinto del que se conserva: avisar, no tocar
    for f in "$CRON"/*; do
      [ -f "$f" ] || continue; b=$(basename "$f")
      [ "$b" = "$KEEP" ] || say "AVISO: queda otro cron no previsto en $CRON: $b (revisalo)"
    done
    # crontab de root (si existe)
    for ct in "$ROOT"/var/spool/cron/crontabs/root "$ROOT"/var/spool/cron/root "$ROOT"/etc/crontab; do
      [ -f "$ct" ] && grep -qE 'reboot|watchdog|qvReboot' "$ct" 2>/dev/null && say "AVISO: $ct menciona reboot/watchdog (no lo toco): revisalo"
    done
    estado
    say "listo. Para devolverlos:  $0 --restaurar";;
  restaurar)
    [ -d "$BAK" ] || die "no hay respaldo en $BAK"
    n=0
    for f in "$BAK"/*; do
      [ -f "$f" ] || continue; b=$(basename "$f"); b=${b%%.20[0-9]*}
      [ -e "$CRON/$b" ] && { say "ya existe $b en $CRON: lo dejo en el respaldo"; continue; }
      mv "$f" "$CRON/$b" && { say "restaurado: $b"; n=$((n+1)); }
    done
    [ "$n" = 0 ] && say "no habia nada que restaurar"
    estado;;
esac
