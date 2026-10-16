package com.nate.scoreline

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * FanDuel lines from The Odds API (the-odds-api.com). Free plan = 500 credits/month and each
 * league refresh costs 3 (moneyline + spread + total), so results are cached for 3 hours in
 * memory and on disk, and automatic refreshes stop when credits run low.
 */
object FanDuelOdds {
    private const val TTL_MS = 3 * 60 * 60 * 1000L
    private const val LOW_CREDITS = 15
    private const val PREFS = "fanduel_odds"

    data class Book(val events: List<OddsEvent>, val fetchedAt: Long)

    private val mem = ConcurrentHashMap<League, Book>()

    /** Shown in Settings. */
    var creditsLeft by mutableStateOf<Int?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var lastFetch by mutableStateOf(0L)
        private set

    private fun sport(l: League) = when (l) {
        League.NFL -> "americanfootball_nfl"
        League.CFB -> "americanfootball_ncaaf"
        League.NHL -> "icehockey_nhl"
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context) {
        val p = prefs(c)
        if (p.contains("credits")) creditsLeft = p.getInt("credits", 0)
        lastFetch = p.getLong("last_fetch", 0L)
    }

    /** Cached lines if fresh; otherwise fetches (unless credits are low and [force] is false). Null when no key is set. */
    suspend fun get(c: Context, league: League, force: Boolean = false): Book? = withContext(Dispatchers.IO) {
        val key = Favorites.get(c).oddsApiKey
        if (key.isBlank()) return@withContext null
        val now = System.currentTimeMillis()
        mem[league]?.let { if (!force && now - it.fetchedAt < TTL_MS) return@withContext it }
        val p = prefs(c)
        val cachedText = p.getString("body_${league.name}", null)
        val cachedAt = p.getLong("at_${league.name}", 0L)
        val cached = cachedText?.let { runCatching { Book(OddsParse.oddsApi(JSONArray(it)), cachedAt) }.getOrNull() }
        if (cached != null && !force && now - cachedAt < TTL_MS) return@withContext cached.also { mem[league] = it }
        if (!force && (creditsLeft ?: Int.MAX_VALUE) < LOW_CREDITS) return@withContext cached // save the last few credits for manual refreshes

        val url = "https://api.the-odds-api.com/v4/sports/${sport(league)}/odds/?apiKey=" +
            URLEncoder.encode(key, "UTF-8") + "&bookmakers=fanduel&markets=h2h,spreads,totals&oddsFormat=american"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        try {
            val code = conn.responseCode
            conn.getHeaderField("x-requests-remaining")?.toDoubleOrNull()?.toInt()?.let {
                creditsLeft = it
                p.edit().putInt("credits", it).apply()
            }
            if (code == 401) throw IOException("Odds API key wasn't accepted")
            if (code == 429) throw IOException("Odds API monthly credits used up")
            if (code !in 200..299) throw IOException("Odds API error $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val book = Book(OddsParse.oddsApi(JSONArray(text)), now)
            p.edit().putString("body_${league.name}", text).putLong("at_${league.name}", now).putLong("last_fetch", now).apply()
            mem[league] = book
            lastFetch = now
            lastError = null
            book
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            cached // fall back to the last lines we had
        } finally {
            conn.disconnect()
        }
    }

    fun clear(c: Context) {
        mem.clear()
        prefs(c).edit().clear().apply()
        creditsLeft = null
        lastError = null
        lastFetch = 0L
    }
}
