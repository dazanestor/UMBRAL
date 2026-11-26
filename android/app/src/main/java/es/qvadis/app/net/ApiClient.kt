package es.qvadis.app.net

import es.qvadis.app.model.Account
import es.qvadis.app.model.CallEntry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Cliente HTTPS (con pinning) para abrir puerta, cámara, registrar FCM, etc. */
class ApiClient(private val acc: Account) {
    /** El token viaja en la cabecera Authorization (no en la URL: no queda en logs ni cachés). */
    val http: OkHttpClient = CertPinning.client(acc.fingerprintSha256).newBuilder()
        .addInterceptor { c ->
            c.proceed(c.request().newBuilder().header("Authorization", "Bearer ${acc.token}").build())
        }.build()
    val fingerprint: String get() = acc.fingerprintSha256

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** Las acciones que cambian estado van por POST (un GET se puede disparar con un simple enlace). */
    private fun post(path: String, extra: String = ""): Request =
        Request.Builder().url(url(path, extra)).post(ByteArray(0).toRequestBody()).build()

    private fun url(path: String, extra: String = ""): String =
        "${acc.httpsBase()}$path?${extra.removePrefix("&")}"

    /** Abre la puerta (which = front|aux). */
    @Throws(IOException::class)
    fun openDoor(which: String = "front"): Boolean {
        val req = post("/api/door/open", "&which=${enc(which)}")
        http.newCall(req).execute().use { return it.isSuccessful }
    }

    /** Certificado público de la placa (PEM), para anclarlo como CA raíz en Linphone (SIP-TLS). */
    @Throws(IOException::class)
    fun certPem(): String {
        val req = Request.Builder().url(url("/api/cert")).build()
        http.newCall(req).execute().use { return it.body?.string().orEmpty() }
    }

    /** Avisa a la placa de que este móvil atendió/abrió (cancela el aviso de perdida). */
    fun ackCall(): Boolean {
        val req = post("/api/calls/ack")
        return runCatching { http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** URL del stream MJPEG (para MjpegView / WebView). */
    fun cameraStreamUrl(): String = url("/api/camera/stream.mjpg")

    /** URL de una foto de llamadas recientes. */
    fun snapUrl(file: String): String = url("/api/snap", "&f=${enc(file)}")

    /** Registra el token FCM de este dispositivo en la placa. En el cuerpo del POST, no en la
     *  URL: a diferencia del resto de "extras" (which, volumen...), este token identifica al
     *  dispositivo y permite empujarle notificaciones, así que no debe quedar en ningún log. */
    @Throws(IOException::class)
    fun registerFcm(fcmToken: String): Boolean {
        val req = Request.Builder().url(url("/api/mobiles/fcm"))
            .post(fcmToken.toRequestBody()).build()
        http.newCall(req).execute().use { return it.isSuccessful }
    }

    /** JSON crudo de llamadas recientes (parsear fuera). */
    @Throws(IOException::class)
    fun callsJson(): String {
        val req = Request.Builder().url(url("/api/calls")).build()
        http.newCall(req).execute().use { return it.body?.string().orEmpty() }
    }

    /** Llamadas recientes ya parseadas (más recientes primero). */
    @Throws(IOException::class)
    fun calls(): List<CallEntry> = CallEntry.parseList(callsJson()) { snapUrl(it) }

    // ---- Ajustes del portero manejables desde la app ----
    private fun getStr(path: String): String {
        val req = Request.Builder().url(url(path)).build()
        http.newCall(req).execute().use { return it.body?.string().orEmpty() }
    }
    private fun hit(path: String, extra: String): Boolean {
        val req = post(path, extra)
        return runCatching { http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    @Throws(IOException::class) fun dnd(): JSONObject = JSONObject(getStr("/api/dnd"))
    @Throws(IOException::class) fun audio(): JSONObject = JSONObject(getStr("/api/audio"))
    @Throws(IOException::class) fun ringtone(): JSONObject = JSONObject(getStr("/api/ringtone"))

    /** Cambia un volumen del portero (key = spkStreet|micStreet|spkHome|micHome). */
    fun setAudio(key: String, value: Int): Boolean = hit("/api/audio/set", "&$key=$value")
    /** Volumen del timbre (0-100). */
    fun setRingVolume(v: Int): Boolean = hit("/api/ringtone/set", "&volume=$v")
    /** No molestar. */
    fun setDnd(enabled: Boolean, sched: Boolean, from: String, to: String): Boolean =
        hit("/api/dnd/set", "&enabled=${if (enabled) 1 else 0}&sched=${if (sched) 1 else 0}&from=${enc(from)}&to=${enc(to)}")

    /**
     * ¿La placa tiene cámara? Lee /api/device (campo "camera": true/false).
     * Si el backend no lo expone todavía, devuelve false (la app no muestra vídeo).
     */
    @Throws(IOException::class)
    fun hasCamera(): Boolean {
        val req = Request.Builder().url(url("/api/device")).build()
        http.newCall(req).execute().use {
            val body = it.body?.string().orEmpty()
            return runCatching { JSONObject(body).optBoolean("camera", false) }.getOrDefault(false)
        }
    }
}
