@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.HorizontalDivider
import coil.request.ImageRequest
import coil.compose.AsyncImage

fun shiftWeek(w: WeekInfo, delta: Int, league: League): WeekInfo {
    var type = w.seasonType
    var n = w.week + delta
    when {
        type == 2 && n > league.regularWeeks -> { type = 3; n = 1 }
        type == 3 && n < 1 -> { type = 2; n = league.regularWeeks }
        n < 1 -> n = 1
    }
    if (type == 3 && n > 5) n = 5
    return WeekInfo(type, n)
}

@Composable
fun ScoresScreen(modifier: Modifier, open: (Route) -> Unit) {
    val fav = Favorites.get(LocalContext.current)
    var league by rememberSaveable { mutableStateOf(League.NFL) }
    // Saved as seasonType*100 + week (0 = current) so it survives opening a game and coming back.
    var weekCode by rememberSaveable(league) { mutableIntStateOf(0) }
    val week: WeekInfo? = if (weekCode == 0) null else WeekInfo(weekCode / 100, weekCode % 100)
    val setWeek: (WeekInfo?) -> Unit = { v -> weekCode = if (v == null) 0 else v.seasonType * 100 + v.week }
    var mineOnly by rememberSaveable { mutableStateOf(false) }

    // College view: "top25" and "fbs" both load all FBS games (Top 25 filters by AP rank on the phone);
    // a conference view asks ESPN for just that conference.
    val cfb = league == League.CFB
    val view = if (cfb) fav.cfbView else "fbs"
    val group: String? = when {
        !cfb -> null
        view == "top25" || view == "fbs" -> Conferences.FBS
        else -> view
    }
    val pinId = if (cfb) fav.scoresPinCfb else null
    // FanDuel lines when an Odds API key is set (cached 3 h); otherwise ESPN's DraftKings lines on each game.
    val ctx = LocalContext.current
    val fdP = if (fav.showOdds && fav.oddsApiKey.isNotBlank()) {
        rememberPolled<FanDuelOdds.Book?>("fd-${league.name}-${fav.oddsApiKey}", { 30 * 60_000L }) { FanDuelOdds.get(ctx, league) }
    } else null
    val oddsFor: (Game) -> GameOdds? = { g ->
        if (!fav.showOdds || g.state != "pre") null
        else fdP?.state?.data?.let { OddsParse.match(it.events, g)?.odds } ?: g.odds
    }
    val pinFetch = if (pinId != null && group != pinId) pinId else null

    val polled = rememberPolled<ScoresData>(
        key = listOf(league, week, group, pinFetch),
        intervalMs = { d -> if (d?.board?.games?.any { it.isLive } == true) LIVE_MS else IDLE_MS },
    ) {
        supervisorScope {
            val main = async { Espn.scoreboard(league, week, group) }
            // The pinned conference is a second, optional request; if it fails the main list still shows.
            val pinned = pinFetch?.let { id -> async { runCatching { Espn.scoreboard(league, week, id) }.getOrNull() } }
            ScoresData(main.await(), pinned?.await()?.games?.map { it.id }?.toSet() ?: emptySet())
        }
    }

    val shownWeek = week ?: polled.state.data?.board?.week

    Column(modifier.fillMaxSize()) {
        SearchTabs(open) {
            TabRow(selectedTabIndex = league.ordinal) {
                League.entries.forEach { l ->
                    Tab(
                        selected = l == league,
                        onClick = { league = l },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LeagueLogo(l)
                                Spacer(Modifier.width(8.dp))
                                Text(l.label)
                            }
                        },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = shownWeek != null, onClick = { shownWeek?.let { setWeek(shiftWeek(it, -1, league)) } }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week")
            }
            TextButton(onClick = { setWeek(null) }) {
                Text(shownWeek?.label ?: "This week", fontWeight = FontWeight.SemiBold)
            }
            IconButton(enabled = shownWeek != null, onClick = { shownWeek?.let { setWeek(shiftWeek(it, 1, league)) } }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week")
            }
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = mineOnly,
                onClick = { mineOnly = !mineOnly },
                label = { Text("My teams") },
                leadingIcon = if (mineOnly) { { Icon(Icons.Filled.Favorite, null) } } else null,
            )
        }
        if (cfb) CollegeViewRow(fav, view)

        // Pinch on the list to resize cards. Two-finger gestures are handled here; one finger still scrolls.
        var zoom by remember { mutableFloatStateOf(fav.scoresZoom) }
        var pinching by remember { mutableStateOf(false) }
        // Fully zoomed out: two columns of cards. Any zoom in goes back to one column.
        val twoCol = zoom <= Favorites.ZOOM_MIN + 0.001f
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var changed = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            pinching = true
                            val z = event.calculateZoom()
                            if (z != 1f) {
                                zoom = (zoom * z).coerceIn(Favorites.ZOOM_MIN, Favorites.ZOOM_MAX)
                                changed = true
                            }
                            // Consume so the list doesn't also scroll during the pinch.
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    pinching = false
                    if (changed) fav.updateScoresZoom(zoom)
                }
            },
        ) {
        // Pull down on the list to refresh. The spinner shows until the fetch finishes.
        // Every fetch step publishes a new state object, so the spinner stays up until
        // the state has moved past the one seen at pull time and is no longer loading.
        var pulledFrom by remember { mutableStateOf<Any?>(null) }
        val st = polled.state
        val pulling = pulledFrom != null && (st === pulledFrom || st.loading)
        LaunchedEffect(pulling) { if (!pulling) pulledFrom = null }
        PullToRefreshBox(
            isRefreshing = pulling,
            onRefresh = { pulledFrom = st; polled.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
        LoadableContent(polled) { data ->
            val sb = data.board
            val favIds = fav.favTeamIds(league)
            fun isFav(g: Game) = g.home.id in favIds || g.away.id in favIds
            val stateOrder = mapOf("in" to 0, "pre" to 1, "post" to 2)
            fun arrange(list: List<Game>) = list
                .filter { !mineOnly || isFav(it) }
                .sortedWith(compareBy<Game>({ if (isFav(it)) 0 else 1 }, { stateOrder[it.state] ?: 3 }, { it.date }))
            val ranked: (Game) -> Boolean = { it.away.rank != null || it.home.rank != null }
            val pinnedGames = arrange(sb.games.filter { it.id in data.pinnedIds })
            val games = arrange(sb.games.filter { it.id !in data.pinnedIds && (view != "top25" || ranked(it)) })
            if (games.isEmpty() && pinnedGames.isEmpty()) {
                // Scrollable so a pull still works on an empty week.
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Message(
                    when {
                        mineOnly -> "None of your teams play this week.\nAdd teams under Settings."
                        view == "top25" -> "No ranked teams play this week."
                        else -> "No games this week."
                    },
                )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp * zoom),
                ) {
                    if (pinnedGames.isNotEmpty()) {
                        item { ListLabel("★ Pinned: ${Conferences.name(pinId)}") }
                        dayGroupedItems("pin", pinnedGames, ::isFav, mineOnly, twoCol, zoom) { g -> GameCard(g, isFav(g), oddsFor(g)) { open(Route.GameDetail(league, g.id)) } }
                        if (games.isNotEmpty()) item { ListLabel(if (view == "top25") "Top 25" else "All FBS") }
                    }
                    dayGroupedItems("all", games, ::isFav, mineOnly, twoCol, zoom) { g -> GameCard(g, isFav(g), oddsFor(g)) { open(Route.GameDetail(league, g.id)) } }
                    item {
                        Text(
                            agoText(polled.state.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
        }
            // Size readout while pinching.
            if (pinching) {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                ) {
                    Text(
                        "${Math.round(zoom * 100)}%" + if (twoCol) "  ·  2 columns" else "",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** Game cards, one per row, or two side by side when fully zoomed out. */
private fun LazyListScope.gameItems(games: List<Game>, twoCol: Boolean, zoom: Float, card: @Composable (Game) -> Unit) {
    if (!twoCol) {
        items(games, key = { it.id }) { g -> Zoomed(zoom) { card(g) } }
    } else {
        items(games.chunked(2), key = { "pair-${it.first().id}" }) { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { g -> Box(Modifier.weight(1f)) { Zoomed(zoom) { card(g) } } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Favorites stay on top as before; every other game is grouped by local calendar day under a
 * subtle date divider: live and upcoming days first, then results with the most recent day last. With "My teams" on,
 * all shown games are favorites, so they're grouped by day too.
 */
private fun LazyListScope.dayGroupedItems(
    section: String,
    games: List<Game>,
    isFav: (Game) -> Boolean,
    mineOnly: Boolean,
    twoCol: Boolean,
    zoom: Float,
    card: @Composable (Game) -> Unit,
) {
    val favs = if (mineOnly) emptyList() else games.filter(isFav)
    val rest = games.filterNot { it in favs }
    // Live games first, then upcoming, then finished; by kickoff within each.
    val order = compareBy<Game>({ when (it.state) { "in" -> 0; "pre" -> 1; else -> 2 } }, { it.date })
    if (favs.isNotEmpty()) gameItems(favs.sortedWith(order), twoCol, zoom, card)
    val zone = java.time.ZoneId.systemDefault()
    fun day(g: Game) = runCatching { java.time.OffsetDateTime.parse(g.date).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
    // Live and upcoming games first, by day; then results, oldest day first, so the most recent final is at the very bottom.
    val (ahead, done) = rest.partition { it.state == "in" || it.state == "pre" }
    val byDay = nullsLast(compareBy<java.time.LocalDate> { it })
    ahead.groupBy(::day).toSortedMap(byDay).forEach { (d, list) ->
        item(key = "day-$section-up-${d ?: "tbd"}") { DayDivider(d) }
        gameItems(list.sortedWith(order), twoCol, zoom, card)
    }
    done.groupBy(::day).toSortedMap(byDay).forEach { (d, list) ->
        item(key = "day-$section-res-${d ?: "tbd"}") { DayDivider(d) }
        gameItems(list.sortedBy { it.date }, twoCol, zoom, card)
    }
}

@Composable
private fun DayDivider(d: java.time.LocalDate?) {
    val label = WidgetFormat.dayLabel(d, java.time.LocalDate.now())
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        if (LocalModern.current) {
            Box(Modifier.weight(1f).height(2.dp).background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(modernPalette.edge, Color.Transparent)),
                RoundedCornerShape(1.dp),
            ))
        } else {
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Draws content at a different size by scaling dp and sp together, so layout (not just pixels) shrinks or grows. */
@Composable
private fun Zoomed(scale: Float, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale)) { content() }
}

data class ScoresData(val board: Scoreboard, val pinnedIds: Set<String>)

@Composable
private fun ListLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/** Top 25 / All FBS / conference chooser. The star next to each conference pins it to the top. */
@Composable
private fun CollegeViewRow(fav: Favorites, view: String) {
    var menuOpen by remember { mutableStateOf(false) }
    val confView = view != "top25" && view != "fbs"
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = view == "top25", onClick = { fav.updateCfbView("top25") }, label = { Text("Top 25") })
        FilterChip(selected = view == "fbs", onClick = { fav.updateCfbView("fbs") }, label = { Text("All FBS") })
        Box {
            FilterChip(
                selected = confView,
                onClick = { menuOpen = true },
                label = { Text(if (confView) Conferences.name(view) else "Conference") },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Text(
                    "Tap a conference to view it. Tap its star to pin it to the top.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).width(220.dp),
                )
                Conferences.all.forEach { (id, name) ->
                    val pinned = fav.scoresPinCfb == id
                    DropdownMenuItem(
                        text = { Text(name, fontWeight = if (view == id) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { fav.updateCfbView(id); menuOpen = false },
                        trailingIcon = {
                            IconButton(onClick = { fav.toggleScoresPin(id) }) {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = if (pinned) "Unpin $name" else "Pin $name",
                                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        },
                    )
                }
            }
        }
        if (confView) {
            val pinned = fav.scoresPinCfb == view
            IconButton(onClick = { fav.toggleScoresPin(view) }) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (pinned) "Unpin conference" else "Pin conference to top",
                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
/** showDate: add the local game date after "Final" (used on team schedules). */
fun GameCard(g: Game, favorite: Boolean, odds: GameOdds? = null, showDate: Boolean = false, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = if (favorite) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            TeamLine(g.away, g, hasBall = g.isLive && g.possessionTeamId == g.away.id)
            TeamLine(g.home, g, hasBall = g.isLive && g.possessionTeamId == g.home.id)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 300.dp
                // Forecast follows the network (or down and distance), after a dot, and is the part that shortens if space runs out.
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (g.state) {
                        "in" -> {
                            LiveBadge()
                            Text("  ${g.detail}", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            if (g.downDistance.isNotBlank()) {
                                Text("  •  ${g.downDistance}", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        "pre" -> Text(
                            listOf(formatLocal(g.date).ifEmpty { g.detail }, g.broadcast).filter { it.isNotBlank() }.joinToString("  •  "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        else -> {
                            Text(g.detail, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            val day = if (showDate) gameDay(g.date) else ""
                            if (day.isNotEmpty()) {
                                Text("  •  $day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (g.state == "pre" || g.state == "in") GameWeatherTag(g, compact, Modifier.weight(1f, fill = false))
                }
            }
            if (odds != null && g.state == "pre") OddsTable(odds, g.away.abbr, g.home.abbr, Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun TeamLine(t: TeamSide, g: Game, hasBall: Boolean) {
    val emphasize = g.state == "post" && t.winner
    val dim = g.state == "post" && !t.winner
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(t.logo)
        Spacer(Modifier.width(10.dp))
        if (t.rank != null) {
            Text("${t.rank} ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            t.name.ifEmpty { t.abbr },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
            color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hasBall) {
            Spacer(Modifier.width(6.dp))
            Image(
                painter = painterResource(R.drawable.ic_football),
                contentDescription = "Has the ball",
                modifier = Modifier.size(13.dp),
            )
        }
        if (t.record.isNotBlank()) {
            Text("  ${t.record}  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (g.state != "pre") {
            Text(
                t.score,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = if (emphasize || g.isLive) FontWeight.Bold else FontWeight.Normal,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * League mark for the Scores tabs. NFL shield from ESPN; NCAA logo from Wikimedia Commons,
 * which asks apps to send a descriptive User-Agent.
 */
@Composable
private fun LeagueLogo(l: League) {
    val ctx = LocalContext.current
    val url = when (l) {
        League.NFL -> "https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png"
        League.CFB -> "https://upload.wikimedia.org/wikipedia/commons/thumb/d/dd/NCAA_logo.svg/120px-NCAA_logo.svg.png"
    }
    val request = remember(url) {
        ImageRequest.Builder(ctx)
            .data(url)
            .setHeader("User-Agent", "Scoreology/1.0 (Android app; https://github.com/Gozdor1234/Scoreology)")
            .build()
    }
    AsyncImage(model = request, contentDescription = null, modifier = Modifier.size(22.dp))
}

/** "Sun, Sep 13" in local time, or "" if the date can't be read. */
fun gameDay(iso: String): String = runCatching {
    java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
}.getOrDefault("")
