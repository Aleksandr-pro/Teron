package app.teron.signals.core

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import java.text.DecimalFormat
import java.util.Locale

/**
 * Disciplined dark palette ("Graphite Carbon") shared with the Teron super-app.
 * Pure framework drawables — no AndroidX.
 */
object Theme {
    val bg: Int = 0xFF0B0D11.toInt()
    val card: Int = 0xFF151922.toInt()
    val cardAlt: Int = 0xFF1C212D.toInt()
    val border: Int = 0xFF252C3B.toInt()
    val divider: Int = 0xFF1F2532.toInt()

    val textPrimary: Int = 0xFFF1F5F9.toInt()
    val textSecondary: Int = 0xFF8B95A7.toInt()
    val textMuted: Int = 0xFF5A6475.toInt()

    val brand: Int = 0xFF2563EB.toInt()
    val brandText: Int = 0xFF60A5FA.toInt()

    val green: Int = 0xFF10B981.toInt()
    val greenText: Int = 0xFF34D399.toInt()
    val red: Int = 0xFFEF4444.toInt()
    val redText: Int = 0xFFF87171.toInt()
    val amber: Int = 0xFFF59E0B.toInt()
    val amberText: Int = 0xFFFBBF24.toInt()

    private val priceFmt = DecimalFormat("#,##0.#####")
    private val pctFmt = DecimalFormat("+0.00;-0.00")

    fun dp(ctx: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics).toInt()

    fun dp(ctx: Context, value: Int): Int = dp(ctx, value.toFloat())

    fun sp(ctx: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, ctx.resources.displayMetrics)

    fun rounded(fill: Int, radiusPx: Float, stroke: Int = Color.TRANSPARENT, strokeW: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fill)
            if (strokeW > 0 && stroke != Color.TRANSPARENT) setStroke(strokeW, stroke)
        }

    fun ripple(ctx: Context, fill: Int = card, radiusDp: Float = 14f, stroke: Int = border): Drawable {
        val r = dp(ctx, radiusDp).toFloat()
        val content = rounded(fill, r, stroke, dp(ctx, 1).coerceAtLeast(1))
        return RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), content, rounded(Color.WHITE, r))
    }

    fun withAlpha(c: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    fun price(v: Double): String = if (v.isFinite()) priceFmt.format(v) else "—"

    fun pct(v: Double): String = if (v.isFinite()) pctFmt.format(v) + "%" else "—"

    fun num(v: Double, decimals: Int = 2): String =
        if (v.isFinite()) "%.${decimals}f".format(v) else "—"

    fun compact(v: Double): String {
        if (!v.isFinite()) return "—"
        val abs = Math.abs(v)
        return when {
            abs >= 1e12 -> "%.2fT".format(v / 1e12)
            abs >= 1e9 -> "%.2fB".format(v / 1e9)
            abs >= 1e6 -> "%.2fM".format(v / 1e6)
            abs >= 1e3 -> "%.2fK".format(v / 1e3)
            else -> "%.2f".format(v)
        }
    }

    fun signalColor(s: Signal): Int = when (s) {
        Signal.STRONG_BUY, Signal.BUY -> green
        Signal.HOLD -> textSecondary
        Signal.SELL, Signal.STRONG_SELL -> red
    }

    fun signalBg(s: Signal): Int = when (s) {
        Signal.STRONG_BUY, Signal.BUY -> 0xFF0E2A22.toInt()
        Signal.HOLD -> cardAlt
        Signal.SELL, Signal.STRONG_SELL -> 0xFF2A1416.toInt()
    }

    fun signalFg(s: Signal): Int = when (s) {
        Signal.STRONG_BUY, Signal.BUY -> greenText
        Signal.HOLD -> 0xFFC7CEDA.toInt()
        Signal.SELL, Signal.STRONG_SELL -> redText
    }

    fun stateColor(st: IndicatorState): Int = when (st) {
        IndicatorState.BULLISH -> green
        IndicatorState.BEARISH -> red
        IndicatorState.NEUTRAL -> textSecondary
        IndicatorState.UNKNOWN -> textMuted
    }
}
