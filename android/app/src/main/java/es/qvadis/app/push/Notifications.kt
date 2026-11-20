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
    const val ID_INCOMING = 1001
    const val ID_MISSED = 1002

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
        nm.createNotificationChannel(incoming)
        nm.createNotificationChannel(missed)
    }

    /** Recrea el canal entrante para aplicar un tono nuevo (los canales fijan el sonido al crearse). */
    fun applyRingtone(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        ctx.getSystemService(NotificationManager::class.java).deleteNotificationChannel(CH_INCOMING)
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
        val n = NotificationCompat.Builder(ctx, CH_INCOMING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(R.string.call_title))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(true)
            .setFullScreenIntent(full, true)   // se muestra a pantalla completa, incluso bloqueado
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
            ctx.getSystemService(NotificationManager::class.java).notify(ID_MISSED, b.build())
        }
    }
}
