package es.qvadis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import es.qvadis.app.R
import es.qvadis.app.push.Notifications
import es.qvadis.app.sip.SipManager
import es.qvadis.app.store.AccountStore
import es.qvadis.app.ui.MainActivity

/**
 * Servicio en primer plano que mantiene el videoportero SIEMPRE registrado en la placa,
 * para recibir la llamada al instante (como un videoportero real) aunque la app esté en
 * segundo plano. Muestra una notificación persistente (obligatoria para un foreground service).
 * El push FCM queda de respaldo por si el sistema llega a matar el proceso.
 */
class SipService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private var foregroundOk = false

    override fun onCreate() {
        super.onCreate()
        // En Android 12+ arrancar un FGS desde segundo plano (p. ej. BOOT_COMPLETED) puede estar
        // prohibido y lanzar ForegroundServiceStartNotAllowedException: no crasheamos; el servicio
        // se arrancará luego desde la app (primer plano) o por el push (exención temporal).
        foregroundOk = startForegroundCompat()
        if (!foregroundOk) { stopSelf(); return }
        ensureSip()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundOk) { foregroundOk = startForegroundCompat() }
        if (!foregroundOk) { stopSelf(); return START_NOT_STICKY }
        ensureSip()
        return START_STICKY   // que el sistema lo reinicie si lo mata
    }

    private fun ensureSip() {
        val acc = AccountStore.load(this)
        if (acc == null) { stopSelf(); return }
        SipManager.get(this, acc).start(
            onIncoming = { Notifications.showIncoming(this) },   // full-screen intent -> CallActivity
            onEnded = { Notifications.cancelIncoming(this) }
        )
    }

    private fun startForegroundCompat(): Boolean = runCatching {
        val n = buildNotification()
        when {
            Build.VERSION.SDK_INT >= 34 ->
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ->
                startForeground(NOTIF_ID, n)
        }
    }.isSuccess

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CH) == null) {
                val c = NotificationChannel(CH, "Servicio UMBRAL", NotificationManager.IMPORTANCE_LOW)
                c.description = "Mantiene el videoportero conectado para recibir llamadas"
                c.setShowBadge(false)
                mgr.createNotificationChannel(c)
            }
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH)
            .setContentTitle("UMBRAL activo")
            .setContentText("Recibiendo llamadas de la puerta")
            .setSmallIcon(R.drawable.ic_door_open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val NOTIF_ID = 7001
        private const val CH = "umbral_service"
        /** Arranca el servicio en primer plano (desde la app, el push o el arranque del móvil). */
        fun start(ctx: Context) {
            val i = Intent(ctx, SipService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
