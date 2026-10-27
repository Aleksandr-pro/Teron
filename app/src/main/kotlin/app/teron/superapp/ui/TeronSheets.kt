package app.teron.superapp.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.teron.superapp.R
import app.teron.superapp.core.Theme

/**
 * Clean, modern native Bottom Sheet & Modal Card presenter replacing OS default AlertDialogs.
 */
object TeronSheets {

    fun showBottomSheet(
        ctx: Context,
        title: String,
        subtitle: String? = null,
        buildBody: (dialog: Dialog, container: LinearLayout) -> Unit
    ): Dialog {
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val rTop = Theme.dp(ctx, 22).toFloat()
        val sheetBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(rTop, rTop, rTop, rTop, 0f, 0f, 0f, 0f)
            setColor(Theme.bgElevated)
            setStroke(Theme.dp(ctx, 1), Theme.border)
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = sheetBg
            val px = Theme.dp(ctx, 18)
            setPadding(px, Theme.dp(ctx, 10), px, Theme.dp(ctx, 22))
        }

        // Top drag handle bar
        root.addView(View(ctx).apply {
            background = Theme.roundedBg(Theme.border, Theme.dp(ctx, 3).toFloat())
            layoutParams = LinearLayout.LayoutParams(Theme.dp(ctx, 36), Theme.dp(ctx, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = Theme.dp(ctx, 14)
            }
        })

        // Header Row
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Theme.dp(ctx, 12)
            }
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(ctx).apply {
            text = title
            setTextColor(Theme.textPrimary)
            textSize = 17.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (!subtitle.isNullOrBlank()) {
            titleCol.addView(TextView(ctx).apply {
                text = subtitle
                setTextColor(Theme.textSecondary)
                textSize = 12.5f
                setPadding(0, Theme.dp(ctx, 2), 0, 0)
            })
        }
        header.addView(titleCol)

        val closeBtn = FrameLayout(ctx).apply {
            val sz = Theme.dp(ctx, 34)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            background = Theme.rippleCard(ctx, Theme.cardAlt, 10f, Theme.border, 1f)
            addView(
                Components.icon(ctx, R.drawable.ic_close, 16, Theme.textSecondary),
                FrameLayout.LayoutParams(Theme.dp(ctx, 16), Theme.dp(ctx, 16), Gravity.CENTER)
            )
            setOnClickListener { dlg.dismiss() }
        }
        header.addView(closeBtn)
        root.addView(header)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
        }
        val bodyCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        buildBody(dlg, bodyCol)
        scroll.addView(bodyCol)
        root.addView(scroll)

        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.62f)
        }
        dlg.show()
        return dlg
    }

    fun showInfoSheet(
        ctx: Context,
        title: String,
        bodyText: String,
        primaryActionLabel: String = "Закрыть",
        onPrimaryAction: (() -> Unit)? = null
    ) {
        showBottomSheet(ctx, title) { dlg, col ->
            col.addView(TextView(ctx).apply {
                text = bodyText
                setTextColor(Theme.textSecondary)
                textSize = 13.5f
                setLineSpacing(Theme.dp(ctx, 3).toFloat(), 1.05f)
                setPadding(0, 0, 0, Theme.dp(ctx, 16))
            })
            col.addView(Components.primaryButton(ctx, primaryActionLabel) {
                dlg.dismiss()
                onPrimaryAction?.invoke()
            })
        }
    }
}
