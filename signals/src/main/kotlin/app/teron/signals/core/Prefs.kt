package app.teron.signals.core

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("teron_signals", Context.MODE_PRIVATE)

    var timeframe: String
        get() = sp.getString(KEY_TF, Timeframe.DEFAULT.id) ?: Timeframe.DEFAULT.id
        set(value) = sp.edit().putString(KEY_TF, value).apply()

    var watchlist: Set<String>
        get() = sp.getStringSet(KEY_WATCH, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_WATCH, value).apply()

    var market: String
        get() = sp.getString(KEY_MARKET, "") ?: ""
        set(value) = sp.edit().putString(KEY_MARKET, value).apply()

    fun hasWatchlist(): Boolean = sp.contains(KEY_WATCH)

    private companion object {
        const val KEY_TF = "timeframe"
        const val KEY_WATCH = "watchlist"
        const val KEY_MARKET = "market"
    }
}
