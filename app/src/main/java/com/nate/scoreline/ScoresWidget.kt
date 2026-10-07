package com.nate.scoreline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Home-screen scores widget. Each placed widget keeps its own league and week. */
open class ScoresWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetCtl.render(context, it) }
        refreshAsync(context, ids.toList())
    }

    override fun onEnabled(context: Context) = WidgetCtl.schedule(context)

    // Background refresh stops only when no Scoreology widget of either size is left.
    override fun onDisabled(context: Context) { if (WidgetCtl.allIds(context).isEmpty()) WidgetCtl.cancel(context) }

    override fun onDeleted(context: Context, ids: IntArray) = ids.forEach { WidgetStore.clear(context, it) }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        // Resized: re-render so names switch between abbreviations and nicknames.
        WidgetCtl.render(context, id)
        @Suppress("DEPRECATION")
        manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        when (intent.action) {
            ACTION_REFRESH -> Unit
            ACTION_LEAGUE -> {
                WidgetStore.setLeague(context, id, if (WidgetStore.league(context, id) == League.NFL) League.CFB else League.NFL)
                WidgetStore.setWeekCode(context, id, 0)
                WidgetCache.remove(id)
            }
            ACTION_PREV, ACTION_NEXT -> {
                val league = WidgetStore.league(context, id)
                val current = WidgetStore.week(context, id) ?: WidgetCache.get(id)?.week ?: return
                val next = shiftWeek(current, if (intent.action == ACTION_NEXT) 1 else -1, league)
                WidgetStore.setWeekCode(context, id, next.seasonType * 100 + next.week)
                WidgetCache.remove(id)
            }
            ACTION_CURRENT -> {
                WidgetStore.setWeekCode(context, id, 0)
                WidgetCache.remove(id)
            }
            else -> return
        }
        refreshAsync(context, listOf(id))
    }

    /** Broadcasts get ~10 s; goAsync keeps the receiver alive while the scores load. */
    private fun refreshAsync(context: Context, ids: List<Int>) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9_000) { WidgetCtl.refresh(context.applicationContext, ids) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.nate.scoreline.widget.REFRESH"
        const val ACTION_LEAGUE = "com.nate.scoreline.widget.LEAGUE"
        const val ACTION_PREV = "com.nate.scoreline.widget.PREV"
        const val ACTION_NEXT = "com.nate.scoreline.widget.NEXT"
        const val ACTION_CURRENT = "com.nate.scoreline.widget.CURRENT"
        const val EXTRA_LEAGUE = "com.nate.scoreline.LEAGUE"
        const val EXTRA_EVENT = "com.nate.scoreline.EVENT"
    }
}

/** Per-widget settings: league and week (0 = current week). */
object WidgetStore {
    private fun prefs(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun league(c: Context, id: Int): League =
        runCatching { League.valueOf(prefs(c).getString("league_$id", null) ?: "NFL") }.getOrDefault(League.NFL)
    fun setLeague(c: Context, id: Int, l: League) = prefs(c).edit().putString("league_$id", l.name).apply()

    fun weekCode(c: Context, id: Int) = prefs(c).getInt("week_$id", 0)
    fun week(c: Context, id: Int): WeekInfo? = weekCode(c, id).let { if (it == 0) null else WeekInfo(it / 100, it % 100) }
    fun setWeekCode(c: Context, id: Int, code: Int) = prefs(c).edit().putInt("week_$id", code).apply()

    fun updatedAt(c: Context, id: Int) = prefs(c).getLong("updated_$id", 0L)
    fun failed(c: Context, id: Int) = prefs(c).getBoolean("failed_$id", false)
    fun markResult(c: Context, id: Int, ok: Boolean) {
        val e = prefs(c).edit().putBoolean("failed_$id", !ok)
        if (ok) e.putLong("updated_$id", System.currentTimeMillis())
        e.apply()
    }

    fun clear(c: Context, id: Int) {
        prefs(c).edit().remove("league_$id").remove("week_$id").remove("updated_$id").remove("failed_$id").apply()
        WidgetCache.remove(id)
    }
}

data class WidgetData(val league: League, val games: List<Game>, val week: WeekInfo?)

/** Last loaded scores per widget, shared between the provider and the list service (same process). */
object WidgetCache {
    private val map = ConcurrentHashMap<Int, WidgetData>()
    fun get(id: Int): WidgetData? = map[id]
    fun put(id: Int, d: WidgetData) { map[id] = d }
    fun remove(id: Int) { map.remove(id) }
}

object WidgetCtl {
    private const val WORK = "widget-refresh"
    private val clock = DateTimeFormatter.ofPattern("h:mm")

    fun allIds(c: Context): IntArray {
        val m = AppWidgetManager.getInstance(c)
        return m.getAppWidgetIds(ComponentName(c, ScoresWidget::class.java)) + miniIds(c)
    }

    fun miniIds(c: Context): IntArray =
        AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, MiniScoresWidget::class.java))

    fun isMini(c: Context, id: Int) = id in miniIds(c)

    /** Loads this widget's games (network). Uses the app's college view (Top 25 / All FBS / conference). */
    suspend fun fetch(c: Context, id: Int): WidgetData {
        val league = WidgetStore.league(c, id)
        val week = WidgetStore.week(c, id)
        val view = Favorites.get(c).cfbView
        val group = when {
            league != League.CFB -> null
            view == "top25" || view == "fbs" -> Conferences.FBS
            else -> view
        }
        val board = Espn.scoreboard(league, week, group)
        val games = if (league == League.CFB && view == "top25") {
            board.games.filter { it.away.rank != null || it.home.rank != null }
        } else {
            board.games
        }
        return WidgetData(league, games, board.week).also { WidgetCache.put(id, it) }
    }

    suspend fun refresh(c: Context, ids: List<Int>) {
        val mgr = AppWidgetManager.getInstance(c)
        for (id in ids) {
            val ok = try {
                fetch(c, id)
                true
            } catch (e: Exception) {
                false
            }
            WidgetStore.markResult(c, id, ok)
            render(c, id)
            @Suppress("DEPRECATION")
            mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        }
    }

    fun isWide(c: Context, id: Int): Boolean =
        AppWidgetManager.getInstance(c).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) >= 300

    private fun broadcast(c: Context, id: Int, action: String, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c,
            id * 10 + slot,
            Intent(c, ScoresWidget::class.java).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Draws the header/footer and (re)attaches the game list. */
    fun render(c: Context, id: Int) {
        if (isMini(c, id)) return renderMini(c, id)
        val league = WidgetStore.league(c, id)
        val data = WidgetCache.get(id)
        val v = RemoteViews(c.packageName, R.layout.widget_scores)

        v.setTextViewText(R.id.widget_league, "${league.label}  ⇄")
        val updated = WidgetStore.updatedAt(c, id)
        val timeText = if (updated == 0L) "" else
            java.time.Instant.ofEpochMilli(updated).atZone(ZoneId.systemDefault()).toLocalTime().format(clock)
        v.setTextViewText(
            R.id.widget_updated,
            when {
                WidgetStore.failed(c, id) -> "Offline\n$timeText".trim()
                updated == 0L -> "Loading…"
                else -> "Updated\n$timeText"
            },
        )
        val week = data?.week ?: WidgetStore.week(c, id)
        val onCurrent = WidgetStore.weekCode(c, id) == 0
        v.setTextViewText(
            R.id.widget_week,
            (week?.label ?: "This week") + if (onCurrent) "" else "  ·  tap for this week",
        )

        val svc = Intent(c, ScoresWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)) // unique per widget, so lists aren't shared
        @Suppress("DEPRECATION")
        v.setRemoteAdapter(R.id.widget_list, svc)
        v.setEmptyView(R.id.widget_list, R.id.widget_empty)
        v.setTextViewText(
            R.id.widget_empty,
            when {
                data != null -> "No games"
                WidgetStore.failed(c, id) -> "Couldn't load scores.\nTap refresh."
                else -> "Loading…"
            },
        )

        // Row taps open that game in the app. The template must be mutable so each row can add its game id.
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val openGame = PendingIntent.getActivity(
            c, 100_000 + id,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            mutable or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setPendingIntentTemplate(R.id.widget_list, openGame)
        val openApp = PendingIntent.getActivity(
            c, 200_000 + id,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setOnClickPendingIntent(R.id.widget_logo, openApp)
        v.setOnClickPendingIntent(R.id.widget_refresh, broadcast(c, id, ScoresWidget.ACTION_REFRESH, 1))
        v.setOnClickPendingIntent(R.id.widget_league, broadcast(c, id, ScoresWidget.ACTION_LEAGUE, 2))
        v.setOnClickPendingIntent(R.id.widget_prev, broadcast(c, id, ScoresWidget.ACTION_PREV, 3))
        v.setOnClickPendingIntent(R.id.widget_next, broadcast(c, id, ScoresWidget.ACTION_NEXT, 4))
        v.setOnClickPendingIntent(R.id.widget_week, broadcast(c, id, ScoresWidget.ACTION_CURRENT, 5))

        AppWidgetManager.getInstance(c).updateAppWidget(id, v)
    }

    /** Mini Scores: league toggle, refresh, and the game list. Tapping the logo opens the app. */
    private fun renderMini(c: Context, id: Int) {
        val league = WidgetStore.league(c, id)
        val data = WidgetCache.get(id)
        val v = RemoteViews(c.packageName, R.layout.widget_mini)
        v.setTextViewText(R.id.widget_league, "${league.label}  ⇄")
        val svc = Intent(c, MiniScoresWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
        @Suppress("DEPRECATION")
        v.setRemoteAdapter(R.id.widget_list, svc)
        v.setEmptyView(R.id.widget_list, R.id.widget_empty)
        v.setTextViewText(
            R.id.widget_empty,
            when {
                data != null -> "No games"
                WidgetStore.failed(c, id) -> "Couldn't load.\nTap refresh."
                else -> "Loading…"
            },
        )
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        v.setPendingIntentTemplate(
            R.id.widget_list,
            PendingIntent.getActivity(
                c, 100_000 + id,
                Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                mutable or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        v.setOnClickPendingIntent(
            R.id.widget_logo,
            PendingIntent.getActivity(
                c, 200_000 + id,
                Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        v.setOnClickPendingIntent(R.id.widget_refresh, broadcast(c, id, ScoresWidget.ACTION_REFRESH, 1))
        v.setOnClickPendingIntent(R.id.widget_league, broadcast(c, id, ScoresWidget.ACTION_LEAGUE, 2))
        AppWidgetManager.getInstance(c).updateAppWidget(id, v)
    }

    /**
     * Background refresh every 15 minutes (Android's minimum for background work).
     * Doze can stretch this while the phone is idle; the refresh button is always immediate.
     */
    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
    }
}

class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ids = WidgetCtl.allIds(applicationContext)
        if (ids.isEmpty()) {
            WidgetCtl.cancel(applicationContext)
        } else {
            WidgetCtl.refresh(applicationContext, ids.toList())
        }
        return Result.success()
    }
}

class ScoresWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ScoresWidgetFactory(
            applicationContext,
            intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        )
}

/** Builds each game row. Runs on a background binder thread, so blocking network calls are allowed. */
class ScoresWidgetFactory(private val c: Context, private val id: Int) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<WidgetRow> = emptyList()
    private var league: League = League.NFL

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val data = WidgetCache.get(id)
            ?: runBlocking { runCatching { withTimeoutOrNull(10_000) { WidgetCtl.fetch(c, id) } }.getOrNull() }
        if (data == null) {
            rows = emptyList()
            return
        }
        league = data.league
        rows = WidgetFormat.rows(
            data.games,
            Favorites.get(c).favTeamIds(data.league),
            WidgetCtl.isWide(c, id),
            ZoneId.systemDefault(),
            LocalDate.now(),
        )
    }

    override fun onDestroy() {}

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val r = rows.getOrNull(position) ?: return RemoteViews(c.packageName, R.layout.widget_row)
        if (r.header != null) {
            return RemoteViews(c.packageName, R.layout.widget_day).apply { setTextViewText(R.id.day_label, r.header) }
        }
        val v = RemoteViews(c.packageName, R.layout.widget_row)
        val live = c.getColor(R.color.widget_live)
        val text = c.getColor(R.color.widget_text)
        val sub = c.getColor(R.color.widget_subtext)

        v.setTextViewText(R.id.row_away_name, r.awayName)
        v.setTextViewText(R.id.row_home_name, r.homeName)
        v.setTextViewText(R.id.row_center, r.center)
        v.setTextViewText(R.id.row_status, r.status)
        v.setTextColor(R.id.row_center, if (r.live) live else text)
        v.setTextColor(R.id.row_status, if (r.live) live else sub)
        v.setViewVisibility(R.id.row_away_ball, if (r.awayBall) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.row_home_ball, if (r.homeBall) View.VISIBLE else View.GONE)

        val away = LogoCache.get(c, r.awayLogo)
        val home = LogoCache.get(c, r.homeLogo)
        if (away != null) v.setImageViewBitmap(R.id.row_away_logo, away) else v.setImageViewResource(R.id.row_away_logo, 0)
        if (home != null) v.setImageViewBitmap(R.id.row_home_logo, home) else v.setImageViewResource(R.id.row_home_logo, 0)

        v.setOnClickFillInIntent(
            R.id.row_root,
            Intent().putExtra(ScoresWidget.EXTRA_LEAGUE, league.name).putExtra(ScoresWidget.EXTRA_EVENT, r.eventId),
        )
        return v
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 2 // game rows and day dividers
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
}

/**
 * Small team-logo bitmaps for the widget: memory cache, then a disk cache (so logos
 * survive restarts and don't re-download every refresh), then the network.
 */
object LogoCache {
    private const val PX = 72
    private val mem = LruCache<String, Bitmap>(96)

    fun get(c: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        mem.get(url)?.let { return it }
        val file = File(File(c.cacheDir, "logos").apply { mkdirs() }, "${url.hashCode().toUInt()}.png")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let { mem.put(url, it); return it }
        }
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 8_000
            val bytes = try { conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= PX && bounds.outHeight / (sample * 2) >= PX) sample *= 2
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scale = PX.toFloat() / maxOf(raw.width, raw.height)
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(raw, maxOf(1, (raw.width * scale).toInt()), maxOf(1, (raw.height * scale).toInt()), true)
            } else raw
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            mem.put(url, bmp)
            bmp
        } catch (e: Exception) {
            null
        }
    }
}


/** Compact 2x2 "Mini Scores" widget. Shares settings, loading and refresh with [ScoresWidget]. */
class MiniScoresWidget : ScoresWidget()

class MiniScoresWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        MiniScoresWidgetFactory(
            applicationContext,
            intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        )
}

/** One game per row: two team lines (logo, abbreviation, score) and the status on the right. */
class MiniScoresWidgetFactory(private val c: Context, private val id: Int) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<Pair<String?, Game?>> = emptyList()
    private var league: League = League.NFL

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val data = WidgetCache.get(id)
            ?: runBlocking { runCatching { withTimeoutOrNull(10_000) { WidgetCtl.fetch(c, id) } }.getOrNull() }
        if (data == null) {
            items = emptyList()
            return
        }
        league = data.league
        items = WidgetFormat.items(data.games, Favorites.get(c).favTeamIds(data.league), ZoneId.systemDefault(), LocalDate.now())
    }

    override fun onDestroy() {}
    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val (header, g) = items.getOrNull(position) ?: return RemoteViews(c.packageName, R.layout.widget_mini_row)
        if (header != null || g == null) {
            return RemoteViews(c.packageName, R.layout.widget_mini_day).apply { setTextViewText(R.id.day_label, header.orEmpty()) }
        }
        val v = RemoteViews(c.packageName, R.layout.widget_mini_row)
        val text = c.getColor(R.color.widget_text)
        val sub = c.getColor(R.color.widget_subtext)
        val live = c.getColor(R.color.widget_live)
        val final = g.state == "post"

        fun team(t: TeamSide, logo: Int, name: Int, score: Int, ball: Int) {
            v.setTextViewText(name, WidgetFormat.name(t, wide = false))
            v.setTextViewText(score, if (g.state == "pre") "" else t.score)
            val dim = final && !t.winner && (g.away.winner || g.home.winner)
            v.setTextColor(name, if (dim) sub else text)
            v.setTextColor(score, if (dim) sub else text)
            v.setViewVisibility(ball, if (g.isLive && g.possessionTeamId != null && g.possessionTeamId == t.id) View.VISIBLE else View.GONE)
            val bmp = LogoCache.get(c, t.logo)
            if (bmp != null) v.setImageViewBitmap(logo, bmp) else v.setImageViewResource(logo, 0)
        }
        team(g.away, R.id.mini_away_logo, R.id.mini_away_name, R.id.mini_away_score, R.id.mini_away_ball)
        team(g.home, R.id.mini_home_logo, R.id.mini_home_name, R.id.mini_home_score, R.id.mini_home_ball)

        val status = when (g.state) {
            "pre" -> listOf(WidgetFormat.time(g.date, ZoneId.systemDefault()).ifEmpty { g.detail }, g.broadcast)
                .filter { it.isNotBlank() }.joinToString("\n")
            "in" -> g.detail.replace(" - ", "\n")
            else -> g.detail
        }
        v.setTextViewText(R.id.mini_status, status)
        v.setTextColor(R.id.mini_status, if (g.isLive) live else sub)

        v.setOnClickFillInIntent(
            R.id.mini_root,
            Intent().putExtra(ScoresWidget.EXTRA_LEAGUE, league.name).putExtra(ScoresWidget.EXTRA_EVENT, g.id),
        )
        return v
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 2
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
}
