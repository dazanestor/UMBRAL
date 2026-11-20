#!/bin/sh
# Apaga el intercom local: quita SOLO los loopbacks. Los dispositivos AEC (home_ec/street_ec)
# quedan cargados (persistentes) porque baresip los usa para el audio del movil.
export XDG_RUNTIME_DIR=/run/user/1000
for m in $(pactl list short modules | grep module-loopback | cut -f1); do pactl unload-module "$m" 2>/dev/null; done
echo "intercom OFF"
