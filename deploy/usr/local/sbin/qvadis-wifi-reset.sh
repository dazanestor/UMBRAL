#!/bin/sh
# Cuerpo de qvadis-wifi-reset.service, en fichero aparte para poder envolverlo ENTERO con flock
# (ver el .service): antes 'flock -n lock true' solo comprobaba el lock un instante y soltaba el
# candado antes de tocar el modulo/supplicant, asi que si qvadis-wifi (scan/connect) tomaba el
# lock mientras este script esperaba los 12s, el modprobe/restart corria igual a mitad de un
# cambio de red en curso: justo la colision que el candado decia evitar.
set -u
for i in $(seq 1 12); do
  /usr/sbin/wpa_cli -i wlan0 status 2>/dev/null | grep -q wpa_state=COMPLETED && exit 0
  sleep 1
done
systemctl stop wpa_supplicant@wlan0
/sbin/modprobe -r rtl8xxxu
sleep 2
/sbin/modprobe rtl8xxxu
sleep 2
systemctl start wpa_supplicant@wlan0
# modprobe -r/+ destruye y recrea el netdev wlan0: qvadis-wifi-powersave.service es oneshot y no
# vuelve a correr solo (ya esta "active (exited)" de antes), asi que el ahorro de energia del
# kernel se reactiva en la interfaz nueva. Lo reaplicamos aqui mismo tras recrearla.
/sbin/iw dev wlan0 set power_save off 2>/dev/null || true
