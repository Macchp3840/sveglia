package it.sveglia

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Rullo circolare (stile Orologio Samsung): il valore centrale sta su due tessere a palette,
 * i vicini scorrono sopra e sotto attenuati. Trascina, lancia o tocca sopra/sotto per cambiare.
 */
class WheelPicker @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var count = 60
    var label = ""
    var onValueChanged: ((Int) -> Unit)? = null

    var value: Int
        get() = Math.floorMod(pos.roundToInt(), count)
        set(v) {
            animator?.cancel()
            pos = v.toFloat()
            lastNotified = v
            updateDescription()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val rowH = 72 * density
    private val tileW = 62 * density
    private val tileH = 96 * density
    private val tileGap = 4 * density

    /** Posizione continua in "righe": 7.0 = valore 7 al centro. */
    private var pos = 0f
    private var lastNotified = 0
    private var lastTick = 0
    private var animator: ValueAnimator? = null
    private var velocity: VelocityTracker? = null
    private var downY = 0f
    private var downPos = 0f
    private var dragging = false

    private val font = ResourcesCompat.getFont(context, R.font.archivo)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        fontVariationSettings = "'wdth' 75, 'wght' 800"
        // In dp, non sp: le cifre devono stare nelle tessere anche con caratteri di sistema grandi.
        textSize = 76 * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.ink)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        fontVariationSettings = "'wdth' 75, 'wght' 700"
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 30f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.ink_off)
    }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.tile) }
    private val cutPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.board) }
    private val rect = RectF()

    init {
        isFocusable = true
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (tileW * 2 + tileGap + 8 * density).roundToInt()
        val h = (tileH + rowH * 2 + 8 * density).roundToInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val bandTop = cy - tileH / 2
        val bandBottom = cy + tileH / 2

        // Le due tessere fisse al centro, con la linea di taglio.
        for (i in 0..1) {
            val left = cx - tileW - tileGap / 2 + i * (tileW + tileGap)
            rect.set(left, bandTop, left + tileW, bandBottom)
            canvas.drawRoundRect(rect, 6 * density, 6 * density, tilePaint)
        }

        val base = floor(pos).toInt()
        for (k in -3..3) {
            val index = base + k
            val y = cy + (index - pos) * rowH
            val text = "%02d".format(Math.floorMod(index, count))

            // Fuori dalle tessere: piccolo, spento e sempre più trasparente verso i bordi.
            small.alpha = (255 * (1.4f - abs(index - pos) / 2f)).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.clipOutRect(0f, bandTop, width.toFloat(), bandBottom)
            canvas.drawText(text, cx, y - (small.ascent() + small.descent()) / 2, small)
            canvas.restore()

            // Dentro le tessere: una cifra per tessera, grande, con passo pari alla tessera
            // così da fermo non spunta nessun vicino.
            canvas.save()
            canvas.clipRect(0f, bandTop, width.toFloat(), bandBottom)
            val by = cy + (index - pos) * tileH - (big.ascent() + big.descent()) / 2
            canvas.drawText(text.substring(0, 1), cx - tileW / 2 - tileGap / 2, by, big)
            canvas.drawText(text.substring(1), cx + tileW / 2 + tileGap / 2, by, big)
            canvas.restore()
        }
        canvas.drawRect(0f, cy - density, width.toFloat(), cy + density, cutPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                velocity = VelocityTracker.obtain()
                downY = event.y
                downPos = pos
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.y - downY) > 8 * density) dragging = true
                if (dragging) {
                    pos = downPos - (event.y - downY) / rowH
                    moved()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                velocity?.recycle()
                velocity = null
                parent?.requestDisallowInterceptTouchEvent(false)
                when {
                    dragging -> settle((pos - vy / rowH * 0.25f).roundToInt().toFloat(), (abs(vy) / 4).coerceIn(180f, 700f).toLong())
                    event.actionMasked == MotionEvent.ACTION_UP -> {
                        // Tocco sopra o sotto le tessere: un passo in quella direzione.
                        val step = if (event.y < height / 2f - tileH / 2) -1 else if (event.y > height / 2f + tileH / 2) 1 else 0
                        if (step != 0) settle(pos.roundToInt() + step.toFloat(), 220)
                        performClick()
                    }
                }
                return true
            }
        }
        velocity?.addMovement(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> step(-1)
        KeyEvent.KEYCODE_DPAD_DOWN -> step(1)
        else -> super.onKeyDown(keyCode, event)
    }

    private fun step(delta: Int): Boolean {
        settle(pos.roundToInt() + delta.toFloat(), 220)
        return true
    }

    private fun settle(target: Float, duration: Long) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(pos, target).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                pos = it.animatedValue as Float
                moved()
            }
            start()
        }
    }

    private fun moved() {
        val tick = pos.roundToInt()
        if (tick != lastTick) {
            lastTick = tick
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        val v = value
        if (v != lastNotified) {
            lastNotified = v
            updateDescription()
            onValueChanged?.invoke(v)
        }
        invalidate()
    }

    private fun updateDescription() {
        contentDescription = "$label ${"%02d".format(value)}"
    }

    // TalkBack: scorri avanti/indietro di un valore.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> step(1)
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> step(-1)
        else -> super.performAccessibilityAction(action, arguments)
    }
}
