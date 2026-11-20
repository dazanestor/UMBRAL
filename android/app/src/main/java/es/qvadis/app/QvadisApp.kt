package es.qvadis.app

import android.app.Application
import es.qvadis.app.push.Notifications

/** Application: crea los canales de notificación al arrancar. Firebase se inicializa solo. */
class QvadisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }
}
