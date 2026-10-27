package app.teron.superapp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.teron.superapp.core.MeshPeer
import app.teron.superapp.core.Theme
import app.teron.superapp.crypto.QrCodeGenerator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object Components {

    fun icon(
        ctx: Context,
        resId: Int,
        sizeDp: Int = 20,
        tint: Int = Theme.textPrimary
    ): ImageView = ImageView(ctx).apply {
        setImageResource(resId)
        setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val px = Theme.dp(ctx, sizeDp)
        layoutParams = LinearLayout.LayoutParams(px, px)
    }

    fun tonalIconBadge(
        ctx: Context,
        resId: Int,
        accentColor: Int,
        boxSizeDp: Int = 42,
        iconSizeDp: Int = 20,
        radiusDp: Float = 12f
    ): FrameLayout {
        val boxPx = Theme.dp(ctx, boxSizeDp)
        val fillAlpha = if (Theme.isDark) 32 else 22
        val strokeAlpha = if (Theme.isDark) 64 else 45
        return FrameLayout(ctx).apply {
            background = Theme.roundedBg(
                Theme.withAlpha(accentColor, fillAlpha),
                Theme.dp(ctx, radiusDp).toFloat(),
                Theme.withAlpha(accentColor, strokeAlpha),
                Theme.dp(ctx, 1)
            )
            layoutParams = LinearLayout.LayoutParams(boxPx, boxPx)
            addView(
                ImageView(ctx).apply {
                    setImageResource(resId)
                    setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                FrameLayout.LayoutParams(Theme.dp(ctx, iconSizeDp), Theme.dp(ctx, iconSizeDp), Gravity.CENTER)
            )
        }
    }

    fun solidIconButton(
        ctx: Context,
        resId: Int,
        bgColor: Int = Theme.brand,
        iconTint: Int = Color.WHITE,
        boxSizeDp: Int = 44,
        iconSizeDp: Int = 20,
        radiusDp: Float = 12f,
        onClick: () -> Unit
    ): FrameLayout {
        val boxPx = Theme.dp(ctx, boxSizeDp)
        return FrameLayout(ctx).apply {
            background = Theme.rippleButton(ctx, bgColor, radiusDp)
            layoutParams = LinearLayout.LayoutParams(boxPx, boxPx)
            addView(
                ImageView(ctx).apply {
                    setImageResource(resId)
                    setColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                FrameLayout.LayoutParams(Theme.dp(ctx, iconSizeDp), Theme.dp(ctx, iconSizeDp), Gravity.CENTER)
            )
            setOnClickListener { onClick() }
        }
    }

    fun monogramAvatar(
        ctx: Context,
        initials: String,
        color: Int,
        sizeDp: Int = 44,
        textSp: Float = 15f
    ): TextView = TextView(ctx).apply {
        text = Theme.initialsFor(initials)
        textSize = textSp
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.03f
        background = Theme.roundedBg(color, Theme.dp(ctx, sizeDp * 0.28f).toFloat())
        val px = Theme.dp(ctx, sizeDp)
        layoutParams = LinearLayout.LayoutParams(px, px)
    }

    fun pillBadge(
        ctx: Context,
        label: String,
        color: Int
    ): TextView = TextView(ctx).apply {
        text = label
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color)
        background = Theme.roundedBg(
            Theme.withAlpha(color, if (Theme.isDark) 30 else 22),
            Theme.dp(ctx, 8).toFloat(),
            Theme.withAlpha(color, 70),
            Theme.dp(ctx, 1)
        )
        setPadding(Theme.dp(ctx, 8), Theme.dp(ctx, 3.5f), Theme.dp(ctx, 8), Theme.dp(ctx, 3.5f))
    }

    fun sectionHeader(ctx: Context, textStr: String): TextView = TextView(ctx).apply {
        text = textStr.uppercase()
        setTextColor(Theme.textMuted)
        textSize = 11f
        letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(Theme.dp(ctx, 2), Theme.dp(ctx, 16), Theme.dp(ctx, 2), Theme.dp(ctx, 8))
    }

    fun divider(ctx: Context, startInsetDp: Int = 0): View = View(ctx).apply {
        setBackgroundColor(Theme.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(ctx, 1f).coerceAtLeast(1)).apply {
            if (startInsetDp > 0) leftMargin = Theme.dp(ctx, startInsetDp)
        }
    }

    fun actionChip(
        ctx: Context,
        label: String,
        active: Boolean,
        iconRes: Int = 0,
        activeColor: Int = Theme.brand,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = if (active) {
            Theme.rippleButton(ctx, activeColor, 10f)
        } else {
            Theme.rippleCard(ctx, Theme.card, 10f, Theme.border, 1f)
        }
        setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 7.5f), Theme.dp(ctx, 12), Theme.dp(ctx, 7.5f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = Theme.dp(ctx, 8)
        }
        if (iconRes != 0) {
            addView(icon(ctx, iconRes, 15, if (active) Color.WHITE else Theme.textSecondary).apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = Theme.dp(ctx, 6)
            })
        }
        addView(TextView(ctx).apply {
            text = label
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (active) Color.WHITE else Theme.textSecondary)
        })
        setOnClickListener { onClick() }
    }

    fun primaryButton(
        ctx: Context,
        label: String,
        iconRes: Int = 0,
        bgColor: Int = Theme.brand,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = Theme.rippleButton(ctx, bgColor, 12f)
        setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 12), Theme.dp(ctx, 16), Theme.dp(ctx, 12))
        if (iconRes != 0) {
            addView(icon(ctx, iconRes, 17, Color.WHITE).apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = Theme.dp(ctx, 8)
            })
        }
        addView(TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        setOnClickListener { onClick() }
    }

    fun secondaryButton(
        ctx: Context,
        label: String,
        iconRes: Int = 0,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = Theme.rippleCard(ctx, Theme.cardAlt, 12f, Theme.border, 1f)
        setPadding(Theme.dp(ctx, 14), Theme.dp(ctx, 11.5f), Theme.dp(ctx, 14), Theme.dp(ctx, 11.5f))
        if (iconRes != 0) {
            addView(icon(ctx, iconRes, 17, Theme.textPrimary).apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = Theme.dp(ctx, 8)
            })
        }
        addView(TextView(ctx).apply {
            text = label
            setTextColor(Theme.textPrimary)
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        setOnClickListener { onClick() }
    }

    data class LabeledField(val container: LinearLayout, val input: EditText)

    fun labeledInput(
        ctx: Context,
        label: String,
        hintText: String,
        initialValue: String = "",
        isPassword: Boolean = false,
        singleLine: Boolean = true,
        minLinesCount: Int = 1
    ): LabeledField {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Theme.dp(ctx, 12)
            }
        }
        if (label.isNotEmpty()) {
            box.addView(TextView(ctx).apply {
                text = label
                setTextColor(Theme.textSecondary)
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(Theme.dp(ctx, 2), 0, 0, Theme.dp(ctx, 5))
            })
        }
        val edit = EditText(ctx).apply {
            setText(initialValue)
            hint = hintText
            setHintTextColor(Theme.textMuted)
            setTextColor(Theme.textPrimary)
            textSize = 14.5f
            background = Theme.roundedBg(Theme.cardAlt, Theme.dp(ctx, 11).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val px = Theme.dp(ctx, 13)
            val py = Theme.dp(ctx, 11)
            setPadding(px, py, px, py)
            if (isPassword) {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else if (singleLine) {
                setSingleLine(true)
            } else {
                minLines = minLinesCount
                gravity = Gravity.TOP or Gravity.START
            }
        }
        box.addView(edit)
        return LabeledField(box, edit)
    }

    fun emptyStateCard(
        ctx: Context,
        iconRes: Int,
        title: String,
        subtitle: String,
        ctaLabel: String? = null,
        onCtaClick: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
        val p = Theme.dp(ctx, 24)
        setPadding(p, p, p, p)
        addView(tonalIconBadge(ctx, iconRes, Theme.textSecondary, 48, 22, 14f))
        addView(TextView(ctx).apply {
            text = title
            setTextColor(Theme.textPrimary)
            textSize = 15.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(ctx, 12), 0, Theme.dp(ctx, 4))
        })
        addView(TextView(ctx).apply {
            text = subtitle
            setTextColor(Theme.textSecondary)
            textSize = 13f
            gravity = Gravity.CENTER
        })
        if (ctaLabel != null && onCtaClick != null) {
            addView(primaryButton(ctx, ctaLabel, onClick = onCtaClick).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Theme.dp(ctx, 14)
                }
            })
        }
    }
}

/**
 * Sharp pixel-aligned ISO QR Code View for blockchain receive addresses & contact URIs.
 */
class QrCodeView(context: Context, content: String) : View(context) {

    private var matrix: Array<BooleanArray> = QrCodeGenerator.encode(content)
    private val darkPaint = Paint().apply {
        color = 0xFF0B0D11.toInt()
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    fun updateContent(newContent: String) {
        matrix = QrCodeGenerator.encode(newContent)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 10f) return
        val r = Theme.dp(context, 14).toFloat()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, bgPaint)

        val n = matrix.size
        val quietZone = 3
        val totalModules = n + quietZone * 2
        val cell = size / totalModules.toFloat()
        val ox = (width - size) / 2f + quietZone * cell
        val oy = (height - size) / 2f + quietZone * cell

        for (y in 0 until n) {
            for (x in 0 until n) {
                if (matrix[y][x]) {
                    val left = ox + x * cell
                    val top = oy + y * cell
                    canvas.drawRect(left, top, left + cell + 0.4f, top + cell + 0.4f, darkPaint)
                }
            }
        }
    }
}

/**
 * Clean Architectural AirMesh Radar View showing real BLE / Wi-Fi peers and RSSI distance rings.
 */
class MeshRadarView(context: Context) : View(context) {

    var peers: List<MeshPeer> = emptyList()
    var selfLabel: String = ""
    private var sweepDeg = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Theme.dp(context, 1f).toFloat()
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Theme.dp(context, 1f).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = Theme.dp(context, 10f).toFloat()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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

        // Radar field
        dotPaint.color = if (Theme.isDark) 0xFF0E1219.toInt() else 0xFFEEF2F8.toInt()
        canvas.drawCircle(cx, cy, maxR, dotPaint)

        // Subtle crosshairs
        ringPaint.color = Theme.withAlpha(Theme.border, 160)
        canvas.drawLine(cx - maxR, cy, cx + maxR, cy, ringPaint)
        canvas.drawLine(cx, cy - maxR, cx, cy + maxR, ringPaint)

        // Concentric distance rings: 50m, 200m, 800m
        val ringLabels = arrayOf("50 м", "200 м", "800 м")
        for (i in 1..3) {
            val r = maxR * (i / 3f)
            ringPaint.color = Theme.border
            canvas.drawCircle(cx, cy, r, ringPaint)
            labelPaint.color = Theme.textMuted
            canvas.drawText(ringLabels[i - 1], cx + Theme.dp(context, 18), cy - r + Theme.dp(context, 12), labelPaint)
        }

        // Subtle cobalt sweep sector
        beamPaint.color = Theme.withAlpha(Theme.brand, if (Theme.isDark) 28 else 20)
        val oval = RectF(cx - maxR, cy - maxR, cx + maxR, cy + maxR)
        canvas.drawArc(oval, sweepDeg, 42f, true, beamPaint)

        // Real nearby peers
        for ((idx, peer) in peers.withIndex()) {
            val hashAngle = ((peer.nodeId.hashCode() and 0x7FFFFFFF) % 360).toDouble() * Math.PI / 180.0
            val distRatio = when {
                peer.distanceMeters <= 50.0 -> 0.25f + (peer.distanceMeters.toFloat() / 50f) * 0.16f
                peer.distanceMeters <= 220.0 -> 0.44f + ((peer.distanceMeters.toFloat() - 50f) / 170f) * 0.24f
                else -> 0.78f
            }.coerceIn(0.22f, 0.86f)

            val px = cx + (maxR * distRatio * cos(hashAngle + idx * 0.5)).toFloat()
            val py = cy + (maxR * distRatio * sin(hashAngle + idx * 0.5)).toFloat()

            dashPaint.color = Theme.withAlpha(Theme.accentEmerald, 120)
            canvas.drawLine(cx, cy, px, py, dashPaint)

            val nodeColor = if (peer.hops > 1) Theme.accentAmber else Theme.accentEmerald
            dotPaint.color = Theme.withAlpha(nodeColor, 48)
            canvas.drawCircle(px, py, Theme.dp(context, 12).toFloat(), dotPaint)
            dotPaint.color = nodeColor
            canvas.drawCircle(px, py, Theme.dp(context, 5.5f).toFloat(), dotPaint)

            labelPaint.color = Theme.textPrimary
            canvas.drawText(
                "${peer.displayName.take(12)} (${peer.distanceMeters.toInt()} м)",
                px,
                py - Theme.dp(context, 10),
                labelPaint
            )
        }

        // Center Local Device Node
        dotPaint.color = Theme.withAlpha(Theme.brand, 52)
        canvas.drawCircle(cx, cy, Theme.dp(context, 15).toFloat(), dotPaint)
        dotPaint.color = Theme.brand
        canvas.drawCircle(cx, cy, Theme.dp(context, 6.5f).toFloat(), dotPaint)
        labelPaint.color = Theme.textSecondary
        canvas.drawText(selfLabel, cx, cy + Theme.dp(context, 22), labelPaint)

        if (isAttachedToWindow) {
            sweepDeg = (sweepDeg + 2.6f) % 360f
            postInvalidateDelayed(40L)
        }
    }
}

/**
 * Clean 32-bar audio spectrum visualizer for Teron Sound.
 */
class AudioSpectrumView(context: Context) : View(context) {

    var activePlaying: Boolean = false
    var barColor: Int = Theme.brand
    private var tick = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = 32
        val gap = Theme.dp(context, 3f).toFloat()
        val totalGap = gap * (count - 1)
        val barW = ((width - totalGap) / count).coerceAtLeast(2.5f)
        val cy = height / 2f

        for (i in 0 until count) {
            val wave = if (activePlaying) {
                val s1 = sin((tick + i * 0.42).toDouble()).toFloat()
                val s2 = cos((tick * 1.6 - i * 0.28).toDouble()).toFloat()
                (0.18f + 0.72f * ((s1 * s2 + 1f) * 0.5f)).coerceIn(0.12f, 0.94f)
            } else {
                0.12f + 0.05f * sin((i * 0.45).toDouble()).toFloat()
            }
            val barH = (height * wave).coerceAtLeast(Theme.dp(context, 4).toFloat())
            val left = i * (barW + gap)
            rect.set(left, cy - barH / 2f, left + barW, cy + barH / 2f)
            paint.color = if (activePlaying) barColor else Theme.withAlpha(Theme.textMuted, 90)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }

        if (isAttachedToWindow && activePlaying) {
            tick += 0.18f
            postInvalidateDelayed(40L)
        }
    }
}

/**
 * Finger Calligraphy Canvas with traditional 米字格 guide grid.
 */
class CalligraphyPadView(context: Context, var guideHanzi: String) : View(context) {

    private val strokes = mutableListOf<Path>()
    private var currentPath: Path? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Theme.dp(context, 1f).toFloat()
        color = Theme.border
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (Theme.isDark) 0x1EFFFFFF else 0x1A0F172A
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SERIF
    }
    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Theme.dp(context, 6.5f).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Theme.brand
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

        canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
        canvas.drawLine(w / 2f, 0f, w / 2f, h, gridPaint)
        canvas.drawLine(0f, 0f, w, h, gridPaint)
        canvas.drawLine(w, 0f, 0f, h, gridPaint)

        guidePaint.textSize = min(w, h) * 0.66f
        val fm = guidePaint.fontMetrics
        val ty = h / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(guideHanzi, w / 2f, ty, guidePaint)

        brushPaint.color = Theme.brandText
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
