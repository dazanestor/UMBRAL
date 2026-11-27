#!/bin/bash
# qvadis-apply-verify.sh - Biblioteca SOURCEADA (no se ejecuta sola) por qvadis-wifi, qvadis-net y qvadis-ota.
# Antes el patron "aplicar -> verificar -> si falla, revertir y reaplicar" estaba reimplementado
# a mano en 3 sitios (connect de qvadis-wifi, dhcp y static de qvadis-net): aqui queda en un solo
# lugar para que un futuro cambio en ese patron no haya que repetirlo ni se pueda olvidar en uno
# de los sitios.
#
# Contrato: quien llama ya ha escrito la configuracion NUEVA en "$CONF" y, si habia una anterior,
# la ha copiado a "$CONF.qvbak" ANTES de sobrescribir (ese paso varia poco entre casos como para
# ganar factorizandolo, y asi el candado de "quien escribe que" queda claro en cada script).
#
# Uso:
#   qv_apply_verify_or_revert <fichero_config> <funcion_aplicar> <funcion_verificar>
# funcion_aplicar: sin argumentos, aplica la config ya escrita (reinicia un servicio, recarga...).
# funcion_verificar: sin argumentos, devuelve 0 si la config aplicada funciona; puede imprimir algo
#   por stdout al tener EXITO (p.ej. la IP obtenida), que el llamador captura envolviendo la
#   llamada a qv_apply_verify_or_revert en "$(...)" (ver verify_connect en qvadis-wifi). En el
#   camino de revert esa salida se descarta (se llama con stdout a /dev/null), asi que no hace
#   falta ninguna variable global para sacar el dato.
# Devuelve 0 si verifico bien (y ya borro el .qvbak); 1 si hubo que revertir (y ya esta revertido
# y reaplicado, mejor esfuerzo).
qv_apply_verify_or_revert() {
  local conf="$1" apply_fn="$2" verify_fn="$3"
  "$apply_fn"
  if "$verify_fn"; then rm -f "$conf.qvbak"; return 0; fi
  [ -f "$conf.qvbak" ] && mv -f "$conf.qvbak" "$conf"
  "$apply_fn"
  "$verify_fn" >/dev/null 2>&1
  return 1
}

# Puerta de enlace de la ruta por defecto + ping a ella. Compartido por qvadis-net (verify(),
# limitado a su interfaz) y qvadis-ota (health_ok(), la ruta por defecto del sistema): antes cada
# uno reimplementaba su propio "ip route show default | awk ... && ping".
#   qv_gw_ping_ok [interfaz] [timeout_ping_segundos]
# Sin interfaz, usa la ruta por defecto global (cualquier interfaz).
qv_gw_ping_ok() {
  local ifc="${1:-}" w="${2:-1}" gw
  gw=$(ip -4 route show default ${ifc:+dev "$ifc"} 2>/dev/null | awk '/default/{print $3; exit}')
  [ -n "$gw" ] && ping -c1 -W"$w" "$gw" >/dev/null 2>&1
}
