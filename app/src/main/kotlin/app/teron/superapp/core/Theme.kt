package app.teron.superapp.core

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Theme {
    var isDark: Boolean = true

    val bg: Int get() = if (isDark) 0xFF090D16.toInt() else 0xFFF3F6FB.toInt()
    val bgElevated: Int get() = if (isDark) 0xFF111827.toInt() else 0xFFFFFFFF.toInt()
    val card: Int get() = if (isDark) 0xFF151E32.toInt() else 0xFFFFFFFF.toInt()
    val cardAlt: Int get() = if (isDark) 0xFF1B2640.toInt() else 0xFFEBF0F9.toInt()
    val border: Int get() = if (isDark) 0xFF243252.toInt() else 0xFFD9E2F2.toInt()

    val textPrimary: Int get() = if (isDark) 0xFFF8FAFC.toInt() else 0xFF0F172A.toInt()
    val textSecondary: Int get() = if (isDark) 0xFF94A3B8.toInt() else 0xFF475569.toInt()
    val textMuted: Int get() = if (isDark) 0xFF64748B.toInt() else 0xFF94A3B8.toInt()

    const val accentCyan: Int = 0xFF00E5FF.toInt()
    const val accentIndigo: Int = 0xFF6C5CE7.toInt()
    const val accentPurple: Int = 0xFF8B5CF6.toInt()
    const val accentEmerald: Int = 0xFF10B981.toInt()
    const val accentAmber: Int = 0xFFF59E0B.toInt()
    const val accentCoral: Int = 0xFFEF4444.toInt()
    const val accentPink: Int = 0xFFEC4899.toInt()
    const val accentBlue: Int = 0xFF3B82F6.toInt()

    val avatarPalette = intArrayOf(
        0xFF6C5CE7.toInt(),
        0xFF00B8D4.toInt(),
        0xFF10B981.toInt(),
        0xFFF59E0B.toInt(),
        0xFFEC4899.toInt(),
        0xFF3B82F6.toInt(),
        0xFFEF4444.toInt()
    )

    fun dp(ctx: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics).toInt()

    fun dp(ctx: Context, value: Int): Int = dp(ctx, value.toFloat())

    fun roundedBg(
        fillColor: Int,
        radiusPx: Float,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidthPx: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(fillColor)
        if (strokeWidthPx > 0 && strokeColor != Color.TRANSPARENT) {
            setStroke(strokeWidthPx, strokeColor)
        }
    }

    fun gradientBg(
        colors: IntArray,
        radiusPx: Float,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidthPx: Int = 0
    ): GradientDrawable = GradientDrawable(orientation, colors).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        if (strokeWidthPx > 0 && strokeColor != Color.TRANSPARENT) {
            setStroke(strokeWidthPx, strokeColor)
        }
    }

    fun circleBg(fillColor: Int, strokeColor: Int = Color.TRANSPARENT, strokeWidthPx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            if (strokeWidthPx > 0 && strokeColor != Color.TRANSPARENT) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }

    fun rippleCard(
        ctx: Context,
        fillColor: Int = card,
        radiusDp: Float = 18f,
        strokeColor: Int = border,
        strokeWidthDp: Float = 1f
    ): Drawable {
        val r = dp(ctx, radiusDp).toFloat()
        val content = roundedBg(fillColor, r, strokeColor, dp(ctx, strokeWidthDp))
        val mask = roundedBg(Color.WHITE, r)
        val rippleColor = if (isDark) 0x33FFFFFF else 0x220F172A
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun rippleButton(
        ctx: Context,
        fillColor: Int = accentIndigo,
        radiusDp: Float = 14f
    ): Drawable {
        val r = dp(ctx, radiusDp).toFloat()
        val content = roundedBg(fillColor, r)
        val mask = roundedBg(Color.WHITE, r)
        return RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, mask)
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    fun formatTime(ms: Long): String {
        if (ms <= 0L) return ""
        val now = System.currentTimeMillis()
        val diff = now - ms
        return if (diff < 86_400_000L) {
            SimpleDateFormat("HH:mm", Locale("ru")).format(Date(ms))
        } else {
            SimpleDateFormat("dd MMM", Locale("ru")).format(Date(ms))
        }
    }

    fun formatDateTime(ms: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(ms))
}
