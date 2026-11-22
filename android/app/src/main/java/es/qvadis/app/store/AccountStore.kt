package es.qvadis.app.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import es.qvadis.app.model.Account

/** Guarda/lee la cuenta del emparejamiento en EncryptedSharedPreferences. */
object AccountStore {
    private const val FILE = "qvadis_acc"

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, FILE,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(ctx: Context, a: Account): Boolean =
        runCatching {
            prefs(ctx).edit().apply {
                putString("host", a.host); putString("domain", a.domain)
                putInt("sipPort", a.sipPort); putInt("tlsPort", a.tlsPort); putInt("httpsPort", a.httpsPort)
                putString("ext", a.ext); putString("secret", a.secret)
                putString("token", a.token); putString("fp", a.fingerprintSha256)
            }.commit()
        }.getOrDefault(false)

    /** Devuelve la cuenta, o null si no hay o el almacén cifrado está corrupto (→ re-emparejar). */
    fun load(ctx: Context): Account? = runCatching {
        val p = prefs(ctx)
        val ext = p.getString("ext", null) ?: return null
        Account(
            host = p.getString("host", "")!!, domain = p.getString("domain", "")!!,
            sipPort = p.getInt("sipPort", 5060), tlsPort = p.getInt("tlsPort", 5061),
            httpsPort = p.getInt("httpsPort", 8443), ext = ext,
            secret = p.getString("secret", "")!!, token = p.getString("token", "")!!,
            fingerprintSha256 = p.getString("fp", "")!!
        )
    }.getOrNull()

    fun clear(ctx: Context) {
        // Si el almacén cifrado está corrupto, borramos el fichero directamente.
        runCatching { prefs(ctx).edit().clear().apply() }
            .onFailure { runCatching { ctx.deleteSharedPreferences(FILE) } }
    }
}
