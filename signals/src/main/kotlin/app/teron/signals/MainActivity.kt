package app.teron.signals

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.teron.signals.core.PairSignal
import app.teron.signals.core.Theme
import app.teron.signals.data.UpdateChecker
import app.teron.signals.ui.Components
import app.teron.signals.ui.DetailScreen
import app.teron.signals.ui.PairsScreen
import app.teron.signals.ui.SignalsScreen

class MainActivity : Activity() {

    private lateinit var app: SignalsApp
    private lateinit var content: FrameLayout
    private lateinit var bottomNav: LinearLayout

    private val stack = ArrayList<View>()
    private var currentTab = 0

    private var signalsScreen: SignalsScreen? = null
    private var pairsScreen: PairsScreen? = null
    private var signalsView: View? = null
    private var pairsView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as SignalsApp
        setContentView(shell())
        // Show a loading state immediately — never leave a black empty screen
        // while the pair catalogue is being fetched.
        setContent(loadingView())

        app.boot(
            onReady = {
                buildScreens()
                showTab(0)
                signalsScreen?.refresh()
                UpdateChecker.checkAuto(this@MainActivity)
            },
            onError = { showFatal(it) }
        )
    }

    override fun onBackPressed() {
        if (stack.isNotEmpty()) popDetail() else super.onBackPressed()
    }

    // ------------------------------------------------------------------ shell

    private fun shell(): View {
        val root = FrameLayout(this)
        content = FrameLayout(this).apply { setBackgroundColor(Theme.bg) }
        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        bottomNav = navBar()
        root.addView(
            bottomNav,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        )
        return root
    }

    private fun navBar(): LinearLayout {
        val act = this
        val nav = Components.horizontal(act)
        nav.setPadding(dp(8), dp(6), dp(8), dp(8))
        nav.background = ColorDrawable(Theme.withAlpha(Theme.card, 235))
        val items = listOf(
            NavItem(R.drawable.ic_signal, "Сигналы"),
            NavItem(R.drawable.ic_star, "Пары")
        )
        items.forEachIndexed { i, item ->
            val selected = i == currentTab
            val btn = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { if (currentTab != i) showTab(i) }
                background = if (selected) Theme.ripple(act, Theme.cardAlt, 14f) else ColorDrawable(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            btn.addView(
                ImageView(act).apply {
                    setImageResource(item.icon)
                    drawable?.mutate()?.setTint(if (selected) Theme.brandText else Theme.textSecondary)
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                }
            )
            btn.addView(
                TextView(act).apply {
                    text = item.label
                    textSize = Theme.sp(act, 11f)
                    setTextColor(if (selected) Theme.textPrimary else Theme.textMuted)
                    setPadding(dp(6), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            )
            nav.addView(btn)
        }
        return nav
    }

    private data class NavItem(val icon: Int, val label: String)

    // ---------------------------------------------------------------- screens

    private fun buildScreens() {
        val prefs = app.prefs
        signalsScreen = SignalsScreen(
            ctx = this,
            prefs = prefs,
            universe = { app.universe },
            onOpenDetail = { openDetail(it) },
            onChanged = {
                pairsScreen?.onResume()
                signalsScreen?.refresh()
            }
        )
        pairsScreen = PairsScreen(
            ctx = this,
            prefs = prefs,
            universe = { app.universe },
            onChanged = { signalsScreen?.refresh() }
        )
        signalsView = signalsScreen?.build()
        pairsView = pairsScreen?.build()
    }

    private fun showTab(index: Int) {
        currentTab = index
        for (i in 0 until bottomNav.childCount) {
            val v = bottomNav.getChildAt(i)
            v.alpha = if (i == index) 1f else 0.5f
        }
        val view = when (index) {
            0 -> signalsView
            1 -> {
                pairsScreen?.onResume()
                pairsView
            }
            else -> null
        } ?: loadingView()
        setContent(view)
    }

    private fun setContent(view: View) {
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun loadingView(): View {
        val act = this
        return TextView(act).apply {
            text = "Загрузка данных…"
            textSize = Theme.sp(act, 14f)
            setTextColor(Theme.textSecondary)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    // ------------------------------------------------------------------ detail

    private fun openDetail(ps: PairSignal) {
        val view = DetailScreen(this, ps) { popDetail() }.build()
        stack.add(view)
        content.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun popDetail() {
        if (stack.isEmpty()) return
        val view = stack.removeAt(stack.lastIndex)
        content.removeView(view)
    }

    // -------------------------------------------------------------------- error

    private fun showFatal(message: String) {
        val act = this
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(28))
        }
        body.addView(
            TextView(act).apply {
                text = "Не удалось загрузить данные"
                textSize = Theme.sp(act, 16f)
                setTextColor(Theme.textPrimary)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        )
        body.addView(
            TextView(act).apply {
                text = message
                textSize = Theme.sp(act, 13f)
                setTextColor(Theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(24))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        )
        body.addView(
            TextView(act).apply {
                text = "Повторить"
                textSize = Theme.sp(act, 14f)
                setTextColor(Theme.brandText)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    setContent(loadingView())
                    app.boot(
                        onReady = { buildScreens(); showTab(0); signalsScreen?.refresh() },
                        onError = { showFatal(it) }
                    )
                }
            }
        )
        setContent(body)
    }

    private fun dp(v: Int): Int = Theme.dp(this, v)
}
