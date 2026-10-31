package app.teron.signals

import android.app.Application
import app.teron.signals.core.PairInfo
import app.teron.signals.core.Prefs
import app.teron.signals.data.TradingViewApi

class SignalsApp : Application() {

    val prefs: Prefs by lazy { Prefs(this) }

    @Volatile
    var universe: List<PairInfo> = emptyList()
        private set

    fun boot(onReady: () -> Unit, onError: (String) -> Unit) {
        TradingViewApi.discoverPairs(
            onResult = { crypto ->
                // Каталог = криптовалюты со сканнера + статичные форекс-пары.
                universe = crypto + TradingViewApi.FOREX_PAIRS
                seedDefaults(universe)
                onReady()
            },
            onError = onError
        )
    }

    /**
     * Базовый набор, который пользователь видит сразу:
     * главные криптовалюты (BTC, ETH, SOL, LTC, BCH) + топ-5 валютных пар
     * Olymp Trade без OTC + самые ликвидные крипто-пары до 14 штук.
     * Для уже установленных версий набор добавляется один раз (флаг в Prefs),
     * чтобы не возвращать пары, которые пользователь сам убрал.
     */
    private fun seedDefaults(list: List<PairInfo>) {
        val core = TradingViewApi.CORE_CRYPTO_TICKERS + TradingViewApi.FOREX_PAIRS.map { it.ticker }
        val available = list.map { it.ticker }.toHashSet()
        val coreAvailable = core.filter { it in available }

        if (!prefs.hasWatchlist()) {
            val extra = list.filter { it.ticker !in coreAvailable }.take(9).map { it.ticker }
            prefs.watchlist = (coreAvailable + extra).toHashSet()
            prefs.coreSeeded = true
            return
        }
        if (!prefs.coreSeeded) {
            val next = prefs.watchlist.toMutableSet()
            next.addAll(coreAvailable)
            prefs.watchlist = next
            prefs.coreSeeded = true
        }
    }
}
