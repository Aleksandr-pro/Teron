package app.teron.signals.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.teron.signals.core.IndicatorState
import app.teron.signals.core.Theme

/** Reusable framework-only view builders (no AndroidX). */
object Components {

    fun vertical(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun horizontal(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    fun card(ctx: Context, orientation: Int = LinearLayout.VERTICAL, padDp: Int = 14): LinearLayout =
        LinearLayout(ctx).apply {
            this.orientation = orientation
            setPadding(Theme.dp(ctx, padDp), Theme.dp(ctx, padDp), Theme.dp(ctx, padDp), Theme.dp(ctx, padDp))
            background = Theme.ripple(ctx, Theme.card)
        }

    fun text(
        ctx: Context,
        msg: String,
        sizeSp: Float = 14f,
        color: Int = Theme.textPrimary,
        bold: Boolean = false,
        mono: Boolean = false,
        gravity: Int = Gravity.START,
        weight: Float = -1f
    ): TextView = TextView(ctx).apply {
        text = msg
        textSize = Theme.sp(ctx, sizeSp)
        setTextColor(color)
        typeface = if (mono)
            Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        else
            Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        this.gravity = gravity
        setLineSpacing(0f, 1.06f)
        layoutParams = when {
            weight >= 0f -> LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            else -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    fun badge(
        ctx: Context,
        label: String,
        bg: Int,
        fg: Int,
        sizeSp: Float = 11f,
        radiusDp: Float = 9f,
        padH: Int = 10,
        padV: Int = 5
    ): TextView = TextView(ctx).apply {
        text = label
        textSize = Theme.sp(ctx, sizeSp)
        setTextColor(fg)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(Theme.dp(ctx, padH), Theme.dp(ctx, padV), Theme.dp(ctx, padH), Theme.dp(ctx, padV))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Theme.dp(ctx, radiusDp).toFloat()
            setColor(bg)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun icon(ctx: Context, res: Int, color: Int, sizeDp: Int): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        drawable?.mutate()?.setTint(color)
        layoutParams = LinearLayout.LayoutParams(Theme.dp(ctx, sizeDp), Theme.dp(ctx, sizeDp))
    }

    fun iconButton(ctx: Context, res: Int, color: Int, sizeDp: Int, onClick: () -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            drawable?.mutate()?.setTint(color)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            background = Theme.ripple(ctx, Theme.cardAlt, 12f, Theme.border)
            val s = Theme.dp(ctx, sizeDp)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }

    fun chip(ctx: Context, label: String, selected: Boolean, onClick: () -> Unit): View =
        TextView(ctx).apply {
            text = label
            textSize = Theme.sp(ctx, 12f)
            setTextColor(if (selected) Theme.textPrimary else Theme.textSecondary)
            typeface = Typeface.create(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 7), Theme.dp(ctx, 12), Theme.dp(ctx, 7))
            background = Theme.ripple(
                ctx,
                if (selected) Theme.brand else Theme.cardAlt,
                16f,
                if (selected) Theme.brand else Theme.border
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    fun coinBadge(ctx: Context, base: String, sizeDp: Int = 34, color: Int = Theme.brand): View =
        TextView(ctx).apply {
            text = base.take(4)
            textSize = Theme.sp(ctx, if (base.length <= 3) 12f else 9f)
            setTextColor(Theme.textPrimary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            val s = Theme.dp(ctx, sizeDp)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(s, s)
        }

    fun stateDot(ctx: Context, st: IndicatorState, sizeDp: Int = 9): View = View(ctx).apply {
        val s = Theme.dp(ctx, sizeDp)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Theme.stateColor(st))
        }
        layoutParams = LinearLayout.LayoutParams(s, s)
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(Theme.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(ctx, 1))
    }

    fun sectionTitle(ctx: Context, label: String): TextView =
        text(ctx, label, 12f, Theme.textMuted, bold = true).apply {
            setPadding(0, Theme.dp(ctx, 14), 0, Theme.dp(ctx, 8))
        }

    fun emptyState(ctx: Context, title: String, subtitle: String): LinearLayout =
        vertical(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(Theme.dp(ctx, 32), Theme.dp(ctx, 48), Theme.dp(ctx, 32), Theme.dp(ctx, 32))
            addView(
                text(ctx, title, 16f, Theme.textPrimary, bold = true, gravity = Gravity.CENTER).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            )
            addView(
                text(ctx, subtitle, 13f, Theme.textSecondary, gravity = Gravity.CENTER).apply {
                    setPadding(0, Theme.dp(ctx, 8), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            )
        }

    fun searchField(ctx: Context, hint: String): EditText = EditText(ctx).apply {
        setHintTextColor(Theme.textMuted)
        setTextColor(Theme.textPrimary)
        textSize = Theme.sp(ctx, 14f)
        setPadding(Theme.dp(ctx, 14), Theme.dp(ctx, 11), Theme.dp(ctx, 14), Theme.dp(ctx, 11))
        background = Theme.rounded(Theme.cardAlt, Theme.dp(ctx, 12).toFloat(), Theme.border, Theme.dp(ctx, 1))
        setSingleLine()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Theme.dp(ctx, 10) }
        this.hint = hint
    }
}
