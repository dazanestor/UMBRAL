package es.qvadis.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import es.qvadis.app.R
import es.qvadis.app.net.ApiClient
import es.qvadis.app.store.AccountStore
import kotlin.concurrent.thread

/**
 * Apertura rápida desde el widget: pide huella/cara (o PIN del móvil como alternativa)
 * y, si autentica, abre la puerta. Actividad translúcida: no muestra UI propia, solo el
 * diálogo biométrico del sistema. Se cierra al terminar.
 */
class QuickOpenActivity : AppCompatActivity() {

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val acc = AccountStore.load(this)
        if (acc == null) {   // sin emparejar: abre la app para escanear el QR
            startActivity(Intent(this, MainActivity::class.java))
            finish(); return
        }
        val api = ApiClient(acc)
        val bm = BiometricManager.from(this)
        val strongOrCred = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

        when {
            bm.canAuthenticate(strongOrCred) == BiometricManager.BIOMETRIC_SUCCESS ->
                authenticate(strongOrCred, false) { openDoor(api) }
            bm.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS ->
                authenticate(BIOMETRIC_STRONG, true) { openDoor(api) }
            else ->   // sin huella ni bloqueo configurado: confirmación simple
                AlertDialog.Builder(this)
                    .setTitle("Abrir puerta")
                    .setMessage("¿Abrir la puerta principal?")
                    .setPositiveButton("Abrir") { _, _ -> openDoor(api) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
        }
    }

    private fun authenticate(authenticators: Int, withNegative: Boolean, onOk: () -> Unit) {
        val exec = androidx.core.content.ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, exec, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON)
                    Toast.makeText(this@QuickOpenActivity, msg, Toast.LENGTH_SHORT).show()
                finish()
            }
            // onAuthenticationFailed: intento no reconocido -> el diálogo sigue abierto
        })
        val b = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Abrir puerta")
            .setSubtitle("Confirma tu identidad para abrir")
            .setAllowedAuthenticators(authenticators)
        if (withNegative) b.setNegativeButtonText("Cancelar")   // obligatorio sin DEVICE_CREDENTIAL
        prompt.authenticate(b.build())
    }

    private fun openDoor(api: ApiClient) {
        thread {
            val ok = runCatching { api.openDoor("front") }.getOrDefault(false)
            runOnUiThread {
                Toast.makeText(this, if (ok) R.string.door_opened else R.string.door_error, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
