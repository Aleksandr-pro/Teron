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

    /** Одноразовая миграция v1.2.0: базовый набор пар (крипта + форекс) добавлен. */
    var coreSeeded: Boolean
        get() = sp.getBoolean(KEY_CORE_SEEDED, false)
        set(value) = sp.edit().putBoolean(KEY_CORE_SEEDED, value).apply()

    private companion object {
        const val KEY_TF = "timeframe"
        const val KEY_WATCH = "watchlist"
        const val KEY_MARKET = "market"
        const val KEY_CORE_SEEDED = "core_seeded_v120"
    }
}
