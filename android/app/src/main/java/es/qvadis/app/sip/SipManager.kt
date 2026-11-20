package es.qvadis.app.sip

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
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
 * (el CN del cert es "qvadis-videoportero", no el dominio). Sin PEM, caemos a no verificar
 * el cert (la media sigue cifrada por SRTP).
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
            if (!built) { buildCore(onIncoming, onEnded); built = true }
            else core.refreshRegisters()
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
            runCatching { core.verifyServerCertificates(false) }
            runCatching { core.verifyServerCn(false) }
        }

        // Cuenta SIP (TLS)
        val authInfo = factory.createAuthInfo(acc.ext, null, acc.secret, null, null, acc.sipDomain())
        core.addAuthInfo(authInfo)

        // El resolutor DNS interno de Linphone (belle-sip) no funciona bajo la VPN del móvil
        // (lista de servidores vacía + EPERM) y falla con "io error". Resolvemos el dominio con
        // el resolutor del SISTEMA (que sí funciona, p.ej. por VPN) y le damos a Linphone la IP;
        // el TLS sigue validando por CA anclada (verifyServerCn=false, así que la IP no rompe nada).
        val host = runCatching {
            java.net.InetAddress.getByName(acc.sipDomain()).hostAddress
        }.getOrNull() ?: acc.sipDomain()
        android.util.Log.i("QvadisSip", "server host resuelto: $host (dominio=${acc.sipDomain()})")

        val params = core.createAccountParams()
        params.identityAddress = factory.createAddress("sip:${acc.ext}@${acc.sipDomain()}")
        val server = factory.createAddress("sip:$host:${acc.tlsPort};transport=tls")
        server?.transport = TransportType.Tls
        params.serverAddress = server
        params.isRegisterEnabled = true
        runCatching { params.expires = 3600 }
        val account: LinAccount = core.createAccount(params)
        core.addAccount(account)
        core.defaultAccount = account
        android.util.Log.i("QvadisSip", "buildCore: ext=${acc.ext} id=sip:${acc.ext}@${acc.sipDomain()} server=sip:${acc.sipDomain()}:${acc.tlsPort};transport=tls hasPem=${pem.contains("BEGIN CERTIFICATE")}")

        core.addListener(object : CoreListenerStub() {
            override fun onAccountRegistrationStateChanged(c: Core, a: LinAccount, state: RegistrationState?, message: String) {
                android.util.Log.i("QvadisSip", "registro: state=$state msg=$message")
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
    }

    /** Atiende (audio-only) + manos libres. Si el INVITE aún no ha llegado, se acepta al llegar. */
    fun answer() = h.post {
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

    fun hangup() = h.post { pendingAnswer = false; (core.currentCall ?: core.calls.firstOrNull())?.terminate() }

    fun setMicMuted(m: Boolean) = h.post { core.isMicEnabled = !m }

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
        runCatching { if (built) core.stop() }
        runCatching { worker.quitSafely() }
    }

    /** ¿Es la misma cuenta SIP? (para no reconstruir el Core sin necesidad). */
    fun sameAccount(a: Account): Boolean =
        acc.ext == a.ext && acc.secret == a.secret && acc.sipDomain() == a.sipDomain() && acc.tlsPort == a.tlsPort

    companion object {
        @Volatile private var inst: SipManager? = null
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
