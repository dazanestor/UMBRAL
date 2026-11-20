#!/bin/sh
for g in 16 22 23 17 27; do pigs w $g 1; done
amixer -c 0 sset PCM 85% unmute >/dev/null 2>&1
amixer -c 0 sset Mic 85% cap >/dev/null 2>&1
exit 0
