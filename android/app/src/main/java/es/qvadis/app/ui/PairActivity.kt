package es.qvadis.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import es.qvadis.app.model.Account
import es.qvadis.app.net.ApiClient
import es.qvadis.app.service.SipService
import es.qvadis.app.store.AccountStore
import es.qvadis.app.store.Prefs
import kotlin.concurrent.thread

/** Escanea el QR qvadis://pair del panel, guarda la cuenta y registra el token FCM. */
class PairActivity : AppCompatActivity() {

    private val scan = registerForActivityResult(ScanContract()) { res ->
        val txt = res.contents
        if (txt == null) { finish(); return@registerForActivityResult }
        val acc = Account.fromQr(txt)
        if (acc == null) { toast("QR no válido"); finish(); return@registerForActivityResult }
        if (!AccountStore.save(this, acc)) { toast("No se pudo guardar el emparejamiento"); finish(); return@registerForActivityResult }
        thread {
            val api = ApiClient(acc)
            // 1) descarga el cert público (HTTPS con pinning) para anclar TLS en Linphone (antes del SIP)
            val pem = runCatching { api.certPem() }.getOrNull()?.takeIf { it.contains("BEGIN CERTIFICATE") }
            if (pem == null) {
                AccountStore.clear(this); Prefs.setCertPem(this, null)
                runOnUiThread { toast("No se pudo contactar con la placa. Vuelve a escanear el QR") }
                return@thread
            }
            Prefs.setCertPem(this, pem)
            // 2) (re)arranca el SIP con la NUEVA cuenta (el gestor se reconstruye al cambiar de cuenta)
            runCatching { SipService.start(this) }
            // 3) registra el token FCM esperando al token (fiable; el callback asíncrono se perdía)
            runCatching { val t = Tasks.await(FirebaseMessaging.getInstance().token); api.registerFcm(t) }
        }
        toast("Emparejando…")
        finish()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        scan.launch(ScanOptions()
            .setPrompt("Escanea el QR del panel")
            .setBeepEnabled(false)
            .setOrientationLocked(true)
            .setCaptureActivity(PortraitCaptureActivity::class.java))
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
