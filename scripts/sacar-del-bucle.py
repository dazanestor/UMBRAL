#!/usr/bin/env python3
"""sacar-del-bucle.py - Se ejecuta EN TU PC (Linux, macOS o Windows). Saca del bucle de reinicios a una
pantalla de FABRICA (Yocto) anulando los crons que la reinician (scripts/anular-crons-stock.sh).
NO instala ni cambia el arranque: solo deja la pantalla estable para lanzar despues liberar.sh con calma.
Es reversible (anular-crons-stock.sh --restaurar en la pantalla).

Como la placa se reinicia cada pocos minutos, REINTENTA hasta pillar una ventana tras el arranque,
ejecuta el anulador y comprueba que /etc/cron.d ya no tiene los crons de reinicio.

Uso:
  python3 sacar-del-bucle.py ssh  IP                 por red (la pantalla tiene que haber cogido IP)
  python3 sacar-del-bucle.py uart [PUERTO]           por la consola serie (CP2102, 115200), sin red
                                                     PUERTO: /dev/ttyUSB0 (Linux), COM3 (Windows)...
                                                     sin PUERTO se busca el CP210x automaticamente
Opciones/variables:
  --clave RUTA / SSH_KEY     clave privada para root@stock (defecto ~/.ssh/id_ed25519, luego service.qvadis)
  --usuario / UART_USER      usuario de la consola serie (defecto root)
  UART_PASS                  su contrasena (si no esta definida, la pregunta; vacia = sin contrasena)
  --espera SEGUNDOS          tiempo maximo de reintentos (defecto 1800)

Requisitos: Python 3.8+. Modo ssh: cliente 'ssh' (Linux/macOS; en Windows 10/11 viene OpenSSH Client).
Modo uart: en Windows y macOS hace falta 'pip install pyserial'; en Linux se usa si esta instalado y,
si no, termios (tu usuario necesita el grupo uucp/dialout o sudo).
"""
import argparse, getpass, os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
ANULADOR = os.path.join(HERE, "anular-crons-stock.sh")
CRONS = "reboot watchdog_gui watchdog_pulse watchdog_server remoteCommand"
FIN_MSG = ("Siguiente paso: liberar.sh en la pantalla (BUNDLE=...). "
           "Revertir: anular-crons-stock.sh --restaurar")


def say(s):
    print("[bucle] " + s, flush=True)


def die(s):
    print("[error] " + s, file=sys.stderr, flush=True)
    sys.exit(1)


def leer_anulador():
    with open(ANULADOR, "rb") as f:
        # en Windows git puede haber convertido a CRLF: bash en la pantalla lo necesita con LF
        return f.read().replace(b"\r\n", b"\n").replace(b"\r", b"")


# ------------------------------------------------------------------ SSH
def modo_ssh(ip, clave, espera):
    if not clave:
        for k in (os.path.expanduser("~/.ssh/id_ed25519"),
                  os.path.join(HERE, "..", "service.qvadis"), "service.qvadis"):
            if os.path.isfile(k):
                clave = k
                break
    if not clave or not os.path.isfile(clave):
        die("no encuentro clave SSH (usa --clave RUTA)")
    data = leer_anulador()
    # El anulador va por stdin (no se copia nada) y se comprueba el resultado en la misma sesion.
    remoto = ("bash -s; rc=$?; for c in %s; do [ -e /etc/cron.d/$c ] && "
              "{ echo QV_FALTA_$c; rc=9; }; done; [ $rc = 0 ] && echo QV_OK_$((6*7)); exit $rc" % CRONS)
    cmd = ["ssh", "-i", clave, "-o", "BatchMode=yes", "-o", "ConnectTimeout=3",
           "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=" + os.devnull,
           "-o", "LogLevel=ERROR", "-o", "IdentitiesOnly=yes", "root@" + ip, remoto]
    fin = time.time() + espera
    n = 0
    say("esperando a que %s responda por SSH (hasta %d s)..." % (ip, espera))
    while time.time() < fin:
        n += 1
        try:
            r = subprocess.run(cmd, input=data, capture_output=True, timeout=120)
        except FileNotFoundError:
            die("no encuentro el cliente 'ssh' (Windows: Configuracion > Aplicaciones > Caracteristicas "
                "opcionales > Cliente OpenSSH)")
        except subprocess.TimeoutExpired:
            r = None
        if r is not None:
            out = (r.stdout + r.stderr).decode("utf-8", "replace").replace("\r", "")
            if r.returncode == 0 and "QV_OK_42" in out:
                print(out, end="" if out.endswith("\n") else "\n")
                say("LISTO: crons de reinicio anulados y comprobados. La pantalla ya no deberia reiniciarse sola.")
                say(FIN_MSG)
                return 0
            if out.strip() and not re.search(r"timed out|refused|unreachable|closed|reset|No route",
                                             out, re.I):
                print("\n".join(out.strip().splitlines()[-3:]))
        if n % 10 == 0:
            say("sigo intentando (intento %d, %d s restantes)" % (n, fin - time.time()))
        time.sleep(2)
    die("no pude entrar/anular en %d s (red caida, clave no valida o la placa no arranca)" % espera)


# ------------------------------------------------------------------ UART
class Puerto:
    """Puerto serie 115200 8N1 sin control de flujo: pyserial si esta; si no, termios (solo POSIX)."""

    def __init__(self, dev):
        self.ser = None
        try:
            try:
                import serial
            except ImportError:
                serial = None
            if serial:
                self.ser = serial.Serial(dev, 115200, timeout=0.2, write_timeout=5)
                return
            if os.name == "nt" or sys.platform == "darwin":
                die("falta pyserial: pip install pyserial")
            import termios
            self.fd = os.open(dev, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
            a = termios.tcgetattr(self.fd)
            a[0] = 0
            a[1] = 0
            a[3] = 0
            a[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
            a[4] = a[5] = termios.B115200
            a[6][termios.VMIN] = 0
            a[6][termios.VTIME] = 0
            termios.tcsetattr(self.fd, termios.TCSANOW, a)
        except Exception as e:  # permisos, puerto ocupado...
            die("no puedo abrir %s: %s" % (dev, e))

    def leer(self, seg):
        if self.ser:
            return self.ser.read(4096) if seg else b""
        import select
        r, _, _ = select.select([self.fd], [], [], seg)
        if r:
            try:
                return os.read(self.fd, 4096)
            except BlockingIOError:
                pass
        return b""

    def escribir(self, b):
        if self.ser:
            self.ser.write(b)
            self.ser.flush()
        else:
            os.write(self.fd, b)


def buscar_cp210x():
    try:
        from serial.tools import list_ports
    except ImportError:
        return None
    for p in list_ports.comports():
        if (p.vid, p.pid) == (0x10C4, 0xEA60):
            return p.device
    return None


class Consola:
    def __init__(self, puerto, usuario, clave):
        self.p, self.usuario, self.clave, self.buf = puerto, usuario, clave, ""

    def bombear(self, seg):
        fin = time.time() + seg
        while time.time() < fin:
            d = self.p.leer(0.2)
            if d:
                self.buf += d.decode("utf-8", "replace")

    def enviar(self, s, pausa=0.0):
        for ln in s.splitlines(True):
            self.p.escribir(ln.encode())
            if pausa:
                self.bombear(pausa)

    def espera(self, pat, t):
        fin = time.time() + t
        while time.time() < fin:
            self.bombear(0.3)
            m = re.search(pat, self.buf)
            if m:
                out, self.buf = self.buf[:m.end()], self.buf[m.end():]
                return out
        return None

    def login(self):
        """Insiste hasta ver 'login:' (consola muda por 'quiet') o un prompt ya abierto."""
        for _ in range(6):
            self.buf = ""
            self.enviar("\r\n")
            if self.espera(r"login:\s*$", 4) is not None:
                self.enviar(self.usuario + "\r\n")
                if self.espera(r"[Pp]assword:\s*$", 4) is not None:
                    self.enviar(self.clave + "\r\n")
                r = self.espera(r"[#$] ?$", 8)
                if r is not None and not re.search(r"incorrect|failed", r, re.I):
                    self.buf = ""
                    return True
                if r is not None:
                    say("login rechazado (usuario/contrasena de la UART?)")
                    return False
            elif re.search(r"[#$] ?$", self.buf):
                self.buf = ""
                return True
        return False

    def intento(self, data):
        if not self.login():
            return False
        self.buf = ""
        self.enviar("stty -echo; cat > /tmp/qv-anular.sh <<'QVEOF'\n")
        self.enviar(data.decode("utf-8", "replace") + "\nQVEOF\n", 0.03)
        self.enviar("echo QV_SZ_$(wc -c < /tmp/qv-anular.sh)_FIN\n")
        r = self.espera(r"QV_SZ_(\d+)_FIN", 15)
        if r is None:
            return False
        sz = int(re.search(r"QV_SZ_(\d+)_FIN", r).group(1))
        if sz not in (len(data), len(data) + 1):  # +1: salto de linea antes de QVEOF
            say("subida corrupta (%d != %d bytes), reintento" % (sz, len(data)))
            return False
        self.buf = ""
        self.enviar("bash /tmp/qv-anular.sh; rc=$?; for c in %s; do [ -e /etc/cron.d/$c ] && "
                    "echo QV_FALTA_$c; done; echo QV_FIN_$((6*7))_$rc\n" % CRONS)
        r = self.espera(r"QV_FIN_42_(\d+)", 60)
        if r is None:
            return False
        print(r.replace("\r", ""), flush=True)
        return "QV_FALTA_" not in r and "QV_FIN_42_0" in r


def modo_uart(dev, usuario, clave, espera):
    dev = dev or buscar_cp210x() or ("/dev/ttyUSB0" if os.name != "nt" else None)
    if not dev:
        die("indica el puerto (p. ej. COM3) o instala pyserial para detectarlo: pip install pyserial")
    data = leer_anulador()
    con = Consola(Puerto(dev), usuario, clave)
    fin = time.time() + espera
    say("esperando el login de la UART en %s (hasta %d s)..." % (dev, espera))
    n = 0
    while time.time() < fin:
        n += 1
        try:
            if con.intento(data):
                say("LISTO: crons de reinicio anulados y comprobados. La pantalla ya no deberia reiniciarse sola.")
                say(FIN_MSG)
                return 0
        except OSError as e:
            say("error de puerto: %s" % e)
        say("intento %d sin exito, reintento..." % n)
        time.sleep(2)
    die("no pude anular en el tiempo dado")


def main():
    ap = argparse.ArgumentParser(description="Saca del bucle de reinicios a una pantalla de fabrica.")
    ap.add_argument("modo", choices=("ssh", "uart"))
    ap.add_argument("destino", nargs="?", help="ssh: IP | uart: puerto serie (opcional)")
    ap.add_argument("--clave", default=os.environ.get("SSH_KEY"))
    ap.add_argument("--usuario", default=os.environ.get("UART_USER", "root"))
    ap.add_argument("--espera", type=int, default=int(os.environ.get("ESPERA", "1800")))
    a = ap.parse_args()
    if not os.path.isfile(ANULADOR):
        die("no encuentro " + ANULADOR)
    if a.modo == "ssh":
        if not a.destino:
            die("uso: sacar-del-bucle.py ssh IP")
        sys.exit(modo_ssh(a.destino, a.clave, a.espera))
    clave = os.environ.get("UART_PASS")
    if clave is None:
        clave = getpass.getpass("Contrasena de '%s' en la consola serie del stock (vacia = sin contrasena): "
                                % a.usuario)
    sys.exit(modo_uart(a.destino, a.usuario, clave, a.espera))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
