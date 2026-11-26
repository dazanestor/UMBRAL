#!/bin/sh
# Apaga el intercom local: quita SOLO los loopbacks. Los dispositivos AEC (home_ec/street_ec)
# quedan cargados (persistentes) porque baresip los usa para el audio del movil.
export XDG_RUNTIME_DIR=/run/user/1000
# Nombre del modulo (2a columna) exacto, no un grep de subcadena sobre toda la linea: ver
# intercom-on.sh para el motivo.
for m in $(pactl list short modules | awk -F'\t' '$2=="module-loopback"{print $1}'); do pactl unload-module "$m" 2>/dev/null; done
echo "intercom OFF"
