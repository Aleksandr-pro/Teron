package app.teron.signals.core

/**
 * Turns a flat map of TradingView scanner indicator fields into a readable signal.
 *
 * The headline signal blends TradingView's own composite technical rating
 * (`Recommend.All`, -1..1) with a local aggregation of the individual
 * oscillator / moving-average readings.
 */
object SignalEngine {

    fun build(pair: PairInfo, tf: Timeframe, map: Map<String, Double?>): PairSignal {
        val suffix = if (tf.tv == "1D") "" else "|" + tf.tv
        val readings = evaluate(map, pair.price)

        var bull = 0
        var bear = 0
        var neu = 0
        for (r in readings) {
            when (r.state) {
                IndicatorState.BULLISH -> bull++
                IndicatorState.BEARISH -> bear++
                IndicatorState.NEUTRAL -> neu++
                IndicatorState.UNKNOWN -> { }
            }
        }

        val localScore = readings.asSequence()
            .filter { it.state != IndicatorState.UNKNOWN }
            .map { it.state.weight }
            .average()
            .takeIf { it.isFinite() } ?: 0.0

        val tvRaw = map["Recommend.All$suffix"] ?: map["Recommend.All"]
        val tvScore = tvRaw?.takeIf { it.isFinite() }
        val tvSignal = tvScore?.let { Signal.fromScore(it) } ?: Signal.HOLD

        val finalScore = if (tvScore == null) localScore else tvScore * 0.6 + localScore * 0.4

        return PairSignal(
            pair = pair,
            timeframe = tf,
            tvScore = tvScore ?: Double.NaN,
            tvSignal = tvSignal,
            localScore = localScore,
            localSignal = Signal.fromScore(localScore),
            finalSignal = Signal.fromScore(finalScore),
            readings = readings,
            bullish = bull,
            bearish = bear,
            neutral = neu,
            summary = summarize(pair, tvScore, readings, bull, bear)
        )
    }

    private fun evaluate(map: Map<String, Double?>, close: Double): List<IndicatorReading> {
        val out = ArrayList<IndicatorReading>()
        fun d(key: String): Double? = map[key]

        out.add(reading("RSI", "RSI", d("RSI"),
            bull = { it <= 30 }, bear = { it >= 70 },
            bullNote = "перепродано", bearNote = "перекуплено"))

        val macdSig = d("MACD.signal")
        out.add(reading("MACD", "MACD", d("MACD.macd"),
            bull = { macdSig != null && it > macdSig },
            bear = { macdSig != null && it < macdSig }))

        val stochD = d("Stoch.D")
        out.add(reading("Stoch", "Стохастик", d("Stoch.K"),
            bull = { k -> stochD != null && k > stochD },
            bear = { k -> stochD != null && k < stochD }))

        out.add(reading("StochRSI", "Stoch RSI", d("Stoch.RSI.K"),
            bull = { it <= 20 }, bear = { it >= 80 }))
        out.add(reading("CCI", "CCI 20", d("CCI20"), bull = { it >= 100 }, bear = { it <= -100 }))

        val adx = d("ADX")
        val diP = d("ADX+DI")
        val diM = d("ADX-DI")
        out.add(reading("ADX", "ADX (сила тренда)", adx,
            bull = { it >= 25 && diP != null && diM != null && diP > diM },
            bear = { it >= 25 && diP != null && diM != null && diP < diM }))

        out.add(reading("AO", "Awesome Oscillator", d("AO"), bull = { it > 0 }, bear = { it < 0 }))
        out.add(reading("Mom", "Моментум", d("Mom"), bull = { it > 0 }, bear = { it < 0 }))
        out.add(reading("WR", "Williams %R", d("W.R"), bull = { it <= -80 }, bear = { it >= -20 }))
        out.add(reading("UO", "Ultimate Oscillator", d("UO"), bull = { it >= 50 }, bear = { it <= 30 }))
        out.add(reading("BBPower", "BB Power", d("BBPower"), bull = { it > 0 }, bear = { it < 0 }))

        val emaList = listOf(d("EMA20"), d("EMA50"), d("EMA100"), d("EMA200"))
        val present = emaList.filterNotNull()
        val above = present.count { close > it }
        val trendState = when {
            present.isEmpty() -> IndicatorState.UNKNOWN
            above * 2 >= present.size + 1 -> IndicatorState.BULLISH
            above * 2 <= present.size - 1 -> IndicatorState.BEARISH
            else -> IndicatorState.NEUTRAL
        }
        out.add(IndicatorReading(
            "TrendEMA", "Цена vs EMA", if (present.isEmpty()) null else close,
            trendState, "${above}/${present.size} выше EMA"
        ))

        val sma50 = d("SMA50")
        val sma200 = d("SMA200")
        val maState = when {
            sma50 != null && sma200 != null ->
                if (sma50 > sma200) IndicatorState.BULLISH else IndicatorState.BEARISH
            else -> IndicatorState.UNKNOWN
        }
        out.add(IndicatorReading(
            "TrendMA", "SMA 50 / 200", if (sma50 != null && sma200 != null) sma50 else null,
            maState, "тренд MA"
        ))

        out.add(reading("Ichimoku", "Ichimoku Cloud", d("Ichimoku.BLine"),
            bull = { close > it }, bear = { close < it }))
        out.add(reading("VWMA", "VWMA", d("VWMA"), bull = { close > it }, bear = { close < it }))
        out.add(reading("HullMA", "Hull MA 9", d("HullMA9"), bull = { close > it }, bear = { close < it }))

        return out
    }

    private fun reading(
        key: String,
        label: String,
        value: Double?,
        bull: (Double) -> Boolean,
        bear: (Double) -> Boolean,
        bullNote: String = "",
        bearNote: String = ""
    ): IndicatorReading {
        val state = when {
            value == null || !value.isFinite() -> IndicatorState.UNKNOWN
            bull(value) -> IndicatorState.BULLISH
            bear(value) -> IndicatorState.BEARISH
            else -> IndicatorState.NEUTRAL
        }
        val note = when (state) {
            IndicatorState.BULLISH -> bullNote
            IndicatorState.BEARISH -> bearNote
            else -> ""
        }
        return IndicatorReading(key, label, value, state, note)
    }

    private fun summarize(
        pair: PairInfo,
        tvScore: Double?,
        readings: List<IndicatorReading>,
        bull: Int,
        bear: Int
    ): String {
        val trend = readings.firstOrNull { it.key == "TrendEMA" }?.state
        val trendText = when (trend) {
            IndicatorState.BULLISH -> "тренд бычий"
            IndicatorState.BEARISH -> "тренд медвежий"
            IndicatorState.NEUTRAL -> "тренд нейтрален"
            else -> "тренд неопределён"
        }
        val rsi = readings.firstOrNull { it.key == "RSI" }?.value
        val rsiText = if (rsi != null && rsi.isFinite()) ", RSI ${Theme.price(rsi)}" else ""
        val tvText = if (tvScore != null && tvScore.isFinite())
            ", TradingView ${Theme.pct(tvScore * 100.0)}" else ""
        return "$trendText$rsiText$tvText. Индикаторов: $bull бычих / $bear медвежих."
    }
}
