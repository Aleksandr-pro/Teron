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

    // Architectural Graphite Carbon (Dark) & Pure Slate (Light)
    val bg: Int get() = if (isDark) 0xFF0B0D11.toInt() else 0xFFF4F6F9.toInt()
    val bgElevated: Int get() = if (isDark) 0xFF11141B.toInt() else 0xFFFFFFFF.toInt()
    val card: Int get() = if (isDark) 0xFF151922.toInt() else 0xFFFFFFFF.toInt()
    val cardAlt: Int get() = if (isDark) 0xFF1C212D.toInt() else 0xFFEDF1F7.toInt()
    val border: Int get() = if (isDark) 0xFF252C3B.toInt() else 0xFFE1E6EF.toInt()
    val divider: Int get() = if (isDark) 0xFF1F2532.toInt() else 0xFFEBEEF5.toInt()

    val textPrimary: Int get() = if (isDark) 0xFFF1F5F9.toInt() else 0xFF0F172A.toInt()
    val textSecondary: Int get() = if (isDark) 0xFF8B95A7.toInt() else 0xFF525E71.toInt()
    val textMuted: Int get() = if (isDark) 0xFF5A6475.toInt() else 0xFF8893A4.toInt()

    // Disciplined Cobalt & Semantic Status Tokens
    const val brand: Int = 0xFF2563EB.toInt()
    val brandText: Int get() = if (isDark) 0xFF60A5FA.toInt() else 0xFF2563EB.toInt()

    const val accentIndigo: Int = 0xFF2563EB.toInt()
    val accentCyan: Int get() = brandText
    const val accentBlue: Int = 0xFF3B82F6.toInt()
    const val accentEmerald: Int = 0xFF10B981.toInt()
    const val accentAmber: Int = 0xFFF59E0B.toInt()
    const val accentCoral: Int = 0xFFEF4444.toInt()
    const val accentPurple: Int = 0xFF6366F1.toInt()
    const val accentPink: Int = 0xFF0EA5E9.toInt()

    // Chain-authentic brand colors
    const val colorBtc: Int = 0xFFF7931A.toInt()
    const val colorEth: Int = 0xFF627EEA.toInt()
    const val colorUsdt: Int = 0xFF26A17B.toInt()
    const val colorTrx: Int = 0xFFEB0029.toInt()

    val avatarPalette = intArrayOf(
        0xFF2563EB.toInt(),
        0xFF0F766E.toInt(),
        0xFF334155.toInt(),
        0xFF4F46E5.toInt(),
        0xFF0369A1.toInt(),
        0xFF15803D.toInt(),
        0xFFB45309.toInt()
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
        radiusDp: Float = 14f,
        strokeColor: Int = border,
        strokeWidthDp: Float = 1f
    ): Drawable {
        val r = dp(ctx, radiusDp).toFloat()
        val content = roundedBg(fillColor, r, strokeColor, if (strokeWidthDp > 0f) dp(ctx, strokeWidthDp).coerceAtLeast(1) else 0)
        val mask = roundedBg(Color.WHITE, r)
        val rippleColor = if (isDark) 0x22FFFFFF else 0x160F172A
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun rippleButton(
        ctx: Context,
        fillColor: Int = brand,
        radiusDp: Float = 12f
    ): Drawable {
        val r = dp(ctx, radiusDp).toFloat()
        val content = roundedBg(fillColor, r)
        val mask = roundedBg(Color.WHITE, r)
        return RippleDrawable(ColorStateList.valueOf(0x36FFFFFF), content, mask)
    }

    fun rippleSurfaceRow(ctx: Context): Drawable {
        val rippleColor = if (isDark) 0x1EFFFFFF else 0x140F172A
        return RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            roundedBg(Color.TRANSPARENT, 0f),
            roundedBg(Color.WHITE, 0f)
        )
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    fun initialsFor(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "T"
        if (parts.size >= 2) {
            val a = parts[0].firstOrNull { it.isLetterOrDigit() } ?: parts[0].first()
            val b = parts[1].firstOrNull { it.isLetterOrDigit() } ?: parts[1].first()
            return "${a.uppercaseChar()}${b.uppercaseChar()}"
        }
        val clean = parts[0].filter { it.isLetterOrDigit() }
        return (if (clean.isNotEmpty()) clean.take(2) else parts[0].take(1)).uppercase()
    }

    fun shortAddress(addr: String, head: Int = 8, tail: Int = 6): String {
        if (addr.length <= head + tail + 3) return addr
        return "${addr.take(head)}…${addr.takeLast(tail)}"
    }

    fun formatTime(ms: Long): String {
        if (ms <= 0L) return ""
        val now = System.currentTimeMillis()
        val diff = now - ms
        return if (diff < 86_400_000L && diff >= -60_000L) {
            SimpleDateFormat("HH:mm", Locale("ru")).format(Date(ms))
        } else {
            SimpleDateFormat("dd.MM", Locale("ru")).format(Date(ms))
        }
    }

    fun formatDateTime(ms: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(ms))
}
