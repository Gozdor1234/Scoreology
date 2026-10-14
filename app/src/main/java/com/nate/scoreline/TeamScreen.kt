@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

// ---------------------------------------------------------------- shared pieces (team + player pages)

/** Header bar in the team's color, with black or white icons/text for contrast. */
@Composable
fun teamBarColors(hex: String?): TopAppBarColors {
    val c = ColorMath.parseHex(hex.orEmpty()) ?: return scorelineTopBarColors()
    val on = Color(ColorMath.onColorFor(c))
    return TopAppBarDefaults.topAppBarColors(
        containerColor = Color(c),
        scrolledContainerColor = Color(c),
        titleContentColor = on,
        navigationIconContentColor = on,
        actionIconContentColor = on,
    )
}

/** Team color fading downward, used behind team and player headers. */
@Composable
fun teamFade(hex: String?, altHex: String? = null): Brush {
    val darkUi = ColorMath.isDark(MaterialTheme.colorScheme.background.toArgb())
    val c = teamColor(hex.orEmpty(), altHex.orEmpty(), darkUi)
    return Brush.verticalGradient(listOf(c?.copy(alpha = 0.45f) ?: Color.Transparent, Color.Transparent))
}

@Composable
fun Headshot(url: String, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = rememberCrispRequest(url, size, fill = true),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                modifier = Modifier.size(size),
            )
        }
    }
}

/**
 * A sideways-scrolling stat table: a fixed first column (player or season) and one column per stat.
 * Names with an onRowClick are shown in the accent color and open that row.
 */
@Composable
fun StatGrid(
    title: String,
    firstHeader: String,
    labels: List<String>,
    rows: List<Pair<String, List<String>>>,
    totalsLabel: String = "",
    totals: List<String> = emptyList(),
    firstWidth: Dp = 150.dp,
    cellWidth: Dp = 54.dp,
    rowClickable: (Int) -> Boolean = { false },
    onRowClick: (Int) -> Unit = {},
    groups: List<ColumnGroup> = emptyList(),
    /** Optional small logo shown after each row's first-column text (e.g. the team for that season). */
    rowIcons: List<String> = emptyList(),
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(vertical = 10.dp)) {
            if (title.isNotBlank()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                if (groups.isNotEmpty()) {
                    // e.g. "RUSHING" over its 5 columns, "RECEIVING" over the next 6
                    Row(Modifier.padding(top = 2.dp)) {
                        Spacer(Modifier.width(firstWidth))
                        groups.forEach { g ->
                            Text(
                                g.title.uppercase(),
                                Modifier.width(cellWidth * g.span).padding(horizontal = 4.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    }
                }
                GridRow(firstHeader, labels, firstWidth, cellWidth, header = true)
                rows.forEachIndexed { i, (first, cells) ->
                    GridRow(first, cells, firstWidth, cellWidth, onFirst = if (rowClickable(i)) { { onRowClick(i) } } else null,
                        icon = rowIcons.getOrNull(i)?.ifEmpty { null })
                }
                if (totals.any { it.isNotBlank() }) GridRow(totalsLabel, totals, firstWidth, cellWidth, bold = true)
            }
        }
    }
}

@Composable
private fun GridRow(
    first: String,
    cells: List<String>,
    firstWidth: Dp,
    cellWidth: Dp,
    header: Boolean = false,
    bold: Boolean = false,
    onFirst: (() -> Unit)? = null,
    icon: String? = null,
) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.width(firstWidth).then(if (onFirst != null) Modifier.clickable(onClick = onFirst) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                first,
                Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = style,
                fontWeight = if (onFirst != null) FontWeight.Medium else weight,
                color = when {
                    header -> MaterialTheme.colorScheme.onSurfaceVariant
                    onFirst != null -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (icon != null) {
                Spacer(Modifier.width(6.dp))
                Logo(icon, 16.dp)
            }
        }
        cells.forEach { c ->
            Text(
                c,
                Modifier.width(cellWidth),
                textAlign = TextAlign.End,
                style = style,
                fontWeight = weight,
                maxLines = 1,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun InlineLoading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun InlineNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    )
}

// ---------------------------------------------------------------- team page

private val teamTabs = listOf("Scores", "Stats", "Roster")

@Composable
fun TeamScreen(league: League, teamId: String, onBack: () -> Unit, open: (Route) -> Unit) {
    val fav = Favorites.get(LocalContext.current)
    val infoP = rememberPolled<TeamInfo>("info-${league.name}-$teamId", { 30 * 60_000L }) { TeamApi.info(league, teamId) }
    val schedP = rememberPolled<List<Game>>(
        "sched-${league.name}-$teamId",
        { s -> if (s?.any { it.isLive } == true) LIVE_MS else IDLE_MS },
    ) { TeamApi.schedule(league, teamId) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val info = infoP.state.data
    val isFav = fav.isFavTeam(league, teamId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(info?.shortName ?: "Team") },
                colors = teamBarColors(info?.color),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (info != null) {
                        IconButton(onClick = { fav.toggleTeam(league, teamId, info.name) }) {
                            Icon(
                                if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (isFav) "Remove favorite" else "Add favorite",
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (info != null) {
                Row(
                    Modifier.fillMaxWidth().background(teamFade(info.color, info.altColor)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Logo(info.logo, 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(info.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        val line = listOf(info.record, info.standing).filter { it.isNotBlank() }.joinToString("  ·  ")
                        if (line.isNotBlank()) {
                            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            TabRow(selectedTabIndex = tab) {
                teamTabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> TeamScores(schedP, league, open)
                1 -> TeamStats(league, teamId, schedP, open)
                else -> TeamRoster(league, teamId, open)
            }
        }
    }
}

@Composable
private fun TeamScores(schedP: Polled<List<Game>>, league: League, open: (Route) -> Unit) {
    LoadableContent(schedP, emptyText = "No games scheduled.") { games ->
        val live = games.filter { it.state == "in" }
        val upcoming = games.filter { it.state == "pre" }.sortedBy { it.date }
        // Oldest game first, most recent at the bottom.
        val results = games.filter { it.state == "post" }.sortedBy { it.date }
        if (games.isEmpty()) {
            Message("No games on the schedule yet.")
            return@LoadableContent
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Live" to live, "Upcoming" to upcoming, "Results" to results).forEach { (label, list) ->
                if (list.isNotEmpty()) {
                    item {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                    items(list, key = { "$label-${it.id}" }) { g -> GameCard(g, false, showDate = true) { open(Route.GameDetail(league, g.id)) } }
                }
            }
        }
    }
}

@Composable
private fun TeamStats(league: League, teamId: String, schedP: Polled<List<Game>>, open: (Route) -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 players, 1 team
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Players") })
            FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Team") })
        }
        if (mode == 0) PlayerStatsView(league, teamId, schedP, open) else TeamTotalsView(league, teamId)
    }
}

@Composable
private fun PlayerStatsView(league: League, teamId: String, schedP: Polled<List<Game>>, open: (Route) -> Unit) {
    val games = schedP.state.data
    if (games == null) {
        if (schedP.state.error != null) Message("Couldn't load the schedule.", "Retry", schedP.refresh) else InlineLoading()
        return
    }
    val finished = games.filter { it.state == "post" }
    if (finished.isEmpty()) {
        Message("Season stats appear after this team's first game.")
        return
    }
    // Re-add up whenever another game goes final.
    val statsP = rememberPolled<TeamSeasonStats>(
        "season-${league.name}-$teamId-${finished.size}",
        { 15 * 60_000L },
    ) { TeamApi.seasonStats(league, teamId, games) }

    LoadableContent(statsP) { s ->
        LazyColumn {
            if (s.leaders.isNotEmpty()) {
                item {
                    Text(
                        "Team Leaders",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        s.leaders.forEach { l ->
                            Card(
                                Modifier.width(150.dp).clickable(enabled = l.athleteId.isNotEmpty()) {
                                    open(Route.Player(league, l.athleteId))
                                },
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(l.stat, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Headshot(l.headshot, 40.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(l.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(l.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            items(s.tables, key = { it.key }) { t ->
                StatGrid(
                    title = t.title,
                    firstHeader = "NAME",
                    labels = t.labels,
                    rows = t.names.zip(t.rows),
                    totalsLabel = "Total",
                    totals = t.totals,
                    rowClickable = { i -> t.athleteIds.getOrNull(i).orEmpty().isNotEmpty() },
                    onRowClick = { i -> open(Route.Player(league, t.athleteIds[i])) },
                )
            }
            item {
                InlineNote("Season totals added up from ${s.gamesCounted} box score${if (s.gamesCounted == 1) "" else "s"}.")
            }
        }
    }
}

@Composable
private fun TeamTotalsView(league: League, teamId: String) {
    val p = rememberPolled<List<TeamStatCategory>>("totals-${league.name}-$teamId", { 30 * 60_000L }) { TeamApi.totals(league, teamId) }
    LoadableContent(p) { cats ->
        if (cats.isEmpty()) {
            Message("No team stats published yet.")
            return@LoadableContent
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(cats, key = { it.title }) { c ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        c.stats.forEach { st ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(st.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text(st.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                if (st.rank.isNotBlank()) {
                                    Text(
                                        st.rank,
                                        Modifier.width(64.dp),
                                        textAlign = TextAlign.End,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamRoster(league: League, teamId: String, open: (Route) -> Unit) {
    val p = rememberPolled<List<RosterGroup>>("roster-${league.name}-$teamId", { 60 * 60_000L }) { TeamApi.roster(league, teamId) }
    LoadableContent(p) { groups ->
        if (groups.isEmpty()) {
            Message("No roster published.")
            return@LoadableContent
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { g ->
                item { SectionHeader(g.title) }
                item { RosterLine(null, header = true) {} }
                items(g.players, key = { "${g.title}-${it.id}-${it.name}" }) { pl ->
                    RosterLine(pl.copy(headshot = pl.headshot.ifEmpty { TeamApi.headshotFallback(league, pl.id) })) {
                        if (pl.id.isNotEmpty()) open(Route.Player(league, pl.id))
                    }
                }
            }
        }
    }
}

@Composable
private fun RosterLine(p: RosterPlayer?, header: Boolean = false, onClick: () -> Unit) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !header, onClick = onClick).padding(horizontal = 16.dp, vertical = if (header) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (header) {
                Text("NAME", Modifier.weight(1f), style = style, color = sub)
            } else if (p != null) {
                Headshot(p.headshot, 38.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (p.jersey.isNotBlank()) Text("  #${p.jersey}", style = MaterialTheme.typography.labelSmall, color = sub)
                    }
                    if (p.college.isNotBlank()) {
                        Text(p.college, style = MaterialTheme.typography.labelSmall, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val cells = if (header) listOf("POS", "AGE", "HT", "WT") else listOf(p?.position.orEmpty(), p?.age.orEmpty(), p?.height.orEmpty(), p?.weight.orEmpty())
            listOf(38.dp, 34.dp, 46.dp, 62.dp).forEachIndexed { i, w ->
                Text(
                    cells[i],
                    Modifier.width(w),
                    textAlign = TextAlign.End,
                    style = style,
                    color = if (header) sub else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
        if (!header) HorizontalDivider()
    }
}
