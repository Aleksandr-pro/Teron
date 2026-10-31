package app.teron.signals.core

/** Supported TradingView scanner timeframes (minute bars + D/W/M). */
enum class Timeframe(val id: String, val label: String, val tv: String) {
    M1("1m", "1M", "1"),
    M5("5m", "5M", "5"),
    M15("15m", "15M", "15"),
    M30("30m", "30M", "30"),
    H1("1h", "1H", "60"),
    H2("2h", "2H", "120"),
    H4("4h", "4H", "240"),
    D1("1d", "1D", "1D"),
    W1("1w", "1W", "1W"),
    MN1("1M", "1M", "1M");

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
    val rank: Int
)

data class PairSignal(
    val pair: PairInfo,
    val timeframe: Timeframe,
    val tvScore: Double,
    val tvSignal: Signal,
    val localScore: Double,
    val localSignal: Signal,
    val finalSignal: Signal,
    val readings: List<IndicatorReading>,
    val bullish: Int,
    val bearish: Int,
    val neutral: Int,
    val summary: String
)
