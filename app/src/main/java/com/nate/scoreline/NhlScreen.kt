@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * NHL scores, one day at a time (hockey plays nearly every day, so there are no weeks).
 * Same cards, favorites, odds, pull-to-refresh and day ordering as the football Scores tab.
 */
@Composable
fun NhlScreen(modifier: Modifier, open: (Route) -> Unit) {
    val league = League.NHL
    val fav = Favorites.get(LocalContext.current)
    val ctx = LocalContext.current
    // Days from today; kept when you open a game and come back.
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    var mineOnly by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()
    val date = today.plusDays(dayOffset.toLong())
    val code = date.format(DateTimeFormatter.BASIC_ISO_DATE)

    val fdP = if (fav.showOdds && fav.oddsApiKey.isNotBlank()) {
        rememberPolled<FanDuelOdds.Book?>("fd-${league.name}-${fav.oddsApiKey}", { 30 * 60_000L }) { FanDuelOdds.get(ctx, league) }
    } else null
    val oddsFor: (Game) -> GameOdds? = { g ->
        if (!fav.showOdds || g.state != "pre") null
        else fdP?.state?.data?.let { OddsParse.match(it.events, g)?.odds } ?: g.odds
    }

    val polled = rememberPolled<Scoreboard>(
        key = "nhl-$code",
        intervalMs = { d -> if (d?.games?.any { it.isLive } == true) LIVE_MS else IDLE_MS },
    ) { Espn.scoreboardOn(league, code) }

    Column(modifier.fillMaxSize()) {
        SearchTabs(open) {
            TabRow(selectedTabIndex = 0) {
                Tab(
                    selected = true,
                    onClick = { dayOffset = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LeagueLogo(league)
                            Spacer(Modifier.width(8.dp))
                            Text(league.label)
                        }
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { dayOffset-- }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
            }
            // Tap the date to jump back to today.
            TextButton(onClick = { dayOffset = 0 }) {
                Text(dayTitle(date, today), fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = { dayOffset++ }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
            }
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = mineOnly,
                onClick = { mineOnly = !mineOnly },
                label = { Text("My teams") },
                leadingIcon = if (mineOnly) { { Icon(Icons.Filled.Favorite, null) } } else null,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            var pulledFrom by remember { mutableStateOf<Any?>(null) }
            val st = polled.state
            val pulling = pulledFrom != null && (st === pulledFrom || st.loading)
            LaunchedEffect(pulling) { if (!pulling) pulledFrom = null }
            PullToRefreshBox(
                isRefreshing = pulling,
                onRefresh = { pulledFrom = st; polled.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LoadableContent(polled) { board ->
                    val favIds = fav.favTeamIds(league)
                    fun isFav(g: Game) = g.home.id in favIds || g.away.id in favIds
                    val games = board.games.filter { !mineOnly || isFav(it) }
                    if (games.isEmpty()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Message(
                                if (mineOnly) "None of your NHL teams play this day.\nAdd teams under Settings."
                                else "No NHL games this day.",
                            )
                        }
                    } else {
                        val zoom = fav.scoresZoom
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp * zoom),
                        ) {
                            dayGroupedItems("nhl", games, ::isFav, mineOnly, twoCol = false, zoom = zoom) { g ->
                                GameCard(g, isFav(g), oddsFor(g)) { open(Route.GameDetail(league, g.id)) }
                            }
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
        }
    }
}

/** "Today", "Tomorrow", "Yesterday", or "Sat, Oct 17". */
private fun dayTitle(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> d.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
}
