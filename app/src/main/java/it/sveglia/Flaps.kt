package it.sveglia

import android.animation.ValueAnimator
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Tabellone: ogni cifra su una tessera, gli operatori in giallo. Le tessere cambiate
 * si ribaltano dall'alto come le palette Solari.
 */
fun LinearLayout.showFlaps(text: String, sizeSp: Float) {
    val old = tag as? String
    if (old == text) return
    tag = text
    removeAllViews()
    val gap = (4 * resources.displayMetrics.density).toInt()
    // Con caratteri di sistema grandi le tessere si restringono per stare in larghezza: un numero tagliato non si può risolvere.
    val chars = text.filter { it != ' ' }
    val units = chars.sumOf { if (it.isDigit()) 0.82 else 0.7 }.toFloat()
    val room = (resources.displayMetrics.widthPixels - 40 * resources.displayMetrics.density - gap * chars.length) / units
    val px = minOf(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics), room)
    var index = 0
    for ((i, ch) in text.withIndex()) {
        if (ch == ' ') continue
        val digit = ch.isDigit()
        val view = if (digit) {
            TextView(context, null, 0, R.style.Board_Flap).apply { setText(ch.toString()) }
        } else {
            TextView(context).apply {
                setText(ch.toString())
                setTextColor(ContextCompat.getColor(context, R.color.signal))
                fontVariationSettings = "'wght' 800"
                gravity = Gravity.CENTER
            }
        }
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        val params = if (digit) {
            LinearLayout.LayoutParams((px * 0.82f).toInt(), (px * 1.3f).toInt())
        } else {
            LinearLayout.LayoutParams((px * 0.7f).toInt(), (px * 1.3f).toInt())
        }
        if (childCount > 0) params.marginStart = gap
        addView(view, params)
        val changed = old?.getOrNull(i) != ch
        if (digit && changed && ValueAnimator.areAnimatorsEnabled()) {
            view.pivotY = 0f
            view.scaleY = 0f
            view.animate().scaleY(1f).setStartDelay(index * 35L).setDuration(170).start()
        }
        index++
    }
}
