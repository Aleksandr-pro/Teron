package app.teron.superapp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.teron.superapp.core.MeshPeer
import app.teron.superapp.core.Theme
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object Components {

    fun squircleIcon(
        ctx: Context,
        glyph: String,
        bgColor: Int,
        sizeDp: Int = 44,
        textSp: Float = 20f
    ): TextView = TextView(ctx).apply {
        text = glyph
        textSize = textSp
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        paint.isFakeBoldText = true
        background = Theme.roundedBg(bgColor, Theme.dp(ctx, sizeDp * 0.30f).toFloat())
        layoutParams = LinearLayout.LayoutParams(Theme.dp(ctx, sizeDp), Theme.dp(ctx, sizeDp))
    }

    fun pillBadge(
        ctx: Context,
        label: String,
        color: Int
    ): TextView = TextView(ctx).apply {
        text = label
        textSize = 11.5f
        paint.isFakeBoldText = true
        setTextColor(color)
        background = Theme.roundedBg(
            Theme.withAlpha(color, 34),
            Theme.dp(ctx, 10).toFloat(),
            Theme.withAlpha(color, 95),
            Theme.dp(ctx, 1)
        )
        setPadding(Theme.dp(ctx, 9), Theme.dp(ctx, 4), Theme.dp(ctx, 9), Theme.dp(ctx, 4))
    }

    fun sectionHeader(ctx: Context, textStr: String): TextView = TextView(ctx).apply {
        text = textStr
        setTextColor(Theme.textSecondary)
        textSize = 12.5f
        paint.isFakeBoldText = true
        setPadding(Theme.dp(ctx, 4), Theme.dp(ctx, 14), Theme.dp(ctx, 4), Theme.dp(ctx, 8))
    }

    fun actionChip(ctx: Context, label: String, active: Boolean, activeColor: Int = Theme.accentIndigo, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 13f
            paint.isFakeBoldText = active
            setTextColor(if (active) Color.WHITE else Theme.textSecondary)
            background = if (active) {
                Theme.rippleButton(ctx, activeColor, 12f)
            } else {
                Theme.rippleCard(ctx, Theme.cardAlt, 12f)
            }
            setPadding(Theme.dp(ctx, 13), Theme.dp(ctx, 8), Theme.dp(ctx, 13), Theme.dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Theme.dp(ctx, 8)
            }
            setOnClickListener { onClick() }
        }
}

/**
 * Animated 60fps AirMesh Radar View showing real BLE/Wi-Fi peers, RSSI distance rings & relay hops.
 */
class MeshRadarView(context: Context) : View(context) {

    var peers: List<MeshPeer> = emptyList()
    var selfLabel: String = "ВЫ"
    private var sweepDeg = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Theme.dp(context, 1f).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = Theme.dp(context, 10.5f).toFloat()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) * 0.44f
        if (maxR <= 10f) return

        // Background disc
        dotPaint.color = if (Theme.isDark) 0xFF0B1222.toInt() else 0xFFE6EEF9.toInt()
        canvas.drawCircle(cx, cy, maxR, dotPaint)

        // Concentric distance rings: 50m, 200m, 800m (4 hops)
        val ringLabels = arrayOf("50 м", "200 м", "800 м (эстафета)")
        for (i in 1..3) {
            val r = maxR * (i / 3f)
            ringPaint.color = Theme.withAlpha(Theme.accentCyan, if (Theme.isDark) 58 else 90)
            canvas.drawCircle(cx, cy, r, ringPaint)
            labelPaint.color = Theme.textMuted
            canvas.drawText(ringLabels[i - 1], cx, cy - r + Theme.dp(context, 13), labelPaint)
        }

        // Sweeping radar sector
        beamPaint.color = Theme.withAlpha(Theme.accentCyan, 34)
        val oval = RectF(cx - maxR, cy - maxR, cx + maxR, cy + maxR)
        canvas.drawArc(oval, sweepDeg, 48f, true, beamPaint)

        // Draw peers
        for ((idx, peer) in peers.withIndex()) {
            val hashAngle = ((peer.nodeId.hashCode() and 0x7FFFFFFF) % 360).toDouble() * Math.PI / 180.0
            val distRatio = when {
                peer.distanceMeters <= 50.0 -> 0.28f + (peer.distanceMeters.toFloat() / 50f) * 0.14f
                peer.distanceMeters <= 220.0 -> 0.45f + ((peer.distanceMeters.toFloat() - 50f) / 170f) * 0.22f
                else -> 0.78f
            }.coerceIn(0.24f, 0.86f)

            val px = cx + (maxR * distRatio * cos(hashAngle + idx * 0.5)).toFloat()
            val py = cy + (maxR * distRatio * sin(hashAngle + idx * 0.5)).toFloat()

            // Relay dashed line to center
            ringPaint.color = Theme.withAlpha(Theme.accentEmerald, 130)
            canvas.drawLine(cx, cy, px, py, ringPaint)

            // Halo + Node dot
            val nodeColor = if (peer.hops > 1) Theme.accentAmber else Theme.accentEmerald
            dotPaint.color = Theme.withAlpha(nodeColor, 65)
            canvas.drawCircle(px, py, Theme.dp(context, 14).toFloat(), dotPaint)
            dotPaint.color = nodeColor
            canvas.drawCircle(px, py, Theme.dp(context, 7).toFloat(), dotPaint)

            labelPaint.color = Theme.textPrimary
            canvas.drawText(
                "${peer.displayName.take(12)} (${peer.distanceMeters.toInt()}м)",
                px,
                py - Theme.dp(context, 12),
                labelPaint
            )
        }

        // Center Self Node
        dotPaint.color = Theme.withAlpha(Theme.accentIndigo, 85)
        canvas.drawCircle(cx, cy, Theme.dp(context, 20).toFloat(), dotPaint)
        dotPaint.color = Theme.accentCyan
        canvas.drawCircle(cx, cy, Theme.dp(context, 10).toFloat(), dotPaint)
        labelPaint.color = Theme.textPrimary
        canvas.drawText(selfLabel, cx, cy + Theme.dp(context, 24), labelPaint)

        if (isAttachedToWindow) {
            sweepDeg = (sweepDeg + 3.2f) % 360f
            postInvalidateDelayed(36L)
        }
    }
}

/**
 * Animated 28-bar audio spectrum visualizer for Teron Sound.
 */
class AudioSpectrumView(context: Context) : View(context) {

    var activePlaying: Boolean = false
    var barColor: Int = Theme.accentCyan
    private var tick = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = 28
        val gap = Theme.dp(context, 3f).toFloat()
        val totalGap = gap * (count - 1)
        val barW = ((width - totalGap) / count).coerceAtLeast(3f)
        val cy = height / 2f

        for (i in 0 until count) {
            val wave = if (activePlaying) {
                val s1 = sin((tick + i * 0.45).toDouble()).toFloat()
                val s2 = cos((tick * 1.7 - i * 0.3).toDouble()).toFloat()
                (0.22f + 0.72f * ((s1 * s2 + 1f) * 0.5f)).coerceIn(0.14f, 0.96f)
            } else {
                0.14f + 0.06f * sin((i * 0.5).toDouble()).toFloat()
            }
            val barH = (height * wave).coerceAtLeast(Theme.dp(context, 6).toFloat())
            val left = i * (barW + gap)
            rect.set(left, cy - barH / 2f, left + barW, cy + barH / 2f)
            paint.color = if (i % 3 == 0) Theme.accentIndigo else barColor
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }

        if (isAttachedToWindow && activePlaying) {
            tick += 0.19f
            postInvalidateDelayed(40L)
        }
    }
}

/**
 * Finger Calligraphy Canvas with traditional Chinese 米字格 grid for Red Panda / Hanzi Dojo!
 */
class CalligraphyPadView(context: Context, var guideHanzi: String) : View(context) {

    private val strokes = mutableListOf<Path>()
    private var currentPath: Path? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Theme.dp(context, 1f).toFloat()
        color = Theme.withAlpha(Theme.accentAmber, 65)
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (Theme.isDark) 0x22FFFFFF else 0x220F172A
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SERIF
    }
    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Theme.dp(context, 7f).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Theme.accentCyan
    }

    fun clearPad() {
        strokes.clear()
        currentPath = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 米字格 8-direction calligraphy guide lines
        canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
        canvas.drawLine(w / 2f, 0f, w / 2f, h, gridPaint)
        canvas.drawLine(0f, 0f, w, h, gridPaint)
        canvas.drawLine(w, 0f, 0f, h, gridPaint)

        // Faint guide character watermark
        guidePaint.textSize = min(w, h) * 0.68f
        val fm = guidePaint.fontMetrics
        val ty = h / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(guideHanzi, w / 2f, ty, guidePaint)

        brushPaint.color = if (Theme.isDark) Theme.accentCyan else Theme.accentIndigo
        for (p in strokes) canvas.drawPath(p, brushPaint)
        currentPath?.let { canvas.drawPath(it, brushPaint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = Path().apply { moveTo(event.x, event.y) }
                currentPath = p
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath?.lineTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentPath?.let { strokes.add(it) }
                currentPath = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
