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
#   entradas de directorio de nivel superior (./lib/ ...) REEMPLAZO el enlace
#   /lib por un directorio real, dejando inalcanzable el cargador dinamico
#   /lib/ld-linux-armhf.so.3. Sin el, NINGUN binario puede ejecutarse:
#   init no arranca -> kernel panic. No hay rescate por SSH ni por serie
#   (no se puede ejecutar ningun proceso); solo por IO board + rpiboot.
#
# QUE HACE este script:
#   1. Verifica que el punto de montaje es realmente la p3 danada.
#   2. Fusiona el contenido de lib/ real dentro de usr/lib (sin pisar nada).
#   3. Restaura el enlace simbolico lib -> usr/lib.
#   4. Verifica que el cargador dinamico vuelve a ser alcanzable.
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
# Es IDEMPOTENTE: si lib ya es un enlace, no hace nada y sale con exito.
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

# ---- Caso ya reparado: lib es enlace simbolico ----
if [ -L lib ]; then
    echo "lib ya es un enlace simbolico -> $(readlink lib). Nada que reparar."
    echo "Comprobando cargador dinamico..."
    if [ -e usr/lib/ld-linux-armhf.so.3 ] || [ -e lib/ld-linux-armhf.so.3 ]; then
        echo "OK: ld-linux-armhf.so.3 alcanzable. Sistema sano."
        exit 0
    fi
    fail "lib es enlace pero no encuentro ld-linux-armhf.so.3. Revisar manualmente."
fi

# ---- Caso a reparar: lib es un directorio real ----
[ -d lib ] || fail "'$MP/lib' no existe y no es enlace. Estado inesperado. Aborto."

echo "Detectado: lib es un DIRECTORIO real (sintoma del fallo). Reparando..."

# Registro de lo que hay en lib/ antes de tocar (por si hay que auditar)
STAMP="$(date -u +%Y%m%d-%H%M%S)"
LOG="$MP/root/rescate-lib-$STAMP.log"
{
    echo "# Rescate lib p3 - $STAMP UTC"
    echo "# Contenido de $MP/lib antes de fusionar:"
    ls -la lib 2>/dev/null || true
} > "$LOG" 2>/dev/null || LOG="(no se pudo escribir log)"
echo "Log: $LOG"

# Fusionar lib/ real dentro de usr/lib SIN sobrescribir lo existente.
# cp -a preserva permisos/enlaces; -n (no-clobber) evita pisar usr/lib.
# (Tipicamente lib/ solo contendra modules/ y firmware/ anadidos por error,
#  mas quiza algun resto; lo movemos a su sitio real bajo usr/lib.)
if [ -n "$(ls -A lib 2>/dev/null || true)" ]; then
    echo "Fusionando contenido de lib/ -> usr/lib/ (sin sobrescribir)..."
    cp -a -n lib/. usr/lib/ || fail "Fallo al copiar lib/ -> usr/lib/. NO se ha borrado nada. Revisar."
else
    echo "lib/ esta vacio; nada que fusionar."
fi

# Eliminar el directorio lib real y restaurar el enlace simbolico.
echo "Eliminando directorio lib/ real y restaurando enlace lib -> usr/lib..."
rm -rf lib
ln -s usr/lib lib

# ---- Verificacion final ----
[ -L lib ] || fail "Tras reparar, lib NO es un enlace. Algo fue mal."
if [ -e lib/ld-linux-armhf.so.3 ]; then
    echo
    echo "=========================================================="
    echo " OK: reparado. lib -> $(readlink lib)"
    echo " ld-linux-armhf.so.3 alcanzable. El sistema deberia arrancar."
    echo "=========================================================="
    echo
    echo "Ahora:  sudo umount $MP ; sync"
    echo "Luego:  JP4 -> BOOT DIS y arranca normal."
    exit 0
fi

fail "lib restaurado como enlace pero no encuentro lib/ld-linux-armhf.so.3. Revisar usr/lib manualmente."
