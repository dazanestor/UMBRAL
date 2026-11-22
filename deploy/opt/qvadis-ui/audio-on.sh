#!/bin/sh
# Tarjeta de la pantalla por PUERTO USB fisico (1-1.2), igual que el manager: la enumeracion
# puede invertir card0/card1. Si no se encuentra, card 0.
HC=0
for d in /sys/class/sound/card[0-9]*; do
  case "$(readlink -f "$d/device" 2>/dev/null)" in */1-1.2/*) HC=${d##*card}; break;; esac
done
for g in 16 22 23 17 27; do pigs w $g 1; done
amixer -c "$HC" sset PCM 85% unmute >/dev/null 2>&1
amixer -c "$HC" sset Mic 85% cap >/dev/null 2>&1
exit 0
