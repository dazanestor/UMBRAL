package es.qvadis.app.ui

import android.app.KeyguardManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import es.qvadis.app.R
import es.qvadis.app.net.ApiClient
import kotlin.concurrent.thread

/**
 * Pide huella/cara (o PIN/patron del movil) antes de abrir la puerta, en CUALQUIER pantalla de la
 * app. Antes esto solo lo exigia el widget (QuickOpenActivity); el deslizar de MainActivity y
 * CallActivity abria sin ninguna comprobacion, asi que el gate del widget solo movia el hueco un
 * paso (a la app), no lo cerraba de verdad.
 *
 * @param after se ejecuta en el hilo de UI tras el intento de apertura, con exito o sin el (p.ej. finish() en QuickOpenActivity).
 * @param onCancel se ejecuta si el usuario cancela/falla la verificacion o no hay nada que
 *                 verificar y decide no abrir. Por defecto no hace nada (la pantalla se queda
 *                 como estaba); QuickOpenActivity pasa finish() porque es translucida y debe
 *                 cerrarse siempre.
 */
fun AppCompatActivity.openDoorWithAuth(
    api: ApiClient, after: () -> Unit = {}, onCancel: () -> Unit = {}
) {
    val bm = BiometricManager.from(this)
    val strongOrCred = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    // La respuesta (dialogo o biometria) llega de forma asincrona: si la Activity ya se esta cerrando
    // (p.ej. colgaron con el dialogo abierto en CallActivity) no se actua.
    val onOk: () -> Unit = { if (alive()) openFrontDoor(api, after) }
    val cancel: () -> Unit = { if (alive()) onCancel() }
    fun can(a: Int) = bm.canAuthenticate(a) == BiometricManager.BIOMETRIC_SUCCESS
    val weakOrCred = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    // Con CryptoObject: API 30+ admite biometria fuerte o PIN; antes solo biometria fuerte.
    val api30 = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
    when {
        api30 && can(strongOrCred) ->
            doorAuthPrompt(strongOrCred, DoorKey.cryptoObject(), onOk, cancel)
        can(BIOMETRIC_STRONG) -> {
            // si la huella falla o esta bloqueada, se puede pasar al PIN del movil
            val usePin: (() -> Unit)? =
                if (can(weakOrCred)) ({ doorAuthPrompt(weakOrCred, null, onOk, cancel) }) else null
            doorAuthPrompt(BIOMETRIC_STRONG, DoorKey.cryptoObject(), onOk, cancel, usePin)
        }
        // PIN/patron/contraseña (o biometria debil), sin CryptoObject a proposito: androidx.biometric no
        // lo admite con DEVICE_CREDENTIAL antes de API 30. Tiene que ser WEAK|CREDENTIAL: en API 28-29
        // STRONG|CREDENTIAL no esta soportado y un movil con PIN caeria en el dialogo sin verificacion.
        can(weakOrCred) ->
            doorAuthPrompt(weakOrCred, null, onOk, cancel)
        // Movil con bloqueo pero sin forma de verificarlo aqui: no se abre.
        getSystemService(KeyguardManager::class.java)?.isDeviceSecure != false -> {
            Toast.makeText(this, "No se puede verificar tu identidad en este móvil", Toast.LENGTH_LONG).show()
            cancel()
        }
        else -> {
            // Sin huella NI bloqueo de pantalla configurado en el movil: no hay nada que
            // verificar en ningun sitio de la app. Confirmacion simple como ultimo recurso.
            AlertDialog.Builder(this)
                .setTitle(R.string.widget_open_door)
                .setMessage("¿Abrir la puerta principal?")
                .setPositiveButton("Abrir") { _, _ -> onOk() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> cancel() }
                .setOnCancelListener { cancel() }
                .show()
        }
    }
}

private fun AppCompatActivity.alive() = !isFinishing && !isDestroyed

private fun AppCompatActivity.doorAuthPrompt(
    authenticators: Int,
    crypto: BiometricPrompt.CryptoObject?, onOk: () -> Unit, onCancel: () -> Unit,
    usePin: (() -> Unit)? = null
) {
    val exec = androidx.core.content.ContextCompat.getMainExecutor(this)
    val prompt = BiometricPrompt(this, exec, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            // aqui ademas evita verificar o mostrar avisos sobre una Activity que se cierra
            if (!alive()) return
            // con CryptoObject, el éxito solo vale si el Keystore desbloqueó la clave
            if (crypto != null && !DoorKey.verify(result.cryptoObject)) {
                Toast.makeText(this@doorAuthPrompt, R.string.door_error, Toast.LENGTH_SHORT).show()
                onCancel(); return
            }
            onOk()
        }
        override fun onAuthenticationError(code: Int, msg: CharSequence) {
            if (!alive()) return
            if (usePin != null && (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    code == BiometricPrompt.ERROR_LOCKOUT || code == BiometricPrompt.ERROR_LOCKOUT_PERMANENT)) {
                window.decorView.post { if (alive()) usePin() }   // cuando este dialogo ya se haya cerrado
                return
            }
            if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON)
                Toast.makeText(this@doorAuthPrompt, msg, Toast.LENGTH_SHORT).show()
            onCancel()
        }
        // onAuthenticationFailed: intento no reconocido -> el diálogo sigue abierto
    })
    val b = BiometricPrompt.PromptInfo.Builder()
        .setTitle(getString(R.string.widget_open_door))
        .setSubtitle("Confirma tu identidad para abrir")
        .setAllowedAuthenticators(authenticators)
    if (authenticators and DEVICE_CREDENTIAL == 0) b.setNegativeButtonText(if (usePin != null) "Usar PIN" else "Cancelar")   // obligatorio sin DEVICE_CREDENTIAL
    if (crypto != null) prompt.authenticate(b.build(), crypto) else prompt.authenticate(b.build())
}

/** Llamada de red + aviso del resultado. Privada: solo se abre pasando por openDoorWithAuth. */
private fun AppCompatActivity.openFrontDoor(api: ApiClient, after: () -> Unit) {
    thread {
        val ok = runCatching { api.openDoor("front") }.getOrDefault(false)
        runOnUiThread {
            Toast.makeText(this, if (ok) R.string.door_opened else R.string.door_error, Toast.LENGTH_SHORT).show()
            after()
        }
    }
}
