package app.teron.signals.ui

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.teron.signals.R
import app.teron.signals.core.IndicatorReading
import app.teron.signals.core.IndicatorState
import app.teron.signals.core.PairInfo
import app.teron.signals.core.PairKind
import app.teron.signals.core.PairSignal
import app.teron.signals.core.Prefs
import app.teron.signals.core.Signal
import app.teron.signals.core.Theme
import app.teron.signals.core.Timeframe
import app.teron.signals.data.TradingViewApi
import app.teron.signals.data.UpdateChecker

// ---------------------------------------------------------------------------
// Signals
// ---------------------------------------------------------------------------

class SignalsScreen(
    private val ctx: Context,
    private val prefs: Prefs,
    private val universe: () -> List<PairInfo>,
    private val onOpenDetail: (PairSignal) -> Unit,
    private val onChanged: () -> Unit
) {
    private lateinit var tfRow: LinearLayout
    private lateinit var filterRow: LinearLayout
    private lateinit var summary: TextView
    private lateinit var status: TextView
    private lateinit var listContainer: LinearLayout
    private var loading = false
    private var filterKind: PairKind? = null
    private var lastSignals: List<PairSignal> = emptyList()
    private var watchedNow: List<PairInfo> = emptyList()

    // Автообновление раз в минуту, пока экран открыт.
    private val autoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoTick = object : Runnable {
        override fun run() {
            refresh()
            autoHandler.postDelayed(this, 60_000)
        }
    }

    fun build(): View {
        val root = Components.vertical(ctx)
        root.setPadding(dp(12), dp(10), dp(12), dp(6))

        val titleRow = Components.horizontal(ctx)
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(Components.icon(ctx, R.drawable.ic_signal, Theme.brandText, 22))
        titleRow.addView(
            Components.text(ctx, "Терон Сигналы", 16f, Theme.textPrimary, bold = true).apply {
                setPadding(dp(10), 0, 0, 0)
            }
        )
        titleRow.addView(
            Components.text(ctx, "TradingView TA", 12f, Theme.textMuted, gravity = Gravity.CENTER).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
            }
        )
        titleRow.addView(
            Components.iconButton(ctx, R.drawable.ic_update, Theme.textSecondary, 20) {
                UpdateChecker.checkManual(ctx)
            }
        )
        titleRow.addView(
            Components.iconButton(ctx, R.drawable.ic_refresh, Theme.textSecondary, 20) { refresh() }
        )
        root.addView(titleRow)

        val scroll = HorizontalScrollView(ctx).apply {
            isFillViewport = false
            setPadding(0, dp(10), 0, 0)
            isHorizontalScrollBarEnabled = false
        }
        tfRow = Components.horizontal(ctx)
        tfRow.gravity = Gravity.CENTER_VERTICAL
        renderTimeframe()
        scroll.addView(tfRow)
        root.addView(scroll)

        filterRow = Components.horizontal(ctx)
        filterRow.gravity = Gravity.CENTER_VERTICAL
        filterRow.setPadding(0, dp(10), 0, 0)
        renderFilter()
        root.addView(filterRow)

        summary = Components.text(ctx, "", 12f, Theme.textSecondary, bold = true).apply {
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(summary)

        root.addView(
            Components.text(
                ctx, "Данные: публичный сканнер TradingView · автообновление 60 сек · не инвестиционный совет",
                10f, Theme.textMuted
            ).apply { setPadding(0, dp(6), 0, 0) }
        )

        autoHandler.removeCallbacks(autoTick)
        autoHandler.postDelayed(autoTick, 60_000)

        status = Components.text(ctx, "Загрузка данных…", 12f, Theme.textMuted).apply {
            setPadding(0, dp(6), 0, dp(4))
        }
        root.addView(status)

        val sv = ScrollView(ctx).apply { isFillViewport = true }
        listContainer = Components.vertical(ctx)
        sv.addView(listContainer)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun renderTimeframe() {
        tfRow.removeAllViews()
        val selected = prefs.timeframe
        for (tf in Timeframe.values()) {
            tfRow.addView(
                Components.chip(ctx, tf.label, tf.id == selected) {
                    prefs.timeframe = tf.id
                    renderTimeframe()
                    refresh()
                }
            )
            tfRow.addView(
                Components.text(ctx, "", 4f, Theme.textMuted).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            )
        }
    }

    private fun renderFilter() {
        filterRow.removeAllViews()
        val options: List<Pair<String, PairKind?>> = listOf(
            "Все рынки" to null,
            "Криптовалюты" to PairKind.CRYPTO,
            "Валюты (Forex)" to PairKind.FOREX
        )
        for ((label, kind) in options) {
            filterRow.addView(
                Components.chip(ctx, label, filterKind == kind) {
                    filterKind = kind
                    renderFilter()
                    render(lastSignals)
                }
            )
            filterRow.addView(
                Components.text(ctx, "", 4f, Theme.textMuted).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            )
        }
    }

    fun refresh() {
        if (!loading) load()
    }

    private fun load() {
        loading = true
        setStatus("Загрузка сигналов…", Theme.textMuted)
        val tf = Timeframe.fromId(prefs.timeframe)
        val pairs = watchedPairs()
        watchedNow = pairs
        if (pairs.isEmpty()) {
            loading = false
            setStatus("Нет пар в списке отслеживания", Theme.amberText)
            renderEmpty("Список пуст", "Добавьте пары во вкладке «Пары»")
            return
        }
        TradingViewApi.fetchSignals(
            pairs, tf,
            onResult = { signals ->
                loading = false
                lastSignals = signals
                setStatus(
                    "${signals.size} пар · ${tf.label} · обновлено ${nowTime()} · нажмите на пару для деталей",
                    Theme.textSecondary
                )
                renderSummary(signals)
                render(signals)
            },
            onError = { e ->
                loading = false
                setStatus("Ошибка: $e", Theme.redText)
                renderError(e)
            }
        )
    }

    private fun watchedPairs(): List<PairInfo> {
        val watched = prefs.watchlist
        val fromUniverse = universe().filter { it.ticker in watched }
        val missing = watched.filter { ticker -> fromUniverse.none { it.ticker == ticker } }
        val extra = missing.mapNotNull { TradingViewApi.buildPair(it) }
        return (fromUniverse + extra).filter { it.ticker in watched }
    }

    private fun setStatus(msg: String, color: Int) {
        status.text = msg
        status.setTextColor(color)
    }

    /**
     * Сводка — отдельно по каждому рынку, рынки не смешиваем:
     * криптовалюты считаются по монетам, валютные — по парам.
     */
    private fun renderSummary(signals: List<PairSignal>) {
        if (signals.isEmpty()) {
            summary.text = ""
            return
        }
        val parts = ArrayList<String>()
        for (kind in PairKind.values()) {
            val group = signals.filter { it.pair.kind == kind }
            if (group.isEmpty()) continue
            val buy = group.count { it.finalSignal == Signal.BUY || it.finalSignal == Signal.STRONG_BUY }
            val sell = group.count { it.finalSignal == Signal.SELL || it.finalSignal == Signal.STRONG_SELL }
            val hold = group.size - buy - sell
            val strongest = group.maxByOrNull { it.confidence }
            val title = if (kind == PairKind.FOREX) "Валютные пары" else "Криптовалюты"
            parts.add(
                "$title: ▲ $buy · ▼ $sell · ■ $hold" +
                    (strongest?.let { " · сильнейший: ${it.pair.display} (${it.confidence}%)" } ?: "")
            )
        }
        summary.text = parts.joinToString("\n")
    }

    private fun render(signals: List<PairSignal>) {
        listContainer.removeAllViews()
        val visible = signals.filter { filterKind == null || it.pair.kind == filterKind }
        if (visible.isEmpty()) {
            renderEmpty(
                "Нет данных",
                if (signals.isEmpty()) "Нажмите «Обновить» или смените таймфрейм"
                else "В этой категории пока пусто — добавьте пары во вкладке «Пары»"
            )
            return
        }
        var first = true
        // Порядок секций всегда: сначала криптовалюты (каждая монета отдельно),
        // затем валютные пары. Рынки друг с другом не перемешиваются.
        val grouped = visible.groupBy { it.pair.kind }
        for (kind in PairKind.values()) {
            val group = grouped[kind] ?: continue
            if (!first) {
                listContainer.addView(
                    Components.divider(ctx).apply { setPadding(0, dp(10), 0, dp(2)) }
                )
            }
            first = false
            listContainer.addView(
                Components.sectionTitle(
                    ctx,
                    if (kind == PairKind.FOREX) "Валютные пары · смотрим парами (без OTC)" else "Криптовалюты · каждая монета отдельно"
                )
            )
            // Внутри секции — закреплённый порядок: BTC, ETH, SOL, LTC, BCH,
            // затем остальные по силе сигнала; валютные пары — в своём порядке.
            val ordered = group.sortedWith(
                compareBy<PairSignal> { pinnedIndex(it.pair) }
                    .thenByDescending { it.confidence }
            )
            ordered.forEachIndexed { i, ps ->
                if (i > 0) {
                    listContainer.addView(
                        Components.divider(ctx).apply { setPadding(0, dp(6), 0, dp(6)) }
                    )
                }
                listContainer.addView(signalCard(ps))
            }
        }
        renderMissing(signals)
    }

    /** Место инструмента в закреплённом порядке своей секции. */
    private fun pinnedIndex(p: PairInfo): Int {
        val list = if (p.kind == PairKind.FOREX)
            TradingViewApi.FOREX_PAIRS.map { it.ticker }
        else
            TradingViewApi.CORE_CRYPTO_TICKERS
        val idx = list.indexOf(p.ticker)
        return if (idx >= 0) idx else list.size
    }

    /**
     * Инструменты из списка, по которым сканнер не вернул данных, всё равно
     * показываем компактной строкой «нет данных» — чтобы, например, Биткоин
     * никогда не «пропадал» из списка молча.
     */
    private fun renderMissing(signals: List<PairSignal>) {
        val got = signals.map { it.pair.ticker }.toHashSet()
        val missing = watchedNow.filter {
            it.ticker !in got && (filterKind == null || it.kind == filterKind)
        }
        if (missing.isEmpty()) return
        listContainer.addView(Components.sectionTitle(ctx, "Нет данных по:"))
        for (p in missing.sortedWith(compareBy { pinnedIndex(it) })) {
            val row = Components.card(ctx, LinearLayout.HORIZONTAL, 9)
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(Components.coinBadge(ctx, p.base, 26))
            row.addView(
                Components.text(ctx, p.display, 13f, Theme.textPrimary, bold = true).apply {
                    setPadding(dp(8), 0, 0, 0)
                }
            )
            row.addView(
                Components.text(
                    ctx, "нет данных · потяните обновить ↻", 11f, Theme.textMuted, weight = 1f
                ).apply { setPadding(dp(8), 0, 0, 0) }
            )
            listContainer.addView(row)
        }
    }

    private fun renderEmpty(title: String, subtitle: String) {
        listContainer.removeAllViews()
        listContainer.addView(Components.emptyState(ctx, title, subtitle))
    }

    private fun renderError(e: String) {
        listContainer.removeAllViews()
        listContainer.addView(
            Components.emptyState(
                ctx,
                "Не удалось получить данные",
                "Проверьте интернет. $e"
            )
        )
        listContainer.addView(
            Components.text(ctx, "Повторить", 13f, Theme.brandText, bold = true, gravity = Gravity.CENTER).apply {
                setPadding(0, dp(12), 0, 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { refresh() }
            }
        )
    }

    private fun signalCard(ps: PairSignal): View {
        val card = Components.card(ctx, LinearLayout.VERTICAL, 10)
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { onOpenDetail(ps) }

        val r1 = Components.horizontal(ctx)
        r1.gravity = Gravity.CENTER_VERTICAL
        r1.addView(Components.coinBadge(ctx, ps.pair.base, 28))
        r1.addView(
            Components.text(ctx, ps.pair.display, 14f, Theme.textPrimary, bold = true).apply {
                setPadding(dp(10), 0, 0, 0)
            }
        )
        r1.addView(Components.text(ctx, ps.pair.name, 11f, Theme.textSecondary, weight = 1f))
        r1.addView(
            Components.badge(
                ctx, ps.finalSignal.label,
                Theme.signalBg(ps.finalSignal), Theme.signalFg(ps.finalSignal)
            )
        )
        card.addView(r1)

        val r2 = Components.horizontal(ctx)
        r2.gravity = Gravity.CENTER_VERTICAL
        r2.addView(
            Components.text(
                ctx, Theme.price(ps.pair.price), 15f, Theme.textPrimary, mono = true, bold = true, weight = 1f
            )
        )
        val chg = ps.pair.change24h
        r2.addView(
            Components.text(
                ctx, Theme.pct(chg), 12f,
                if (chg >= 0) Theme.greenText else Theme.redText, mono = true, bold = true, gravity = Gravity.END
            )
        )
        card.addView(r2)

        val r3 = Components.horizontal(ctx)
        r3.gravity = Gravity.CENTER_VERTICAL
        r3.addView(
            Components.stateDot(
                ctx, ps.readings.firstOrNull { it.key == "TrendEMA" }?.state ?: IndicatorState.UNKNOWN
            )
        )
        r3.addView(
            Components.text(
                ctx, "RSI ${Theme.num(ps.readings.firstOrNull { it.key == "RSI" }?.value ?: Double.NaN)}",
                11f, Theme.textSecondary, mono = true
            ).apply { setPadding(dp(8), 0, 0, 0) }
        )
        val macd = ps.readings.firstOrNull { it.key == "MACD" }
        r3.addView(
            Components.text(ctx, "MACD ${arrow(macd?.state)}", 11f, Theme.textSecondary).apply {
                setPadding(dp(10), 0, 0, 0)
            }
        )
        val tr = ps.readings.firstOrNull { it.key == "TrendMA" }
        r3.addView(
            Components.text(ctx, "SMA ${arrow(tr?.state)}", 11f, Theme.textSecondary).apply {
                setPadding(dp(10), 0, 0, 0)
            }
        )
        r3.addView(
            Components.text(
                ctx, "${ps.bullish}▲ ${ps.bearish}▼", 11f, Theme.textSecondary, gravity = Gravity.END
            ).apply { setPadding(dp(10), 0, 0, 0) }
        )
        card.addView(r3)

        val r4 = Components.text(
            ctx,
            "Сила сигнала ${ps.confidence}% · балл ${Theme.pct(ps.finalScore * 100.0)} · ${ps.summary}",
            11f, Theme.textMuted
        )
        r4.setPadding(0, dp(6), 0, 0)
        card.addView(r4)
        card.addView(confidenceBar(ps))

        return card
    }

    /** Тонкая полоса уверенности: заполнение = сила сигнала, цвет = направление. */
    private fun confidenceBar(ps: PairSignal): View {
        val track = Components.horizontal(ctx)
        track.setPadding(0, dp(8), 0, 0)
        val filled = View(ctx).apply {
            background = Theme.rounded(Theme.signalColor(ps.finalSignal), dp(3).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, dp(5), ps.confidence.coerceIn(1, 100).toFloat())
        }
        val empty = View(ctx).apply {
            background = Theme.rounded(Theme.cardAlt, dp(3).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, dp(5), (100 - ps.confidence).coerceIn(0, 99).toFloat())
        }
        track.addView(filled)
        if (ps.confidence < 100) track.addView(empty)
        return track
    }

    private fun arrow(st: IndicatorState?): String = when (st) {
        IndicatorState.BULLISH -> "▲"
        IndicatorState.BEARISH -> "▼"
        else -> "–"
    }

    private fun dp(v: Int): Int = Theme.dp(ctx, v)

    private fun nowTime(): String =
        android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date())
}

// ---------------------------------------------------------------------------
// Pairs / watchlist
// ---------------------------------------------------------------------------

class PairsScreen(
    private val ctx: Context,
    private val prefs: Prefs,
    private val universe: () -> List<PairInfo>,
    private val onChanged: () -> Unit
) {
    private lateinit var listContainer: LinearLayout
    private lateinit var search: android.widget.EditText
    private var all: List<PairInfo> = emptyList()

    fun build(): View {
        val root = Components.vertical(ctx)
        root.setPadding(dp(12), dp(10), dp(12), dp(6))

        val titleRow = Components.horizontal(ctx)
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(Components.icon(ctx, R.drawable.ic_star, Theme.brandText, 22))
        titleRow.addView(
            Components.text(ctx, "Пары", 16f, Theme.textPrimary, bold = true).apply {
                setPadding(dp(10), 0, 0, 0)
            }
        )
        titleRow.addView(
            Components.text(ctx, "${prefs.watchlist.size} отслеживается", 12f, Theme.textMuted, gravity = Gravity.CENTER).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
            }
        )
        root.addView(titleRow)

        search = Components.searchField(ctx, "Поиск пары: BTC, ETHUSDT, SOL")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }
            override fun afterTextChanged(s: Editable?) { render(s?.toString() ?: "") }
        })
        root.addView(search)

        val addRow = Components.horizontal(ctx)
        addRow.gravity = Gravity.CENTER_VERTICAL
        val addField = Components.searchField(ctx, "Символ, например BTC")
        addRow.addView(addField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(
            Components.text(ctx, "Добавить", 13f, Theme.textPrimary, bold = true, gravity = Gravity.CENTER).apply {
                setPadding(dp(14), dp(11), dp(14), dp(11))
                isClickable = true
                isFocusable = true
                setOnClickListener { addFromInput(addField.text.toString()) }
                background = Theme.ripple(ctx, Theme.cardAlt, 12f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        )
        root.addView(addRow)

        val sv = ScrollView(ctx).apply { isFillViewport = true }
        listContainer = Components.vertical(ctx)
        sv.addView(listContainer)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    fun onResume() {
        all = universe()
        render(search.text.toString())
    }

    private fun addFromInput(raw: String) {
        val ticker = raw.trim().uppercase()
        if (ticker.isEmpty()) return
        if (prefs.watchlist.contains(ticker)) {
            toast("Already in the list")
            return
        }
        if (all.none { it.ticker == ticker }) {
            toast("Пара не найдена в каталоге")
            return
        }
        val next = prefs.watchlist.toMutableSet()
        next.add(ticker)
        prefs.watchlist = next
        onChanged()
        toast("Добавлено: $ticker")
        render(search.text.toString())
    }

    private fun render(query: String) {
        val q = query.trim().uppercase()
        val watched = prefs.watchlist
        val pool = if (q.isEmpty()) all else
            all.filter {
                it.ticker.contains(q) || it.base.contains(q) ||
                    it.name.uppercase().contains(q) || it.quote.contains(q)
            }
        val watchedRows = pool.filter { it.ticker in watched }
        val otherRows = pool.filter { it.ticker !in watched }

        listContainer.removeAllViews()
        if (watchedRows.isNotEmpty()) {
            listContainer.addView(Components.sectionTitle(ctx, "Отслеживается (${watchedRows.size}) · нажмите, чтобы убрать"))
            watchedRows.groupBy { it.kind }.forEach { (kind, rows) ->
                listContainer.addView(
                    Components.sectionTitle(
                        ctx,
                        if (kind == PairKind.FOREX) "Валютные пары · Olymp Trade (без OTC)" else "Криптовалюты"
                    )
                )
                rows.forEach { listContainer.addView(pairRow(it, true)) }
            }
        }
        if (otherRows.isNotEmpty()) {
            listContainer.addView(Components.sectionTitle(ctx, "Доступные пары (${otherRows.size}) · нажмите, чтобы добавить"))
            otherRows.groupBy { it.kind }.forEach { (kind, rows) ->
                listContainer.addView(
                    Components.sectionTitle(
                        ctx,
                        if (kind == PairKind.FOREX) "Валютные пары · Olymp Trade (без OTC)" else "Криптовалюты"
                    )
                )
                rows.forEach { listContainer.addView(pairRow(it, false)) }
            }
        }
        if (pool.isEmpty()) {
            listContainer.addView(Components.emptyState(ctx, "Ничего не найдено", "Измените запрос поиска"))
        }
    }

    private fun pairRow(p: PairInfo, watched: Boolean): View {
        val card = Components.card(ctx, LinearLayout.HORIZONTAL, 9)
        card.gravity = Gravity.CENTER_VERTICAL
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener {
            val next = prefs.watchlist.toMutableSet()
            if (watched) next.remove(p.ticker) else next.add(p.ticker)
            prefs.watchlist = next
            onChanged()
            render(search.text.toString())
        }
        card.addView(
            Components.icon(
                ctx, R.drawable.ic_star,
                if (watched) Theme.amberText else Theme.textMuted, 20
            )
        )
        card.addView(Components.coinBadge(ctx, p.base, 28))
        card.addView(
            Components.text(ctx, p.display, 14f, Theme.textPrimary, bold = true).apply {
                setPadding(dp(10), 0, 0, 0)
            }
        )
        card.addView(Components.text(ctx, p.name, 12f, Theme.textSecondary, weight = 0.5f))
        // У валютной пары котировка уже в названии (EUR/USD), у криптовалюты
        // показываем, что цена — в долларах.
        card.addView(
            Components.text(
                ctx, if (p.kind == PairKind.FOREX) "FX" else "$",
                11f, Theme.textMuted, gravity = Gravity.END
            ).apply {
                setPadding(dp(6), 0, 0, 0)
            }
        )
        card.addView(
            Components.text(
                ctx, if (p.price.isFinite()) Theme.price(p.price) else "—",
                13f, Theme.textSecondary, mono = true, gravity = Gravity.END
            ).apply { setPadding(dp(10), 0, 0, 0) }
        )
        return card
    }

    private fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = Theme.dp(ctx, v)
}

// ---------------------------------------------------------------------------
// Detail
// ---------------------------------------------------------------------------

class DetailScreen(
    private val ctx: Context,
    private val ps: PairSignal,
    private val onBack: () -> Unit
) {
    fun build(): View {
        val root = Components.vertical(ctx)
        root.setPadding(dp(12), dp(10), dp(12), dp(20))
        root.setBackgroundColor(Theme.bg)

        val top = Components.horizontal(ctx)
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(
            Components.iconButton(ctx, R.drawable.ic_back, Theme.textPrimary, 20) { onBack() }
        )
        top.addView(
            Components.text(ctx, ps.pair.display, 17f, Theme.textPrimary, bold = true).apply {
                setPadding(dp(6), 0, 0, 0)
            }
        )
        root.addView(top)

        root.addView(
            Components.text(
                ctx,
                if (ps.pair.kind == PairKind.FOREX)
                    "Валютная пара · ${ps.pair.name}"
                else
                    "Криптовалюта · цена в долларах (USDT)",
                13f, Theme.textSecondary
            ).apply {
                setPadding(0, dp(2), 0, dp(12))
            }
        )

        val pr = Components.horizontal(ctx)
        pr.gravity = Gravity.CENTER_VERTICAL
        pr.addView(
            Components.text(
                ctx, Theme.price(ps.pair.price), 24f, Theme.textPrimary, mono = true, bold = true, weight = 1f
            )
        )
        val chg = ps.pair.change24h
        pr.addView(
            Components.badge(
                ctx, Theme.pct(chg),
                if (chg >= 0) 0xFF0E2A22.toInt() else 0xFF2A1416.toInt(),
                if (chg >= 0) Theme.greenText else Theme.redText, sizeSp = 13f
            )
        )
        root.addView(pr)

        val sig = Components.card(ctx, LinearLayout.HORIZONTAL, 14)
        sig.gravity = Gravity.CENTER_VERTICAL
        sig.addView(
            Components.badge(
                ctx, ps.finalSignal.label,
                Theme.signalBg(ps.finalSignal), Theme.signalFg(ps.finalSignal), sizeSp = 13f, padH = 14, padV = 8
            )
        )
        sig.addView(Components.text(ctx, "TradingView", 12f, Theme.textMuted).apply { setPadding(dp(14), 0, 0, 0) })
        sig.addView(
            Components.text(ctx, tvText(ps.tvScore), 15f, Theme.textPrimary, mono = true, bold = true).apply {
                setPadding(dp(6), 0, 0, 0)
            }
        )
        sig.addView(Components.text(ctx, "Анализ", 12f, Theme.textMuted).apply { setPadding(dp(14), 0, 0, 0) })
        sig.addView(
            Components.text(ctx, ps.localSignal.label, 13f, Theme.textPrimary, bold = true).apply {
                setPadding(dp(6), 0, 0, 0)
            }
        )
        root.addView(sig)

        root.addView(
            Components.text(ctx, ps.summary, 12f, Theme.textSecondary).apply {
                setPadding(0, dp(12), 0, dp(4))
            }
        )
        root.addView(Components.divider(ctx))

        root.addView(Components.sectionTitle(ctx, "Индикаторы · ${ps.timeframe.label}"))
        for (rd in ps.readings) root.addView(indicatorRow(rd))

        return root
    }

    private fun indicatorRow(rd: IndicatorReading): View {
        val row = Components.card(ctx, LinearLayout.HORIZONTAL, 12)
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(Components.stateDot(ctx, rd.state))
        row.addView(
            Components.text(
                ctx, rd.label, 13f, Theme.stateColor(rd.state),
                bold = rd.state != IndicatorState.UNKNOWN
            ).apply { setPadding(dp(10), 0, 0, 0) }
        )
        row.addView(
            Components.text(
                ctx, rd.value?.let { Theme.num(it, 2) } ?: "—",
                13f, Theme.textPrimary, mono = true, gravity = Gravity.END, weight = 1f
            )
        )
        if (rd.note.isNotEmpty()) {
            row.addView(
                Components.text(ctx, rd.note, 11f, Theme.textMuted, gravity = Gravity.END).apply {
                    setPadding(dp(8), 0, 0, 0)
                }
            )
        }
        return row
    }

    private fun tvText(v: Double): String = if (v.isFinite()) "${(v * 100).toInt()}%" else "—"

    private fun dp(v: Int): Int = Theme.dp(ctx, v)
}
