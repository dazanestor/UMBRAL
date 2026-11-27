#!/bin/sh
# Intercom local CALLE<->PANTALLA: carga SOLO los loopbacks cruzados sobre los dispositivos
# AEC persistentes (home_ec/street_ec, creados por audio-aec-up.sh). Asi convive con baresip,
# que usa street_ec para el audio del movil (PulseAudio mezcla varios streams).
export XDG_RUNTIME_DIR=/run/user/1000

# Asegura los dispositivos AEC (idempotente) con los volumenes actuales
SPK_HOME="${SPK_HOME:-80}" SPK_STREET="${SPK_STREET:-70}" MIC_HOME="${MIC_HOME:-90}" MIC_STREET="${MIC_STREET:-90}" \
  sh /opt/qvadis-ui/audio-aec-up.sh

# Quita loopbacks previos (no toca los echo-cancel, que son persistentes). Se compara el NOMBRE
# del modulo (2a columna) exacto, no un grep de subcadena sobre toda la linea: con grep, un modulo
# futuro cuyo nombre o argumentos contuvieran "loopback" se desengancharia tambien por error.
for m in $(pactl list short modules | awk -F'\t' '$2=="module-loopback"{print $1}'); do pactl unload-module "$m" 2>/dev/null; done

# STREET mic -> HOME altavoz  y  HOME mic -> STREET altavoz
pactl load-module module-loopback source=street_ec_source sink=home_ec_sink latency_msec=10
pactl load-module module-loopback source=home_ec_source sink=street_ec_sink latency_msec=10
echo "intercom ON"
