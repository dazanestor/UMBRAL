#!/bin/sh
# Deja PERSISTENTES los dispositivos con cancelacion de eco (AEC webrtc) de ambas tarjetas,
# para que los use TANTO el intercom local (loopbacks) COMO baresip (audio del movil<->calle).
# Idempotente: si ya existen, no hace nada. Lo llama el manager al arrancar.
# HOME=pantalla (puerto USB 1-1.2), STREET=calle (puerto USB 1-1.3). Se resuelven por puerto abajo.
export XDG_RUNTIME_DIR=/run/user/1000

# Espera a que PulseAudio este listo (tras un reboot puede tardar unos segundos). Sin esto,
# los module-echo-cancel no se cargan y baresip no puede abrir el audio ("No such device").
i=0
while [ $i -lt 30 ]; do pactl info >/dev/null 2>&1 && break; sleep 1; i=$((i+1)); done

# Front-end analogico ON (amp/mic) por GPIO
for g in 16 22 23 17 27; do pigs w "$g" 1 2>/dev/null; done

export LC_ALL=C
# Resolver las tarjetas por PUERTO USB FISICO (estable entre reinicios), NO por orden de
# enumeracion: el sufijo ".2" de PulseAudio depende de cual card enumere antes y puede invertirse,
# intercambiando home<->street (intercom al reves, timbre por el altavoz equivocado).
# HOME (pantalla) = puerto 1-1.2 ; STREET (calle) = puerto 1-1.3 (verificado 2026-11-19).
card_on_port() { for c in /sys/class/sound/card[0-9]*; do case "$(readlink -f "$c/device" 2>/dev/null)" in *"/$1/"*) echo "${c##*card}"; return;; esac; done; }
pa_name() { # $1=sinks|sources  $2=card index -> Name del dispositivo HW de esa card (omite monitores)
  [ -z "$2" ] && return
  pactl list "$1" 2>/dev/null | awk -v w="$2" '
    /^[ \t]*Name:/ { n=$2 }
    /alsa\.card = / { v=$0; gsub(/[^0-9]/,"",v); if(v==w && n!="" && n !~ /\.monitor$/){ print n; exit } }'
}
CARD_HOME=$(card_on_port 1-1.2); CARD_STREET=$(card_on_port 1-1.3)
HS=$(pa_name sinks "$CARD_HOME");   HM=$(pa_name sources "$CARD_HOME")
SS=$(pa_name sinks "$CARD_STREET"); SM=$(pa_name sources "$CARD_STREET")
# Respaldo si no se pudo resolver por puerto: nombres por enumeracion (comportamiento anterior).
: "${HS:=alsa_output.usb-C-Media_Electronics_Inc._USB_Audio_Device-00.analog-stereo}"
: "${HM:=alsa_input.usb-C-Media_Electronics_Inc._USB_Audio_Device-00.mono-fallback}"
: "${SS:=alsa_output.usb-C-Media_Electronics_Inc._USB_Audio_Device-00.analog-stereo.2}"
: "${SM:=alsa_input.usb-C-Media_Electronics_Inc._USB_Audio_Device-00.mono-fallback.2}"

have() { pactl list short sinks 2>/dev/null | grep -q "$1" || pactl list short sources 2>/dev/null | grep -q "$1"; }

load_ec() { # $1=sink_master $2=source_master $3=sink_name $4=source_name
  pactl load-module module-echo-cancel aec_method=webrtc \
    aec_args='"noise_suppression=1 high_pass_filter=1 analog_gain_control=0"' \
    sink_master="$1" source_master="$2" sink_name="$3" source_name="$4" 2>/dev/null \
  || pactl load-module module-echo-cancel aec_method=webrtc \
       sink_master="$1" source_master="$2" sink_name="$3" source_name="$4" 2>/dev/null
}

have home_ec_sink   || load_ec "$HS" "$HM" home_ec_sink   home_ec_source
have street_ec_sink || load_ec "$SS" "$SM" street_ec_sink street_ec_source

# Masters hardware al 100% (y PCM del DAC alto): evita la DOBLE atenuacion (ec_sink x hardware).
# El volumen de usuario se controla SOLO en los _ec_ sinks de abajo.
pactl set-sink-mute "$HS" 0 2>/dev/null; pactl set-sink-volume "$HS" 100% 2>/dev/null
pactl set-sink-mute "$SS" 0 2>/dev/null; pactl set-sink-volume "$SS" 100% 2>/dev/null
pactl set-source-mute "$HM" 0 2>/dev/null; pactl set-source-volume "$HM" 100% 2>/dev/null
pactl set-source-mute "$SM" 0 2>/dev/null; pactl set-source-volume "$SM" 100% 2>/dev/null
amixer -c 0 sset PCM 100% unmute >/dev/null 2>&1; amixer -c 1 sset PCM 100% unmute >/dev/null 2>&1
amixer -c 0 sset Mic 90% cap >/dev/null 2>&1; amixer -c 1 sset Mic 90% cap >/dev/null 2>&1

# Volumenes de usuario (de Ajustes via env; por defecto si no se pasan)
: "${SPK_HOME:=85}"; : "${SPK_STREET:=90}"; : "${MIC_HOME:=95}"; : "${MIC_STREET:=95}"
pactl set-sink-mute   home_ec_sink   0 2>/dev/null; pactl set-sink-volume   home_ec_sink   "${SPK_HOME}%"   2>/dev/null
pactl set-sink-mute   street_ec_sink 0 2>/dev/null; pactl set-sink-volume   street_ec_sink "${SPK_STREET}%" 2>/dev/null
pactl set-source-mute home_ec_source   0 2>/dev/null; pactl set-source-volume home_ec_source   "${MIC_HOME}%"   2>/dev/null
pactl set-source-mute street_ec_source 0 2>/dev/null; pactl set-source-volume street_ec_source "${MIC_STREET}%" 2>/dev/null
echo "AEC devices listos"
