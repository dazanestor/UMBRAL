package es.qvadis.app.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import es.qvadis.app.R
import es.qvadis.app.net.ApiClient
import es.qvadis.app.store.AccountStore
import es.qvadis.app.store.Prefs
import es.qvadis.app.ui.CallActivity
import okhttp3.Request
import kotlin.concurrent.thread

/**
 * Notificaciones: llamada entrante a pantalla completa (fullScreenIntent, como teléfono)
 * y llamada perdida con captura de la cámara.
 */
object Notifications {

    const val CH_INCOMING = "umbral_incoming"
    const val CH_MISSED = "umbral_missed"
    /** Respaldo con sonido+vibración: se usa si Android no deja abrir la pantalla de llamada (permiso de pantalla completa denegado). */
    const val CH_INCOMING_LOUD = "umbral_incoming_loud"
    const val ID_INCOMING = 1001
    const val ID_MISSED = 1002
    private val missedSeq = java.util.concurrent.atomic.AtomicInteger(0)

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)

        // Entrante: alta prioridad, con el tono elegido (del sistema).
        val incoming = NotificationChannel(CH_INCOMING, "Llamada a la puerta", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Aviso cuando llaman a la puerta"
            // Sin sonido/vibración en el canal: el tono y la vibración los pone la pantalla de llamada
            // (CallActivity) en bucle, como una app de teléfono. Evita duplicar el sonido.
            enableVibration(false)
            setSound(null, null)
        }
        val missed = NotificationChannel(CH_MISSED, "Llamadas perdidas", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Resumen de llamadas no atendidas"
        }
        val loud = NotificationChannel(CH_INCOMING_LOUD, "Llamada a la puerta (con sonido)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Aviso con sonido cuando no se puede abrir la pantalla de llamada"
            enableVibration(true); vibrationPattern = longArrayOf(0, 700, 900, 700, 900)
            val uri = runCatching { android.net.Uri.parse(es.qvadis.app.store.Prefs.ringtoneUri(ctx)) }.getOrNull()
            if (uri != null) setSound(uri, android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        }
        nm.createNotificationChannel(incoming)
        nm.createNotificationChannel(loud)
        nm.createNotificationChannel(missed)
    }

    /** Recrea el canal entrante para aplicar un tono nuevo (los canales fijan el sonido al crearse). */
    fun applyRingtone(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(CH_INCOMING); nm.deleteNotificationChannel(CH_INCOMING_LOUD)
        ensureChannels(ctx)
    }

    /** Notificación de llamada entrante a pantalla completa (abre CallActivity). */
    fun showIncoming(ctx: Context) {
        if (es.qvadis.app.ui.CallActivity.isActive) return   // ya está la pantalla de llamada: no dupliques
        ensureChannels(ctx)
        val full = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Sin permiso de pantalla completa (Android 14+) la pantalla de llamada no se abre sola y, con el
        // canal silencioso, el timbre no sonaría: se usa el canal con sonido y vibración.
        val nmgr = ctx.getSystemService(NotificationManager::class.java)
        val noFsi = Build.VERSION.SDK_INT >= 34 && !nmgr.canUseFullScreenIntent()
        val n = NotificationCompat.Builder(ctx, if (noFsi) CH_INCOMING_LOUD else CH_INCOMING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(R.string.call_title))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(true)
            .setFullScreenIntent(full, true)   // se muestra a pantalla completa, incluso bloqueado
            // Si la llamada se atiende en el panel o se cancela antes de registrar el SIP, nadie la
            // cancela: sin caducidad quedaba una notificación "ongoing" imposible de descartar.
            .setTimeoutAfter(45_000)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_INCOMING, n)
    }

    fun cancelIncoming(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_INCOMING)

    /** Notificación de llamada perdida; si hay foto, la descarga y la muestra (BigPicture). */
    fun showMissed(ctx: Context, photoFile: String?) {
        ensureChannels(ctx)
        val acc = AccountStore.load(ctx)
        thread {
            val bmp = if (!photoFile.isNullOrBlank() && acc != null) runCatching {
                val api = ApiClient(acc)
                api.http.newCall(Request.Builder().url(api.snapUrl(photoFile)).build()).execute().use { r ->
                    r.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull() else null

            val openIntent = Intent(ctx, es.qvadis.app.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val open = PendingIntent.getActivity(
                ctx, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val b = NotificationCompat.Builder(ctx, CH_MISSED)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Llamada perdida en la puerta")
                .setContentText("Alguien llamó y no se atendió")
                .setAutoCancel(true)
                .setContentIntent(open)
            if (bmp != null) {
                b.setLargeIcon(bmp)
                b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as android.graphics.Bitmap?))
            }
            // una notificación por llamada perdida (id distinto): con id fijo cada una borraba a la anterior
            ctx.getSystemService(NotificationManager::class.java).notify(ID_MISSED + 100 + missedSeq.getAndIncrement() % 50, b.build())
        }
    }
}
