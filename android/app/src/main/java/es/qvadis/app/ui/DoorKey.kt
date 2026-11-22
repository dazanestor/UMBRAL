package es.qvadis.app.ui

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricPrompt
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Clave del Keystore que solo se desbloquea tras autenticarse (huella/cara/PIN) en CADA uso.
 * Se liga al BiometricPrompt con un CryptoObject: el desbloqueo lo hace el hardware seguro, así
 * que un callback de éxito falseado (root/Frida) no basta: sin la clave desbloqueada no se abre.
 */
object DoorKey {
    private const val ALIAS = "umbral_door_gate"
    private const val ANDROID_KS = "AndroidKeyStore"

    private fun create(): SecretKey {
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setUserAuthenticationParameters(
                0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            b.setInvalidatedByBiometricEnrollment(true)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KS)
            .apply { init(b.build()) }.generateKey()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KS).apply { load(null) }
        return (ks.getKey(ALIAS, null) as? SecretKey) ?: create()
    }

    private fun newCipher(): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }

    /** CryptoObject listo para el prompt, o null si no se puede (se usa el prompt sin cripto). */
    fun cryptoObject(): BiometricPrompt.CryptoObject? = try {
        BiometricPrompt.CryptoObject(newCipher())
    } catch (e: KeyPermanentlyInvalidatedException) {   // cambió el biométrico: se regenera
        runCatching {
            KeyStore.getInstance(ANDROID_KS).apply { load(null) }.deleteEntry(ALIAS)
            BiometricPrompt.CryptoObject(newCipher())
        }.getOrNull()
    } catch (e: Exception) { null }

    /** Comprueba que el hardware desbloqueó la clave (si no, doFinal lanza excepción). */
    fun verify(c: BiometricPrompt.CryptoObject?): Boolean =
        c?.cipher?.let { runCatching { it.doFinal(ByteArray(1)); true }.getOrDefault(false) } ?: false
}
