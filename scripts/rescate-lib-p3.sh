#!/bin/sh
# ============================================================================
# rescate-lib-p3.sh
# ----------------------------------------------------------------------------
# Repara el sistema Debian de la particion p3 (eMMC del CM3+) cuando el
# arranque falla con "Kernel panic - not syncing: No working init found".
#
# CAUSA del fallo:
#   En Debian con merged-usr, /lib /bin /sbin son ENLACES SIMBOLICOS a
#   usr/lib, usr/bin, usr/sbin. Un `tar xzf ... -C /` cuyo tarball incluia
#   entradas de directorio de nivel superior (./lib/ ./bin/ ./sbin/ ...)
#   REEMPLAZO esos enlaces por directorios reales, dejando inalcanzable el
#   cargador dinamico /lib/ld-linux-armhf.so.3 y/o los binarios de usr/bin,
#   usr/sbin. Sin ellos, NINGUN binario puede ejecutarse: init no arranca ->
#   kernel panic. No hay rescate por SSH ni por serie (no se puede ejecutar
#   ningun proceso); solo por IO board + rpiboot.
#
# QUE HACE este script (para lib, bin Y sbin, los tres igual de afectados):
#   1. Verifica que el punto de montaje es realmente la p3 danada.
#   2. Fusiona el contenido real de cada uno dentro de su usr/<nombre> (sin pisar nada).
#   3. Restaura el enlace simbolico <nombre> -> usr/<nombre>.
#   4. Verifica que el cargador dinamico y los binarios vuelven a ser alcanzables.
#
# COMO USARLO (con la eMMC accesible via rpiboot, p3 montada):
#   1. Pon JP4 en BOOT EN, conecta el CM3+ por USB, lanza `rpiboot`.
#   2. Aparecera la eMMC como /dev/sdX. Monta la p3 (3a particion):
#        sudo mkdir -p /mnt/p3
#        sudo mount /dev/sdX3 /mnt/p3
#   3. Ejecuta:
#        sudo sh rescate-lib-p3.sh /mnt/p3
#   4. Si todo OK:  sudo umount /mnt/p3 ; sync
#      Vuelve JP4 a BOOT DIS y arranca normal.
#
# Es IDEMPOTENTE: lo que ya sea un enlace se deja tal cual.
# ============================================================================

set -eu

MP="${1:-}"

fail() { echo "ERROR: $*" >&2; exit 1; }

[ -n "$MP" ] || fail "Uso: sudo sh rescate-lib-p3.sh <punto-de-montaje-p3>  (ej: /mnt/p3)"
[ -d "$MP" ] || fail "'$MP' no existe o no es un directorio."
[ "$(id -u)" = "0" ] || fail "Ejecutalo como root (sudo)."

cd "$MP" || fail "No puedo entrar en '$MP'."

# ---- Verificaciones de seguridad: confirmar que es la p3 Debian correcta ----
[ -d usr/lib ] || fail "No existe '$MP/usr/lib'. Esto NO parece la p3 Debian. Aborto."

# Marcador de que es Debian (no la p2 Yocto, que NO es merged-usr)
if [ ! -e etc/debian_version ]; then
    fail "No existe '$MP/etc/debian_version'. Punto de montaje equivocado (ojo: p2 es Yocto). Aborto."
fi

echo "== Objetivo: $MP (Debian $(cat etc/debian_version 2>/dev/null || echo '?')) =="

STAMP="$(date -u +%Y%m%d-%H%M%S)"
tocados=0   # cuantos de lib/bin/sbin hubo que reparar realmente

# Repara uno de lib/bin/sbin. Deja en $REP_OK=1 si al salir es un enlace sano.
repara() {
    nombre="$1"
    REP_OK=0
    if [ -L "$nombre" ]; then
        echo "$nombre ya es un enlace simbolico -> $(readlink "$nombre"). Nada que reparar."
        REP_OK=1
        return 0
    fi
    [ -d "$nombre" ] || { echo "AVISO: '$MP/$nombre' no existe y no es enlace (estado inesperado); lo dejo."; return 0; }

    echo "Detectado: $nombre es un DIRECTORIO real (sintoma del fallo). Reparando..."
    tocados=$((tocados + 1))

    LOG="$MP/root/rescate-$nombre-$STAMP.log"
    {
        echo "# Rescate $nombre p3 - $STAMP UTC"
        echo "# Contenido de $MP/$nombre antes de fusionar:"
        ls -la "$nombre" 2>/dev/null || true
    } > "$LOG" 2>/dev/null || LOG="(no se pudo escribir log)"
    echo "Log: $LOG"

    # Fusionar <nombre>/ real dentro de usr/<nombre> SIN sobrescribir lo existente.
    # cp -a preserva permisos/enlaces; -n (no-clobber) evita pisar usr/<nombre>.
    if [ -n "$(ls -A "$nombre" 2>/dev/null || true)" ]; then
        echo "Fusionando contenido de $nombre/ -> usr/$nombre/ (sin sobrescribir)..."
        cp -a -n "$nombre"/. "usr/$nombre/" || fail "Fallo al copiar $nombre/ -> usr/$nombre/. NO se ha borrado nada. Revisar."
    else
        echo "$nombre/ esta vacio; nada que fusionar."
    fi

    echo "Eliminando directorio $nombre/ real y restaurando enlace $nombre -> usr/$nombre..."
    rm -rf "$nombre"
    ln -s "usr/$nombre" "$nombre"

    [ -L "$nombre" ] || fail "Tras reparar, $nombre NO es un enlace. Algo fue mal."
    REP_OK=1
}

for n in lib bin sbin; do
    repara "$n"
    eval "OK_$n=\$REP_OK"
done

# ---- Verificacion final: el cargador dinamico Y un binario de cada uno deben ser alcanzables ----
fallo_verif=0
if [ -e lib/ld-linux-armhf.so.3 ]; then
    echo "OK: ld-linux-armhf.so.3 alcanzable."
else
    echo "ERROR: no encuentro lib/ld-linux-armhf.so.3 tras reparar." >&2
    fallo_verif=1
fi
for n in bin sbin; do
    if [ -n "$(find -H "$n" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]; then
        echo "OK: $n/ alcanzable y no vacio."
    else
        echo "ERROR: $n/ esta vacio o es inalcanzable tras reparar." >&2
        fallo_verif=1
    fi
done

[ "$fallo_verif" = 0 ] || fail "La reparacion de lib/bin/sbin quedo incompleta (ver errores arriba). Revisar manualmente."

echo
echo "=========================================================="
if [ "$tocados" = 0 ]; then
    echo " lib, bin y sbin ya eran enlaces. Nada que reparar; sistema sano."
else
    echo " OK: reparado(s) $tocados de lib/bin/sbin. El sistema deberia arrancar."
fi
echo "=========================================================="
echo
echo "Ahora:  sudo umount $MP ; sync"
echo "Luego:  JP4 -> BOOT DIS y arranca normal."
