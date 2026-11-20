package es.qvadis.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Deslizable "Desliza para abrir" con el mismo estilo que el panel (track redondeado,
 * relleno de acento, knob circular con chevron). Al llegar al final dispara onOpen().
 */
class SlideToOpen @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var label = "Desliza para abrir"
    var onOpen: (() -> Unit)? = null

    private val dens = resources.displayMetrics.density
    private val accent = Color.parseColor("#1E88E5")
    private val accentDim = Color.parseColor("#14507e")
    private val trackColor = Color.parseColor("#171C23")
    private val borderColor = Color.parseColor("#2A3340")
    private val knobIcon = Color.parseColor("#07252A")
    private val hintColor = Color.parseColor("#9fb4bd")

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hintColor; textAlign = Paint.Align.CENTER; textSize = 15f * dens }

    private var knobX = 0f           // left edge of knob
    private var dragging = false
    private var downDX = 0f
    private var done = false

    private fun pad() = 4f * dens
    private fun knobSize() = height - 2 * pad()
    private fun maxX() = width - knobSize() - pad()

    override fun onMeasure(w: Int, h: Int) {
        val hh = (60 * dens).toInt()
        setMeasuredDimension(View.resolveSize((280 * dens).toInt(), w), hh)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { knobX = pad() }

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        // track
        p.color = trackColor; c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * dens; p.color = borderColor
        c.drawRoundRect(RectF(1f, 1f, width - 1f, height - 1f), r, r, p); p.style = Paint.Style.FILL
        // fill hasta el knob
        p.color = accentDim
        c.drawRoundRect(RectF(0f, 0f, knobX + knobSize() + pad(), height.toFloat()), r, r, p)
        // label
        c.drawText(if (done) "Abriendo…" else label, width / 2f, height / 2f + tp.textSize / 3f, tp)
        // knob
        val ks = knobSize(); val cy = height / 2f; val cx = knobX + ks / 2f
        p.color = accent; c.drawCircle(cx, cy, ks / 2f, p)
        // chevron ">"
        p.color = knobIcon; p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f * dens
        val a = ks * 0.16f
        c.drawLine(cx - a, cy - a, cx + a, cy, p)
        c.drawLine(cx + a, cy, cx - a, cy + a, p)
        p.style = Paint.Style.FILL
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (done) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true; downDX = e.x - knobX
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                knobX = (e.x - downDX).coerceIn(pad(), maxX())
                invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging && knobX >= maxX() - 4 * dens) {
                    done = true; invalidate()
                    onOpen?.invoke()
                    postDelayed({ done = false; knobX = pad(); invalidate() }, 2200)
                } else {
                    knobX = pad(); invalidate()
                }
                dragging = false; return true
            }
        }
        return super.onTouchEvent(e)
    }
}
