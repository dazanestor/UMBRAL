package es.qvadis.app.store

import android.content.Context
import android.media.RingtoneManager

/** Preferencias no sensibles (tono, etc.). Las credenciales van en AccountStore (cifrado). */
object Prefs {
    private const val FILE = "qvadis_prefs"
    private const val K_RINGTONE = "ringtone_uri"
    private const val K_CERT = "sip_cert_pem"

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** URI del tono elegido; si no hay, el tono de llamada del sistema por defecto. */
    fun ringtoneUri(ctx: Context): String =
        p(ctx).getString(K_RINGTONE, null)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE).toString()

    fun setRingtoneUri(ctx: Context, uri: String?) =
        p(ctx).edit().apply { if (uri == null) remove(K_RINGTONE) else putString(K_RINGTONE, uri) }.apply()

    /** Cert público de la placa (PEM) para anclar TLS en Linphone; "" si aún no se descargó. */
    fun certPem(ctx: Context): String = p(ctx).getString(K_CERT, "") ?: ""
    fun setCertPem(ctx: Context, pem: String?) =
        p(ctx).edit().apply { if (pem.isNullOrBlank()) remove(K_CERT) else putString(K_CERT, pem) }.apply()
}
