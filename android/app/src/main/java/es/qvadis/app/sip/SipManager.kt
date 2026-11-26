package es.qvadis.app.sip

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import es.qvadis.app.model.Account
import es.qvadis.app.store.Prefs
import org.linphone.core.Account as LinAccount
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.MediaEncryption
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.io.File

/**
 * SIP-TLS + SRTP con el SDK de Linphone, contra la placa (PJSIP).
 *
 * TLS: la placa usa cert autofirmado. Si tenemos su PEM (descargado al emparejar por HTTPS
 * con pinning), lo anclamos como CA raíz y validamos de verdad, sin verificar el CN
 * (el CN del cert es "qvadis-videoportero", no el dominio). Sin PEM NO se desactiva la
 * verificación: el registro falla y hay que re-emparejar.
 *
 * Reconexión: al cambiar de red (Wi-Fi/datos/VPN) el socket TLS viejo puede quedar muerto sin
 * que nadie lo avise y el registro se queda en "Refreshing" para siempre. Por eso hay un callback
 * de red y un vigilante del registro que, en esos casos, vuelven a resolver el servidor y fuerzan
 * una conexión nueva.
 *
 * Todo el trabajo del Core corre en su propio hilo con iterate() manual.
 * Ajusta nombres/firmas a la versión del SDK si hiciera falta.
 */
class SipManager private constructor(private val appCtx: Context, private val acc: Account) {

    private val factory: Factory = Factory.instance()
    private val worker = HandlerThread("linphone-core").apply { start() }
    private val h = Handler(worker.looper)
    private lateinit var core: Core
    private var built = false

    // --- reconexión ---
    @Volatile private var regState: RegistrationState? = null
    @Volatile private var regSince = 0L
    @Volatile private var recovering = false
    private var incomingCb: (() -> Unit)? = null
    private var endedCb: (() -> Unit)? = null
    private var lastRebuild = 0L
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var underCb: ConnectivityManager.NetworkCallback? = null
    private var ignoreNetUntil = 0L
    @Volatile private var lastProbe = 0L

    /** La UI (CallActivity) la usa para cerrarse al terminar la llamada. */
    @Volatile var onCallEnded: (() -> Unit)? = null
    /** Se invoca cuando entra una llamada (INVITE recibido): la UI puede habilitar "Atender". */
    @Volatile var onCallIncoming: (() -> Unit)? = null
    /** Se invoca cuando la llamada queda establecida (audio en marcha): la UI pasa a "en llamada". */
    @Volatile var onCallConnected: (() -> Unit)? = null
    /** Opcional: estado del registro (true=OK). */
    @Volatile var onRegistered: ((Boolean) -> Unit)? = null

    /** Si se pulsa "Atender" antes de que llegue el INVITE, se acepta en cuanto llegue. */
    @Volatile private var pendingAnswer = false
    /** El enrutado de audio se aplica una vez por llamada (no pisar la elección del usuario). */
    @Volatile private var audioRouted = false

    private val iterate = object : Runnable {
        override fun run() { if (built) core.iterate(); h.postDelayed(this, 20) }
    }

    fun start(onIncoming: () -> Unit, onEnded: () -> Unit) {
        h.post {
            incomingCb = onIncoming; endedCb = onEnded
            if (!built) {
                buildCore(onIncoming, onEnded); built = true
                watchNetwork()                       // una sola vez (sobrevive a las reconstrucciones)
                h.postDelayed(watchdog, 30_000)
            } else core.refreshRegisters()
        }
    }

    private fun buildCore(onIncoming: () -> Unit, onEnded: () -> Unit) {
        runCatching { factory.enableLogcatLogs(true) }
        runCatching { factory.loggingService.setLogLevel(org.linphone.core.LogLevel.Message) }
        core = factory.createCore(null, null, appCtx)
        runCatching { core.isAutoIterateEnabled = false }

        // El tono de llamada lo pone la notificación de Android (tono elegido por el usuario).
        // Desactivamos el ring propio de Linphone para que no suene otro tono por encima.
        runCatching { core.isNativeRingingEnabled = false }
        runCatching { core.ring = "" }

        // Transporte: solo TLS (puerto cliente efímero)
        runCatching {
            val t = core.transports
            t.tlsPort = -1; t.tcpPort = 0; t.udpPort = 0
            core.transports = t
        }

        // Media cifrada (SRTP/SDES, como la placa)
        core.mediaEncryption = MediaEncryption.SRTP
        runCatching { core.isMediaEncryptionMandatory = false }

        // TLS anclado al cert de la placa (si lo tenemos)
        val pem = Prefs.certPem(appCtx)
        if (pem.contains("BEGIN CERTIFICATE")) {
            val caFile = File(appCtx.filesDir, "qvadis_ca.pem").apply { writeText(pem) }
            runCatching { core.rootCa = caFile.absolutePath }
            runCatching { core.verifyServerCertificates(true) }
            runCatching { core.verifyServerCn(false) }   // CN del cert != dominio
        } else {
            // Sin cert anclado NO se desactiva ninguna verificación (ni CA ni CN): el registro
            // falla (cerrado) en vez de exponer la contraseña SIP a un intermediario con
            // cualquier CA válida para otro dominio. Re-emparejar lo repara.
            runCatching { core.verifyServerCertificates(true) }
        }

        // Cuenta SIP (TLS)
        val authInfo = factory.createAuthInfo(acc.ext, null, acc.secret, null, null, acc.sipDomain())
        core.addAuthInfo(authInfo)

        // El resolutor DNS interno de Linphone (belle-sip) no funciona bajo la VPN del móvil
        // (lista de servidores vacía + EPERM) y falla con "io error". Resolvemos el dominio con
        // el resolutor del SISTEMA (que sí funciona, p.ej. por VPN) y le damos a Linphone la IP;
        // el TLS sigue validando por CA anclada (verifyServerCn=false, así que la IP no rompe nada).
        val host = resolveHost()
        android.util.Log.i("QvadisSip", "server host resuelto")

        val params = core.createAccountParams()
        params.identityAddress = factory.createAddress("sip:${acc.ext}@${acc.sipDomain()}")
        val server = factory.createAddress("sip:$host:${acc.tlsPort};transport=tls")
        server?.transport = TransportType.Tls
        params.serverAddress = server
        params.isRegisterEnabled = true
        runCatching { params.expires = 600 }   // corto: detecta antes un registro muerto tras cambiar de red
        val account: LinAccount = core.createAccount(params)
        core.addAccount(account)
        core.defaultAccount = account
        android.util.Log.i("QvadisSip", "buildCore: hasPem=${pem.contains("BEGIN CERTIFICATE")}")

        core.addListener(object : CoreListenerStub() {
            override fun onAccountRegistrationStateChanged(c: Core, a: LinAccount, state: RegistrationState?, message: String) {
                android.util.Log.i("QvadisSip", "registro: state=$state msg=$message")
                regState = state; regSince = SystemClock.elapsedRealtime()
                when (state) {
                    RegistrationState.Ok -> onRegistered?.invoke(true)
                    RegistrationState.Failed -> onRegistered?.invoke(false)
                    else -> {}
                }
            }
            override fun onCallStateChanged(c: Core, call: Call, state: Call.State?, message: String) {
                android.util.Log.i("QvadisSip", "call state=$state pendingAnswer=$pendingAnswer")
                when (state) {
                    Call.State.IncomingReceived -> {
                        audioRouted = false
                        onIncoming()
                        onCallIncoming?.invoke()
                        if (pendingAnswer) { pendingAnswer = false; doAccept(call) }
                    }
                    Call.State.Connected, Call.State.StreamsRunning -> {
                        if (!audioRouted) { routeAudio(); audioRouted = true }
                        onCallConnected?.invoke()
                    }
                    Call.State.End, Call.State.Released, Call.State.Error -> {
                        pendingAnswer = false; audioRouted = false; onEnded(); onCallEnded?.invoke()
                    }
                    else -> {}
                }
            }
        })

        core.start()
        h.post(iterate)
        regState = null; regSince = SystemClock.elapsedRealtime(); lastProbe = regSince
    }

    /** Resuelve con el resolutor del SISTEMA (el de belle-sip falla bajo VPN). */
    private fun resolveHost(): String =
        runCatching { java.net.InetAddress.getByName(acc.sipDomain()).hostAddress }.getOrNull() ?: acc.sipDomain()

    /**
     * Reconexión radical: PARA y RECONSTRUYE el Core de Linphone. Alternar la alcanzabilidad de red
     * no basta: tras un cambio de red (VPN) pueden sobrevivir canales TLS muertos y Linphone sigue
     * usándolos para contestar (p. ej. al OPTIONS/INVITE de la placa), que nunca recibe la respuesta.
     * Con un Core nuevo no queda ningún canal viejo y se re-resuelve el servidor. No se toca durante
     * una llamada (el vigilante reintenta) y no más de una vez cada 20 s.
     */
    private fun reconnect(why: String) {
        if (!built || recovering) return
        recovering = true
        h.post {
            try {
                val now = SystemClock.elapsedRealtime()
                val inCall = runCatching { core.callsNb > 0 }.getOrDefault(false)
                val inc = incomingCb; val end = endedCb
                if (inCall || inc == null || end == null || now - lastRebuild < 20_000) return@post
                android.util.Log.i("QvadisSip", "reconstruyendo el Core: $why")
                lastRebuild = now
                h.removeCallbacks(iterate)
                built = false
                runCatching { core.stop() }
                buildCore(inc, end)
                built = true
            } finally { recovering = false }
        }
    }

    private val netChanged = Runnable { reconnect("cambio de red") }

    /**
     * Cambios de red. Dos callbacks:
     *  - red por defecto (Wi-Fi <-> datos, o la VPN al conectar/desconectar);
     *  - redes SUBYACENTES sin VPN: con una VPN activa la red por defecto es siempre la misma y
     *    el cambio real (Wi-Fi a datos, nueva IP de salida) solo se ve en estas.
     * Antirrebote de 1,5 s; los avisos iniciales al registrarlos no cuentan como cambio.
     */
    private fun watchNetwork() {
        val cm = appCtx.getSystemService(ConnectivityManager::class.java) ?: return
        ignoreNetUntil = SystemClock.elapsedRealtime() + 3000
        fun changed() {
            if (SystemClock.elapsedRealtime() < ignoreNetUntil) return
            h.removeCallbacks(netChanged); h.postDelayed(netChanged, 1500)
        }
        val def = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
        }
        val under = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = changed()
        }
        runCatching { cm.registerDefaultNetworkCallback(def, h); netCb = def }
        runCatching {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(req, under, h); underCb = under
        }
    }

    /** Si el registro no está en Ok durante más de 60 s (p. ej. atascado en Refreshing), reconecta. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!built) return
            val now = SystemClock.elapsedRealtime()
            if (regState != RegistrationState.Ok && now - regSince > 60_000) {
                reconnect("registro en $regState")
                regSince = now
            } else if (regState == RegistrationState.Ok && now - lastProbe > 180_000) {
                // Sondeo: un REGISTER cada 3 min. Si el socket está muerto no habrá respuesta, el
                // estado saldrá de Ok y la rama de arriba reconectará (detecta en ~4 min, no ~11).
                lastProbe = now
                runCatching { core.refreshRegisters() }
            }
            h.postDelayed(this, 30_000)
        }
    }

    /** Atiende (audio-only) + manos libres. Si el INVITE aún no ha llegado, se acepta al llegar. */
    fun answer() = h.post {
        if (!built) { pendingAnswer = true; return@post }
        val call = core.currentCall ?: core.calls.firstOrNull()
        if (call == null) {
            pendingAnswer = true
            android.util.Log.i("QvadisSip", "answer(): aún no hay llamada -> aceptación diferida")
        } else {
            doAccept(call)
        }
    }

    private fun doAccept(call: Call) {
        try {
            runCatching { core.isMicEnabled = true }
            val p = core.createCallParams(call)
            runCatching { p?.isVideoEnabled = false }
            if (p != null) call.acceptWithParams(p) else call.accept()
            android.util.Log.i("QvadisSip", "doAccept(): aceptada, estado=${call.state}")
            routeAudio(); audioRouted = true
        } catch (e: Throwable) {
            android.util.Log.e("QvadisSip", "doAccept() falló", e)
        }
    }

    fun hangup() = h.post {
        pendingAnswer = false
        if (built) (core.currentCall ?: core.calls.firstOrNull())?.terminate()
    }

    fun setMicMuted(m: Boolean) = h.post { if (built) core.isMicEnabled = !m }

    /** Enruta entrada (micro del teléfono) y salida (altavoz) explícitamente: evita que el micro
     *  quede en un dispositivo equivocado (silencio en subida) o que un HW EC roto lo deje mudo. */
    private fun routeAudio() {
        runCatching { core.isMicEnabled = true }
        runCatching {
            core.audioDevices.firstOrNull { it.type == AudioDevice.Type.Microphone && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord) }
                ?.let { core.inputAudioDevice = it; android.util.Log.i("QvadisSip", "input=${it.deviceName}") }
        }
        runCatching {
            core.audioDevices.firstOrNull { it.type == AudioDevice.Type.Speaker && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay) }
                ?.let { core.outputAudioDevice = it; android.util.Log.i("QvadisSip", "output=${it.deviceName}") }
        }
    }

    /** Enruta el audio al altavoz (manos libres). */
    fun speakerOn() = h.post {
        runCatching {
            core.audioDevices.firstOrNull { it.type == AudioDevice.Type.Speaker }?.let { core.outputAudioDevice = it }
        }
    }

    /** Conmuta la salida de audio: altavoz (manos libres) o auricular. */
    fun setSpeaker(on: Boolean) = h.post {
        runCatching {
            val type = if (on) AudioDevice.Type.Speaker else AudioDevice.Type.Earpiece
            core.audioDevices.firstOrNull { it.type == type }?.let { core.outputAudioDevice = it }
        }
    }

    /** Llamada saliente del móvil a una extensión de la placa (p. ej. la calle). */
    fun call(ext: String) = h.post {
        runCatching {
            val addr = core.interpretUrl("sip:$ext@${acc.sipDomain()}")
            val params = core.createCallParams(null)
            runCatching { params?.isVideoEnabled = false }
            runCatching { core.isMicEnabled = true }
            if (addr != null) { if (params != null) core.inviteAddressWithParams(addr, params) else core.inviteAddress(addr) }
            speakerOn()
        }
    }

    fun stop() = h.post {
        onCallEnded = null; onCallIncoming = null; onCallConnected = null; onRegistered = null
        h.removeCallbacks(watchdog); h.removeCallbacks(netChanged)
        netCb?.let { cb -> runCatching { appCtx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netCb = null
        underCb?.let { cb -> runCatching { appCtx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        underCb = null
        runCatching { if (built) core.stop() }
        runCatching { worker.quitSafely() }
    }

    /** ¿Es la misma cuenta SIP? (para no reconstruir el Core sin necesidad). */
    fun sameAccount(a: Account): Boolean =
        acc.ext == a.ext && acc.secret == a.secret && acc.sipDomain() == a.sipDomain() && acc.tlsPort == a.tlsPort

    companion object {
        @Volatile private var inst: SipManager? = null
        /** Para el SIP y olvida el gestor (al desemparejar: si no, sigue registrado con la cuenta borrada). */
        fun release() { synchronized(this) { inst?.stop(); inst = null } }
        /** Devuelve el gestor; si la cuenta cambió (p. ej. tras re-emparejar), reconstruye con la nueva. */
        fun get(ctx: Context, acc: Account): SipManager {
            val cur = inst
            if (cur != null && cur.sameAccount(acc)) return cur
            return synchronized(this) {
                val c2 = inst
                if (c2 != null && c2.sameAccount(acc)) c2
                else { c2?.stop(); SipManager(ctx.applicationContext, acc).also { inst = it } }
            }
        }
    }
}
