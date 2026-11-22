package es.qvadis.app.net

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * Pinning por huella: el certificado de la placa es autofirmado, así que NO confiamos
 * en las CAs del sistema; confiamos SOLO en el certificado cuya huella SHA-256 coincide
 * con la del emparejamiento (fp del QR). Esto da TLS real sin Let's Encrypt.
 */
object CertPinning {

    /** "AA:BB:.." o "aabb.." -> bytes. */
    private fun parseFp(fp: String): ByteArray? {
        val h = fp.replace(":", "").replace(" ", "")
        if (!Regex("[0-9a-fA-F]{64}").matches(h)) return null   // sin huella válida NO se confía en nada
        return h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun sha256(cert: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)

    /** OkHttpClient que solo acepta el cert con la huella dada. */
    fun client(fingerprintSha256: String): OkHttpClient {
        val want = parseFp(fingerprintSha256)
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw SSLException("sin certificado")
                if (want == null || !MessageDigest.isEqual(sha256(leaf), want)) throw SSLException("huella no coincide (pinning)")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            // cert autofirmado: el nombre no cuenta; vale solo si el cert de la sesión es el anclado
            .hostnameVerifier { _, session ->
                want != null && runCatching {
                    MessageDigest.isEqual(sha256(session.peerCertificates[0] as X509Certificate), want)
                }.getOrDefault(false)
            }
            .build()
    }
}
