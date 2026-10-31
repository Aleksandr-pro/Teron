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
            onResult = { list ->
                universe = list
                seedDefaults(list)
                onReady()
            },
            onError = onError
        )
    }

    /** First run: follow the most liquid pairs so the app is useful immediately. */
    private fun seedDefaults(list: List<PairInfo>) {
        if (!prefs.hasWatchlist() && list.isNotEmpty()) {
            prefs.watchlist = list.take(14).map { it.ticker }.toHashSet()
        }
    }
}
