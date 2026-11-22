package es.qvadis.app.model

import android.net.Uri

/** Datos del emparejamiento (del QR qvadis://pair). Se persisten cifrados. */
data class Account(
    val host: String,        // IP LAN de la placa
    val domain: String,      // dominio/DDNS para acceso remoto (puede ir vacío)
    val sipPort: Int,        // 5060 (UDP, LAN)
    val tlsPort: Int,        // 5061 (SIP-TLS)
    val httpsPort: Int,      // 8443 (API remota)
    val ext: String,         // extensión SIP (usuario)
    val secret: String,      // contraseña SIP
    val token: String,       // token para abrir puerta / cámara por HTTPS
    val fingerprintSha256: String // huella del cert para pinning (AA:BB:...)
) {
    /** Base HTTPS: usa el dominio si existe (vale dentro y fuera), si no la IP LAN. */
    fun httpsBase(): String {
        val h = if (domain.isNotBlank()) domain else host
        return "https://$h:$httpsPort"
    }
    /** Dominio/host para el registro SIP (dominio si hay, para que valga fuera). */
    fun sipDomain(): String = if (domain.isNotBlank()) domain else host

    companion object {
        /** Parsea una URI qvadis://pair?... (ver README). */
        fun fromQr(text: String): Account? {
            val u = runCatching { Uri.parse(text) }.getOrNull() ?: return null
            if (u.scheme != "qvadis" || u.host != "pair") return null
            fun q(k: String) = u.getQueryParameter(k).orEmpty()
            val ext = q("ext"); val host = q("host")
            if (ext.isBlank() || host.isBlank()) return null
            val fp = q("fp").replace(":", "").replace(" ", "")
            if (!Regex("[0-9a-fA-F]{64}").matches(fp)) return null   // huella SHA-256 obligatoria
            if (q("token").isBlank()) return null
            fun port(k: String, d: Int) = (q(k).toIntOrNull() ?: d).takeIf { it in 1..65535 }
            return Account(
                host = host,
                domain = q("domain"),
                sipPort = port("sipPort", 5060) ?: return null,
                tlsPort = port("tlsPort", 5061) ?: return null,
                httpsPort = port("httpsPort", 8443) ?: return null,
                ext = ext,
                secret = q("secret"),
                token = q("token"),
                fingerprintSha256 = q("fp")
            )
        }
    }
}
