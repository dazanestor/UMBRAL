package es.qvadis.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream

/**
 * Vista que pinta un stream MJPEG (multipart/x-mixed-replace) de /api/camera/stream.mjpg.
 * Lee los JPEG del stream (delimitados por SOI FFD8 / EOI FFD9) y los dibuja.
 * Usa el OkHttpClient con pinning del ApiClient. Esqueleto funcional; ajusta rendimiento.
 */
class MjpegView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    @Volatile private var frame: Bitmap? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var response: okhttp3.Response? = null
    private val dst = Rect()

    fun start(url: String, client: OkHttpClient) {
        if (running) return
        running = true
        worker = Thread { readLoop(url, client) }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { response?.close() }   // desbloquea el read() bloqueante
        response = null
        worker?.interrupt()
        worker = null
    }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    private fun readLoop(url: String, client: OkHttpClient) {
        try {
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            response = resp
            val input = BufferedInputStream(resp.body?.byteStream() ?: return, 32 * 1024)
            val buf = ByteArrayOutputStream(64 * 1024)
            var prev = -1
            var inJpeg = false
            while (running) {
                val b = input.read()
                if (b == -1) break
                if (!inJpeg) {
                    if (prev == 0xFF && b == 0xD8) {          // SOI
                        inJpeg = true; buf.reset(); buf.write(0xFF); buf.write(0xD8)
                    }
                } else {
                    buf.write(b)
                    if (prev == 0xFF && b == 0xD9) {          // EOI -> frame completo
                        inJpeg = false
                        val bytes = buf.toByteArray()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                            // NO reciclar el frame anterior aquí: onDraw (hilo UI) podría estar usándolo
                            // (crash "recycled bitmap"). Se deja al GC. Solo intercambiamos la referencia.
                            frame = it; postInvalidate()
                        }
                    }
                }
                prev = b
            }
            resp.close()
        } catch (_: Exception) {
            // conexión caída / stop(): se ignora; la UI puede reintentar con start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame
        if (f == null) { canvas.drawColor(Color.BLACK); return }
        canvas.drawColor(Color.BLACK)
        // encajar manteniendo proporción (letterbox)
        val vw = width.toFloat(); val vh = height.toFloat()
        val s = minOf(vw / f.width, vh / f.height)
        val w = (f.width * s).toInt(); val h = (f.height * s).toInt()
        val x = ((vw - w) / 2).toInt(); val y = ((vh - h) / 2).toInt()
        dst.set(x, y, x + w, y + h)
        canvas.drawBitmap(f, null, dst, null)
    }
}
