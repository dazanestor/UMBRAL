package es.qvadis.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import es.qvadis.app.net.ApiClient
import es.qvadis.app.service.SipService
import es.qvadis.app.store.AccountStore
import kotlin.concurrent.thread

/**
 * Push FCM: la placa envía un push cuando suena el timbre (data.type=door).
 * Despierta la app -> registra el SIP -> cuando llega el INVITE, abre la llamada.
 * También registra el token FCM nuevo en la placa.
 */
class QvadisFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val acc = AccountStore.load(this) ?: return
        thread { runCatching { ApiClient(acc).registerFcm(token) } }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val acc = AccountStore.load(this) ?: return
        when (msg.data["type"]) {
            "door" -> {
                // Notificación a pantalla completa (fiable con app dormida / pantalla bloqueada).
                Notifications.showIncoming(this)
                // Respaldo: si el sistema hubiera matado el servicio, relánzalo para registrar y
                // recibir el INVITE (el push concede permiso temporal para arrancar un FGS).
                runCatching { SipService.start(this) }
            }
            "missed" -> {
                Notifications.cancelIncoming(this)
                Notifications.showMissed(this, msg.data["photo"])
            }
        }
    }
}
