@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val playerTabs = listOf("Overview", "Stats", "Bio")

@Composable
fun PlayerScreen(league: League, athleteId: String, onBack: () -> Unit, open: (Route) -> Unit) {
    val key = "${league.name}-$athleteId"
    val aP = rememberPolled<Athlete>("ath-$key", { 30 * 60_000L }) { TeamApi.athlete(league, athleteId) }
    val ovP = rememberPolled<AthleteOverview>("ov-$key", { 15 * 60_000L }) { TeamApi.athleteOverview(league, athleteId) }
    val stP = rememberPolled<List<CareerTable>>("st-$key", { 30 * 60_000L }) { TeamApi.athleteStats(league, athleteId) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var statsMode by rememberSaveable { mutableIntStateOf(0) } // 0 by season, 1 by game
    var season by rememberSaveable { mutableStateOf<String?>(null) } // null = current season
    // Game log loads only when you open "By game".
    val glP = if (tab == 1 && statsMode == 1) {
        rememberPolled<GameLog>("gl-$key-${season ?: "current"}", { 10 * 60_000L }) { TeamApi.gameLog(league, athleteId, season) }
    } else null
    val a = aP.state.data

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        a?.let { if (it.firstName.isNotBlank() && it.lastName.isNotBlank()) "${it.firstName.first()}. ${it.lastName}" else it.name }
                            ?: "Player",
                    )
                },
                colors = teamBarColors(a?.teamColor),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LoadableContent(aP) { ath ->
                LazyColumn {
                    item { PlayerHeader(ath, league, open) }
                    if (ath.summary.isNotEmpty()) item { SummaryStrip(ath) }
                    item {
                        TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
                            playerTabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                        }
                    }
                    when (tab) {
                        0 -> {
                            val ov = ovP.state.data
                            when {
                                ov == null && ovP.state.error != null -> item { InlineNote("Couldn't load the overview.") }
                                ov == null -> item { InlineLoading() }
                                else -> {
                                    val sp = ov.splits
                                    if (sp != null) {
                                        item {
                                            StatGrid(
                                                title = sp.title,
                                                firstHeader = "",
                                                labels = sp.labels,
                                                rows = sp.rows,
                                                firstWidth = 120.dp,
                                            )
                                        }
                                    }
                                    if (ov.news.isNotEmpty()) {
                                        item { SectionHeader("Latest news") }
                                        items(ov.news.take(8)) { n -> NewsRow(n) }
                                    }
                                    if (sp == null && ov.news.isEmpty()) item { InlineNote("No overview available yet.") }
                                }
                            }
                        }

                        1 -> {
                            item {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = statsMode == 0, onClick = { statsMode = 0 }, label = { Text("By season") })
                                    FilterChip(selected = statsMode == 1, onClick = { statsMode = 1 }, label = { Text("By game") })
                                }
                            }
                            if (statsMode == 1) {
                                val gl = glP?.state?.data
                                when {
                                    gl == null && glP?.state?.error != null -> item { InlineNote("Couldn't load the game log.") }
                                    gl == null -> item { InlineLoading() }
                                    else -> {
                                        if (gl.seasons.size > 1) {
                                            item {
                                                Row(
                                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    gl.seasons.forEach { (value, label) ->
                                                        FilterChip(
                                                            selected = value == gl.selectedSeason,
                                                            onClick = { season = value },
                                                            label = { Text(label) },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        if (gl.sections.isEmpty()) item { InlineNote("No games played this season.") }
                                        items(gl.sections, key = { it.title }) { sec ->
                                            StatGrid(
                                                title = sec.title,
                                                firstHeader = "GAME",
                                                labels = gl.labels,
                                                groups = gl.groups,
                                                rows = sec.games.map { g -> gameLabel(g) to g.stats },
                                                firstWidth = 168.dp,
                                                rowClickable = { true },
                                                onRowClick = { i -> open(Route.GameDetail(league, sec.games[i].eventId)) },
                                            )
                                        }
                                        // Season trends under the game log, current season only.
                                        if (gl.seasons.isEmpty() || gl.selectedSeason == gl.seasons.first().first) {
                                            val trends = PlayerTrends.build(gl, ath.position, ath.teamName)
                                            if (trends.isNotEmpty()) {
                                                val label = gl.seasons.firstOrNull()?.second.orEmpty()
                                                item(key = "trends") { TrendsCard(if (label.isBlank()) "Trends" else "$label Trends", trends) }
                                            }
                                        }
                                    }
                                }
                            } else {
                            val tables = stP.state.data
                            when {
                                tables == null && stP.state.error != null -> item { InlineNote("Couldn't load stats.") }
                                tables == null -> item { InlineLoading() }
                                tables.isEmpty() -> item { InlineNote("No stats recorded yet.") }
                                else -> items(tables, key = { it.title }) { t ->
                                    StatGrid(
                                        title = t.title,
                                        firstHeader = "SEASON",
                                        labels = t.labels,
                                        rows = t.rows.asReversed(), // most recent season on top
                                        totalsLabel = "Career",
                                        totals = t.totals,
                                        firstWidth = 84.dp,
                                        rowIcons = t.rowLogos.asReversed(),
                                    )
                                }
                            }
                            }
                        }

                        else -> item { BioList(ath, league, open) }
                    }
                    item { Spacer(Modifier.padding(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PlayerHeader(a: Athlete, league: League, open: (Route) -> Unit) {
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().background(teamFade(a.teamColor)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Headshot(a.headshot.ifEmpty { TeamApi.headshotFallback(league, a.id) }, 104.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            if (a.firstName.isNotBlank()) Text(a.firstName.uppercase(), style = MaterialTheme.typography.titleSmall, color = sub)
            Text(
                a.lastName.ifBlank { a.name }.uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(
                Modifier.padding(top = 4.dp).clickable(enabled = a.teamId.isNotEmpty()) { open(Route.Team(league, a.teamId)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (a.teamLogo.isNotBlank()) { Logo(a.teamLogo, 18.dp); Spacer(Modifier.width(6.dp)) }
                Text(
                    listOf(a.teamName, if (a.jersey.isNotBlank()) "#${a.jersey}" else "", a.position)
                        .filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val facts = listOf(
                "HT/WT" to listOf(a.height, a.weight).filter { it.isNotBlank() }.joinToString(", "),
                "BORN" to a.birthDate + if (a.age.isNotBlank() && a.birthDate.isNotBlank()) " (${a.age})" else "",
                "COLLEGE" to a.college,
            ).filter { it.second.isNotBlank() }
            facts.forEach { (k, v) ->
                Row(Modifier.padding(top = 3.dp)) {
                    Text(k, Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, color = sub)
                    Text(v, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** "2026 Regular Season Stats" strip: big numbers with league rank underneath. */
@Composable
private fun SummaryStrip(a: Athlete) {
    val band = ColorMath.parseHex(a.teamColor)?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val onBand = Color(ColorMath.onColorFor(ColorMath.parseHex(a.teamColor) ?: MaterialTheme.colorScheme.primary.toArgbSafe()))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column {
            Surface(color = band, modifier = Modifier.fillMaxWidth()) {
                Text(
                    a.summaryTitle.ifBlank { "Season Stats" }.uppercase(),
                    color = onBand,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                a.summary.take(4).forEach { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(s.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        if (s.rank.isNotBlank()) {
                            Text(s.rank, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun Color.toArgbSafe(): Int = ColorMath.argb(Math.round(red * 255), Math.round(green * 255), Math.round(blue * 255))

@Composable
private fun NewsRow(n: NewsItem) {
    Column {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(n.headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (n.description.isNotBlank()) {
                Text(
                    n.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val when_ = formatLocal(n.published)
            if (when_.isNotBlank()) {
                Text(when_, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun BioList(a: Athlete, league: League, open: (Route) -> Unit) {
    val rows = listOf(
        "Team" to a.teamName,
        "Position" to a.position,
        "Jersey" to if (a.jersey.isNotBlank()) "#${a.jersey}" else "",
        "Height" to a.height,
        "Weight" to a.weight,
        "Birthdate" to a.birthDate,
        "Age" to a.age,
        "Birthplace" to a.birthPlace,
        "College" to a.college,
        "Draft" to a.draft,
        "Experience" to a.experience,
        "Status" to a.status,
    ).filter { it.second.isNotBlank() }
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            rows.forEachIndexed { i, (k, v) ->
                val teamRow = k == "Team" && a.teamId.isNotEmpty()
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = teamRow) { open(Route.Team(league, a.teamId)) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(k, Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        v,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (teamRow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (i < rows.lastIndex) HorizontalDivider()
            }
        }
    }
}

private val gameDateFmt = java.time.format.DateTimeFormatter.ofPattern("M/d")

/** "9/27 vs NYJ  W 31-24" */
private fun gameLabel(g: GameLogEntry): String {
    val d = try {
        java.time.OffsetDateTime.parse(g.date).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(gameDateFmt)
    } catch (e: Exception) {
        ""
    }
    return listOf(d, g.opponent, g.result).filter { it.isNotBlank() }.joinToString("  ")
}


/** Up to 5 one-sentence season trends, as a bulleted card. */
@Composable
private fun TrendsCard(title: String, trends: List<String>) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            trends.forEach { t ->
                Row(Modifier.padding(top = 8.dp)) {
                    Box(
                        Modifier.padding(top = 7.dp).size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(t, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
