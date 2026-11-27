package es.qvadis.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import es.qvadis.app.net.ApiClient
import es.qvadis.app.store.AccountStore

/**
 * Apertura rápida desde el widget: pide huella/cara (o PIN del móvil como alternativa, ver
 * DoorAuth.kt) y, si autentica, abre la puerta. Actividad translúcida: no muestra UI propia,
 * solo el diálogo biométrico del sistema. Se cierra al terminar (éxito, cancelación o fallo).
 */
class QuickOpenActivity : AppCompatActivity() {

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val acc = AccountStore.load(this)
        if (acc == null) {   // sin emparejar: abre la app para escanear el QR
            startActivity(Intent(this, MainActivity::class.java))
            finish(); return
        }
        openDoorWithAuth(ApiClient(acc), after = ::finish, onCancel = ::finish)
    }
}
