package app.teron.signals.core

/** Supported TradingView scanner timeframes (minute bars + D/W/M). */
enum class Timeframe(val id: String, val label: String, val tv: String) {
    M1("1m", "1мин", "1"),
    M5("5m", "5мин", "5"),
    M15("15m", "15мин", "15"),
    M30("30m", "30мин", "30"),
    H1("1h", "1ч", "60"),
    H2("2h", "2ч", "120"),
    H4("4h", "4ч", "240"),
    D1("1d", "1Д", "1D"),
    W1("1w", "1Н", "1W"),
    MN1("1M", "1Мес", "1M");

    companion object {
        val DEFAULT: Timeframe = D1
        fun fromId(id: String?): Timeframe = values().firstOrNull { it.id == id } ?: DEFAULT
    }
}

enum class Signal(val label: String, val short: String) {
    STRONG_BUY("Сильный BUY", "SB"),
    BUY("BUY", "B"),
    HOLD("HOLD", "H"),
    SELL("SELL", "S"),
    STRONG_SELL("Сильный SELL", "SS");

    companion object {
        /** TradingView convention: -1 Strong Sell .. +1 Strong Buy. */
        fun fromScore(score: Double): Signal = when {
            score >= 0.6 -> STRONG_BUY
            score >= 0.2 -> BUY
            score > -0.2 -> HOLD
            score > -0.6 -> SELL
            else -> STRONG_SELL
        }
    }
}

enum class IndicatorState(val weight: Double) {
    BULLISH(1.0),
    BEARISH(-1.0),
    NEUTRAL(0.0),
    UNKNOWN(0.0);
}

data class IndicatorReading(
    val key: String,
    val label: String,
    val value: Double?,
    val state: IndicatorState,
    val note: String = ""
)

enum class PairKind { CRYPTO, FOREX }

data class PairInfo(
    val symbol: String,
    val ticker: String,
    val base: String,
    val quote: String,
    val name: String,
    val price: Double,
    val change24h: Double,
    val volume: Double,
    val marketCap: Double,
    val rank: Int,
    val kind: PairKind = PairKind.CRYPTO
) {
    /**
     * Как инструмент называется на экране:
     * - криптовалюта — сама монета отдельно: `BTC`, `ETH`, `SOL`…
     *   (сигнал считается по монете к доллару, но монета — отдельный актив);
     * - валютная пара — именно пара: `EUR/USD`, `GBP/USD`…
     */
    val display: String
        get() = when (kind) {
            PairKind.FOREX -> if (quote.isEmpty()) base else "$base/$quote"
            PairKind.CRYPTO -> base
        }
}

data class PairSignal(
    val pair: PairInfo,
    val timeframe: Timeframe,
    val tvScore: Double,
    val tvSignal: Signal,
    val localScore: Double,
    val localSignal: Signal,
    /** Итоговый взвешенный балл −1..+1 (0.6·TradingView + 0.4·локальный анализ). */
    val finalScore: Double,
    /** Сила сигнала 0..100: насколько единодушны индикаторы и рейтинг TV. */
    val confidence: Int,
    val finalSignal: Signal,
    val readings: List<IndicatorReading>,
    val bullish: Int,
    val bearish: Int,
    val neutral: Int,
    val summary: String
)
