package es.qvadis.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import es.qvadis.app.store.AccountStore

/** Al arrancar el móvil, relanza el servicio para quedar registrado sin abrir la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && AccountStore.load(ctx) != null) {
            runCatching { SipService.start(ctx) }   // en algunos Android el FGS no puede arrancar en boot
        }
    }
}
