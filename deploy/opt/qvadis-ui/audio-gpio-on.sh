#!/bin/sh
# audio-gpio-on.sh - habilita por GPIO el front-end analogico de audio (amp/mic). SOURCEADO por
# audio-on.sh y audio-aec-up.sh. Los tres viven en /opt para llegar juntos por OTA.
# El mic-test de manager.js (api/mic/test) enciende los mismos pines desde JS.
for g in 16 22 23 17 27; do pigs w "$g" 1 2>/dev/null; done
