package app.teron.signals.data

import app.teron.signals.core.PairInfo
import app.teron.signals.core.PairKind
import app.teron.signals.core.PairSignal
import app.teron.signals.core.SignalEngine
import app.teron.signals.core.Timeframe
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client for TradingView's public (anonymous, key-free) screener endpoints.
 *
 *   POST https://scanner.tradingview.com/{market}/scan
 *
 * `{market}` is tried in order (CEX pairs first, aggregated crypto second) so the
 * app keeps working if one of the markets is unavailable. No API key, no login.
 */
object TradingViewApi {

    private const val SCANNER = "https://scanner.tradingview.com"
    private val MARKETS = listOf("cex", "crypto")
    private const val FOREX_MARKET = "forex"

    /** Главные криптовалюты: всегда в сигналах по умолчанию. */
    val CORE_CRYPTO_TICKERS = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "LTCUSDT", "BCHUSDT")

    /**
     * Топ-5 самых популярных валютных пар Olymp Trade на реальном рынке
     * (без метки OTC). Котировки и теханализ — форекс-сканнер TradingView
     * (межбанковский рынок, символы FX:...), а не внебиржевой OTC-фид брокера.
     */
    val FOREX_PAIRS: List<PairInfo> = listOf(
        forexPair("EURUSD", "EUR", "USD", "Евро / доллар США"),
        forexPair("GBPUSD", "GBP", "USD", "Фунт / доллар США"),
        forexPair("USDJPY", "USD", "JPY", "Доллар США / иена"),
        forexPair("AUDUSD", "AUD", "USD", "Австрал. доллар / доллар США"),
        forexPair("USDCAD", "USD", "CAD", "Доллар США / канад. доллар")
    )

    private fun forexPair(ticker: String, base: String, quote: String, name: String) = PairInfo(
        symbol = "FX:$ticker", ticker = ticker, base = base, quote = quote, name = name,
        price = Double.NaN, change24h = Double.NaN, volume = Double.NaN,
        marketCap = Double.NaN, rank = Int.MAX_VALUE, kind = PairKind.FOREX
    )
    private const val UNIVERSITY_SIZE = 220

    private val BASE_COLUMNS = listOf(
        "name", "description", "close", "change", "volume",
        "market_cap_calc", "market_cap_basic", "crypto_total_rank"
    )

    private val STABLES = setOf(
        "USDT", "USDC", "BUSD", "TUSD", "USDP", "DAI", "USD", "USTC", "FUSD",
        "USDD", "SUSD", "HUSD", "TETHER", "USDH", "PAX", "GUSD", "BETH"
    )

    private val QUOTES = setOf(
        "USDT", "USDC", "BUSD", "TUSD", "USDP", "DAI", "USD", "EUR", "TRY", "RUB",
        "JPY", "GBP", "AUD", "CAD", "CHF", "HKD", "BTC", "ETH", "BNB", "SOL",
        "XRP", "ADA", "DOGE", "DOT", "POL", "TON", "TRX", "LTC", "AVAX", "LINK",
        "BCH", "NEAR", "APT", "SUI", "SEI", "FIL", "ARB", "OP", "INJ", "WIF",
        "PEPE", "HBAR", "VET", "ICP", "ATOM", "UNI", "AAVE", "XTZ", "EOS",
        "FLOW", "SAND", "MANA", "AXS", "GALA", "RUNE", "STX", "EGLD", "APE",
        "GMT", "CRV", "SUSHI", "ENS", "CFX", "TIA", "JUP", "PYTH", "ORDI", "KAS"
    )

    private val DEX_PREFIXES = setOf(
        "BASE", "UNISWAP", "PANCAKESWAP", "PCS", "SUSHISWAP", "CURVE",
        "BALANCER", "OSMOSIS", "JUNO", "UMI", "HYDRA", "GMX", "VELO", "ZEROX",
        "PULSE", "MODE", "LINEA", "HYPE"
    )

    /** Technical columns for a timeframe; TradingView suffixes fields with `|<tf>`. */
    fun techColumns(tf: Timeframe): List<String> {
        val s = if (tf.tv == "1D") "" else "|" + tf.tv
        val base = listOf(
            "RSI", "MACD.macd", "MACD.signal", "Stoch.K", "Stoch.D", "Stoch.RSI.K",
            "CCI20", "ADX", "ADX+DI", "ADX-DI", "AO", "Mom", "W.R", "UO", "BBPower",
            "EMA5", "EMA10", "EMA20", "EMA30", "EMA50", "EMA100", "EMA200",
            "SMA5", "SMA10", "SMA20", "SMA30", "SMA50", "SMA100", "SMA200",
            "Ichimoku.BLine", "VWMA", "HullMA9",
            "Rec.Stoch.RSI", "Rec.WR", "Rec.BBPower", "Rec.UO",
            "Rec.Ichimoku", "Rec.VWMA", "Rec.HullMA9"
        )
        val out = ArrayList<String>(base.size + 2)
        for (c in base) out.add(c + s)
        out.add("Recommend.All")       // daily fallback
        if (s.isNotEmpty()) out.add("Recommend.All$s")
        return out
    }

    // ---------------------------------------------------------------- universe

    fun discoverPairs(onResult: (List<PairInfo>) -> Unit, onError: (String) -> Unit) {
        Http.background<List<PairInfo>>(
            block = {
                var best: List<PairInfo>? = null
                var err = "Сканер не вернул данных"
                for (market in MARKETS) {
                    try {
                        val body = JSONObject().apply {
                            put("filter", JSONArray())
                            put("options", JSONObject().apply { put("lang", "en") })
                            put("columns", JSONArray(BASE_COLUMNS))
                            put("range", JSONArray().put(0).put(UNIVERSITY_SIZE))
                        }
                        val raw = Http.postJson("$SCANNER/$market/scan", body.toString())
                        val parsed = parseRows(raw, BASE_COLUMNS)
                        if (parsed.isNotEmpty()) {
                            best = normalize(parsed)
                            break
                        }
                        err = "Сканер $market вернул пустой ответ"
                    } catch (t: Throwable) {
                        err = t.message ?: t.javaClass.simpleName
                    }
                }
                best ?: throw RuntimeException(err)
            },
            onResult = { r -> if (r != null) onResult(r) else onError("Не удалось получить список пар") },
            onError = { onError(it.message ?: "Ошибка сети") }
        )
    }

    // ----------------------------------------------------------------- signals

    fun fetchSignals(
        pairs: List<PairInfo>,
        tf: Timeframe,
        onResult: (List<PairSignal>) -> Unit,
        onError: (String) -> Unit
    ) {
        val columns = BASE_COLUMNS + techColumns(tf)
        Http.background<List<PairSignal>>(
            block = {
                // Крипта и форекс живут в разных рынках сканнера — запрашиваем
                // группами и сливаем результат в один список.
                val out = ArrayList<PairSignal>()
                var lastErr = "Сканер не вернул данных"
                val groups: List<Pair<List<String>, List<PairInfo>>> = listOf(
                    MARKETS to pairs.filter { it.kind == PairKind.CRYPTO },
                    listOf(FOREX_MARKET) to pairs.filter { it.kind == PairKind.FOREX }
                )
                for ((markets, group) in groups) {
                    if (group.isEmpty()) continue
                    var got = false
                    for (market in markets) {
                        try {
                            val parsed = scanSignals(market, group, columns, tf)
                            if (parsed.isNotEmpty()) {
                                out.addAll(parsed)
                                got = true
                                break
                            }
                            lastErr = "Сканер $market вернул пустой ответ"
                        } catch (t: Throwable) {
                            lastErr = t.message ?: t.javaClass.simpleName
                        }
                    }
                    if (!got && out.isEmpty()) {
                        // Группа не ответила, но вторая ещё может — не падаем раньше времени.
                        continue
                    }
                }
                if (out.isEmpty()) throw RuntimeException(lastErr)
                out.sortedByDescending {
                    it.tvScore.takeIf { v -> v.isFinite() } ?: Double.NEGATIVE_INFINITY
                }
            },
            onResult = { r -> if (r != null) onResult(r) else onError("Не удалось получить сигналы") },
            onError = { onError(it.message ?: "Ошибка сети") }
        )
    }

    private fun scanSignals(
        market: String,
        pairs: List<PairInfo>,
        columns: List<String>,
        tf: Timeframe
    ): List<PairSignal> {
        val tickers = JSONArray()
        for (p in pairs) tickers.put(p.symbol)
        val body = JSONObject().apply {
            put("symbols", JSONObject().apply {
                put("tickers", tickers)
                put("query", JSONObject().apply { put("types", JSONArray()) })
            })
            put("columns", JSONArray(columns))
        }
        val raw = Http.postJson("$SCANNER/$market/scan", body.toString())
        return parseSignalRows(raw, columns, pairs, tf)
    }

    /** Builds a placeholder pair for a ticker the user added that is not in the universe. */
    fun buildPair(ticker: String, exchange: String = "BINANCE"): PairInfo? {
        val up = ticker.trim().uppercase()
        if (up.isEmpty()) return null
        val (base, quote) = splitPair(up)
        val baseCoin = if (base.isEmpty()) up else base
        val quoteCoin = if (quote.isEmpty()) "USDT" else quote
        if (baseCoin in STABLES) return null
        val fullTicker = baseCoin + quoteCoin
        return PairInfo(
            symbol = "$exchange:$fullTicker",
            ticker = fullTicker,
            base = baseCoin,
            quote = quoteCoin,
            name = baseCoin,
            price = Double.NaN,
            change24h = Double.NaN,
            volume = Double.NaN,
            marketCap = Double.NaN,
            rank = Int.MAX_VALUE
        )
    }

    // ------------------------------------------------------- test/debug hooks

    /** Parses a raw scanner universe response into normalized pairs. */
    fun parseUniverse(raw: String): List<PairInfo> = normalize(parseRows(raw, BASE_COLUMNS))

    /** Parses a raw scanner signal response into per-pair signals. */
    fun parseSignalsFor(raw: String, pairs: List<PairInfo>, tf: Timeframe): List<PairSignal> =
        parseSignalRows(raw, BASE_COLUMNS + techColumns(tf), pairs, tf)

    // ----------------------------------------------------------------- parsing

    private fun parseRows(raw: String, columns: List<String>): List<PairInfo> {
        val root = JSONObject(raw)
        val data = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<PairInfo>(data.length())
        for (i in 0 until data.length()) {
            val row = data.optJSONObject(i) ?: continue
            val symbol = row.optString("s", "").trim()
            if (symbol.isEmpty()) continue
            val d = row.optJSONArray("d") ?: continue
            out.add(toPair(symbol, rowValues(d, columns)))
        }
        return out
    }

    private fun parseSignalRows(
        raw: String,
        columns: List<String>,
        pairs: List<PairInfo>,
        tf: Timeframe
    ): List<PairSignal> {
        val root = JSONObject(raw)
        val data = root.optJSONArray("data") ?: return emptyList()
        val bySymbol = pairs.associateBy { it.symbol }
        val byTicker = pairs.associateBy { it.ticker }
        val out = ArrayList<PairSignal>(data.length())
        for (i in 0 until data.length()) {
            val row = data.optJSONObject(i) ?: continue
            val symbol = row.optString("s", "").trim()
            if (symbol.isEmpty()) continue
            val d = row.optJSONArray("d") ?: continue
            val numMap: Map<String, Double?> = rowValues(d, columns).mapValues { (_, v) -> v as? Double }
            val pair = bySymbol[symbol]
                ?: byTicker[symbol.substringAfter(':', "").uppercase()]
                ?: continue
            val updated = pair.copy(
                price = numMap["close"] ?: pair.price,
                change24h = numMap["change"] ?: pair.change24h,
                volume = numMap["volume"] ?: pair.volume
            )
            out.add(SignalEngine.build(updated, tf, numMap))
        }
        return out.sortedByDescending {
            it.tvScore.takeIf { v -> v.isFinite() } ?: Double.NEGATIVE_INFINITY
        }
    }

    private fun toPair(symbol: String, map: Map<String, Any?>): PairInfo {
        val parts = symbol.split(":")
        val ticker = parts.getOrElse(1) { symbol }.uppercase()
        val (base, quote) = splitPair(ticker)
        val cleaned = (map["name"] as? String)?.substringBefore('/')?.trim().orEmpty()
        val name = if (cleaned.isEmpty()) base else cleaned
        return PairInfo(
            symbol = symbol,
            ticker = ticker,
            base = base,
            quote = quote,
            name = name,
            price = num(map, "close") ?: Double.NaN,
            change24h = num(map, "change") ?: Double.NaN,
            volume = num(map, "volume") ?: Double.NaN,
            marketCap = num(map, "market_cap_calc") ?: num(map, "market_cap_basic") ?: Double.NaN,
            rank = (map["crypto_total_rank"] as? Double)?.toInt() ?: Int.MAX_VALUE
        )
    }

    private fun splitPair(ticker: String): Pair<String, String> {
        val up = ticker.uppercase()
        for (q in QUOTES.sortedByDescending { it.length }) {
            if (up.endsWith(q) && up.length > q.length) return up.removeSuffix(q) to q
        }
        return up to ""
    }

    private fun normalize(rows: List<PairInfo>): List<PairInfo> {
        val seen = LinkedHashMap<String, PairInfo>()
        for (r in rows) {
            if (r.ticker.isEmpty() || r.base.isEmpty() || r.quote.isEmpty()) continue
            val exchange = r.symbol.substringBefore(":")
            if (exchange.uppercase() in DEX_PREFIXES) continue
            if (r.base in STABLES) continue
            if (r.base.equals(r.quote, ignoreCase = true)) continue
            val prev = seen[r.ticker]
            if (prev == null || sortKey(r) > sortKey(prev)) seen[r.ticker] = r
        }
        return seen.values.sortedByDescending { sortKey(it) }
    }

    private fun sortKey(p: PairInfo): Double = when {
        p.marketCap > 0.0 -> p.marketCap
        p.volume > 0.0 -> p.volume
        else -> 0.0
    }

    private fun rowValues(d: JSONArray, columns: List<String>): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>(columns.size)
        for ((idx, col) in columns.withIndex()) {
            if (idx < d.length()) map[col] = cell(d, idx)
        }
        return map
    }

    private fun cell(arr: JSONArray, i: Int): Any? {
        if (i >= arr.length()) return null
        val v = arr.opt(i) ?: return null
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.trim()
            else -> null
        }
    }

    private fun num(map: Map<String, Any?>, key: String): Double? =
        (map[key] as? Double)?.takeIf { it.isFinite() }
}
