package es.qvadis.app.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import es.qvadis.app.R
import es.qvadis.app.model.Account
import es.qvadis.app.net.ApiClient
import es.qvadis.app.push.Notifications
import es.qvadis.app.sip.SipManager
import es.qvadis.app.store.AccountStore
import es.qvadis.app.store.Prefs
import kotlin.concurrent.thread

/**
 * Llamada de la puerta a pantalla completa (sobre el bloqueo). Vídeo si hay cámara;
 * ABRIR destacado (deslizar), y Atender/Rechazar. Manos libres automático al atender.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var acc: Account
    private lateinit var api: ApiClient
    private var sip: SipManager? = null
    private lateinit var camera: MjpegView
    private var muted = false
    private var speaker = true
    private var ringPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var authInProgress = false
    private var callEnded = false

    companion object {
        /** La usa Notifications.showIncoming para no duplicar la notificación si ya hay pantalla de llamada. */
        @Volatile var isActive = false
        /** Instancia duena de los avisos de SipManager (solo ella puede quitarlos). */
        private var owner: java.lang.ref.WeakReference<CallActivity>? = null
    }

    override fun onStart() { super.onStart(); isActive = true; Notifications.cancelIncoming(this) }
    override fun onStop() { super.onStop(); isActive = false }
    // desde que empieza a cerrarse ya no cuenta como pantalla de llamada (onStop llega mas tarde)
    override fun finish() { isActive = false; super.finish() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val a = AccountStore.load(this)
        if (a == null) { finish(); return }
        acc = a; api = ApiClient(acc)
        Notifications.cancelIncoming(this)   // ya estamos en la llamada
        setContentView(R.layout.activity_call)

        camera = findViewById(R.id.call_camera)

        val groupIncoming = findViewById<View>(R.id.group_incoming)
        val groupActive = findViewById<View>(R.id.group_active)
        val btnMute = findViewById<ImageButton>(R.id.btn_mute)
        val btnSpeaker = findViewById<ImageButton>(R.id.btn_speaker)

        owner = java.lang.ref.WeakReference(this)
        sip = SipManager.get(this, acc).also { s ->
            // con la huella/PIN en curso no se cierra todavia: la puerta debe poder abrirse (ver authDone)
            s.onCallEnded = { runOnUiThread { stopRinging(); callEnded = true; if (!authInProgress) finish() } }
            s.onCallConnected = {                       // audio establecido -> pasar a "en llamada"
                runOnUiThread {
                    stopRinging()
                    groupIncoming.visibility = View.GONE
                    groupActive.visibility = View.VISIBLE
                }
            }
        }

        // Otra llamada mientras la pantalla sigue viva esperando la huella/PIN de la anterior: no habria
        // notificacion (isActive), asi que esta misma pantalla vuelve a "entrante" y suena.
        sip?.onCallIncoming = {
            runOnUiThread {
                if (isFinishing) {
                    // entra justo mientras se cierra la pantalla de la anterior: que avise la notificacion
                    Notifications.showIncoming(applicationContext)
                } else if (callEnded) {
                    callEnded = false
                    muted = false; btnMute.setImageResource(R.drawable.ic_mic)
                    speaker = true; btnSpeaker.setImageResource(R.drawable.ic_volume_up)
                    groupActive.visibility = View.GONE
                    groupIncoming.visibility = View.VISIBLE
                    startRinging()
                }
            }
        }

        findViewById<ImageButton>(R.id.btn_answer).setOnClickListener {
            stopRinging()
            sip?.answer()                              // acepta + altavoz (manos libres)
            thread { runCatching { api.ackCall() } }   // avisa a la placa (no es perdida)
        }
        findViewById<ImageButton>(R.id.btn_reject).setOnClickListener {
            stopRinging(); sip?.hangup(); finish()
        }
        findViewById<ImageButton>(R.id.btn_hangup).setOnClickListener {
            stopRinging(); sip?.hangup(); finish()
        }
        btnMute.setOnClickListener {
            muted = !muted
            sip?.setMicMuted(muted)
            btnMute.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        }
        btnSpeaker.setOnClickListener {
            speaker = !speaker
            sip?.setSpeaker(speaker)
            btnSpeaker.setImageResource(if (speaker) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
        }
        findViewById<SlideToOpen>(R.id.call_slide_open).onOpen = { openDoor() }

        startRinging()   // tono (elegido en Ajustes) en bucle + vibración mientras suena

        // Mostrar el stream de la cámara YA, desde que suena (antes de atender/colgar), sin esperar
        // a la comprobación. Si la placa confirma que no hay cámara, se oculta.
        camera.visibility = View.VISIBLE
        runCatching { camera.start(api.cameraStreamUrl(), api.http) }
        thread {
            val hasCam = runCatching { api.hasCamera() }.getOrDefault(true)
            if (!hasCam) runOnUiThread { camera.stop(); camera.visibility = View.GONE }
        }
    }

    private fun openDoor() {
        if (authInProgress) return   // el deslizador se rearma solo: una apertura cada vez
        // Deslizar ya cuenta como llamada atendida, aunque luego se cancele la huella/PIN
        thread { runCatching { api.ackCall() } }
        authInProgress = true
        openDoorWithAuth(api, after = ::authDone, onCancel = ::authDone)
    }

    private fun authDone() {
        authInProgress = false
        if (callEnded) finish()
    }

    /** Tono de llamada (el elegido en Ajustes) en bucle + vibración, como una app de teléfono. */
    private fun startRinging() {
        runCatching {
            val uri = runCatching { Uri.parse(Prefs.ringtoneUri(this)) }.getOrNull()
                ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ringPlayer = MediaPlayer().apply {
                setDataSource(this@CallActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                isLooping = true
                prepare(); start()
            }
        }
        runCatching {
            vibrator = getSystemService(Vibrator::class.java)
            val pattern = longArrayOf(0, 700, 900)
            if (Build.VERSION.SDK_INT >= 26)
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            else @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopRinging() {
        runCatching { ringPlayer?.stop() }
        runCatching { ringPlayer?.release() }
        ringPlayer = null
        runCatching { vibrator?.cancel() }
    }

    override fun onDestroy() {
        stopRinging()
        if (::camera.isInitialized) camera.stop()   // onCreate pudo salir antes de inicializarla
        // una pantalla antigua que se cierra tarde no debe quitar los avisos de la nueva
        if (owner?.get() === this) {
            owner = null
            sip?.onCallEnded = null
            sip?.onCallConnected = null
            sip?.onCallIncoming = null
        }
        super.onDestroy()
    }
}
