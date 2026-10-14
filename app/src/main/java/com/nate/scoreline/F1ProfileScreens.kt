@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

private fun teamHex(team: String) = "%06X".format(F1Teams.style(team).color and 0xFFFFFF)

// ---------------------------------------------------------------- driver

@Composable
fun F1DriverScreen(driverId: String, fallbackName: String, onBack: () -> Unit, open: (Route) -> Unit) {
    val p = rememberPolled<F1DriverProfile>("f1drv-$driverId", { 30 * 60_000L }) { F1Profiles.driver(driverId) }
    val career = rememberPolled<F1Career>("f1drv-career-$driverId", { 6 * 3600_000L }) { F1Profiles.driverCareer(driverId) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.state.data?.name ?: fallbackName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = scorelineTopBarColors(),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            LoadableContent(p) { d ->
                // formula1.com's own photo first; OpenF1's link only if that one is missing.
                val official = remember(d.name, d.team) { F1Profiles.officialPhoto(d.name, d.team, java.time.LocalDate.now().year) }
                var officialFailed by remember(official) { mutableStateOf(official == null) }
                val openF1 by produceState<String?>(null, officialFailed, d.name) {
                    if (officialFailed) value = runCatching { F1Profiles.headshot(d.name) }.getOrNull()
                }
                val photo = if (!officialFailed) official else openF1
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().background(teamFade(teamHex(d.team))).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DriverPhoto(photo.orEmpty(), 96.dp, onFail = { if (!officialFailed) officialFailed = true })
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(d.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(
                                    listOf(if (d.number.isNotBlank()) "#${d.number}" else "", d.code, d.nationality)
                                        .filter { it.isNotBlank() }.joinToString("  ·  "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (d.team.isNotBlank()) {
                                    Row(
                                        Modifier.padding(top = 6.dp).clickable(enabled = d.constructorId.isNotEmpty()) {
                                            open(Route.F1Team(d.constructorId, d.team))
                                        },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TeamBadge(d.team, 20.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(d.team, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        val podiums = d.races.count { (it.posText.toIntOrNull() ?: 99) <= 3 }
                        val best = d.races.mapNotNull { it.posText.toIntOrNull() }.minOrNull()
                        SummaryStrip(
                            "Season",
                            listOf(
                                "POS" to (if (d.standingPos.isNotBlank()) "P${d.standingPos}" else "-"),
                                "PTS" to d.points,
                                "WINS" to d.wins,
                                "PODIUMS" to podiums.toString(),
                                "BEST" to (best?.let { "P$it" } ?: "-"),
                            ),
                        )
                    }
                    item {
                        TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
                            listOf("Results", "Points", "Bio").forEachIndexed { i, s -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(s) }) }
                        }
                    }
                    when (tab) {
                        0 -> driverResults(d, open)
                        1 -> item {
                            PointsHistory(
                                d.races.map { Triple("R${it.round}", it.raceName, it.points) },
                            )
                        }
                        else -> item { DriverBio(d, career.state.data) }
                    }
                    item { SourceNote() }
                }
            }
        }
    }
}

private fun LazyListScope.driverResults(d: F1DriverProfile, open: (Route) -> Unit) {
    if (d.races.isEmpty()) {
        item { InlineNote("No races yet this season.") }
        return
    }
    item {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val st = MaterialTheme.typography.labelSmall
            val c = MaterialTheme.colorScheme.onSurfaceVariant
            Text("Race", Modifier.weight(1f), style = st, color = c)
            Text("Grid", Modifier.width(44.dp), textAlign = TextAlign.End, style = st, color = c)
            Text("Finish", Modifier.width(58.dp), textAlign = TextAlign.End, style = st, color = c)
            Text("Pts", Modifier.width(48.dp), textAlign = TextAlign.End, style = st, color = c)
        }
    }
    items(d.races.asReversed(), key = { it.round }) { r ->
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable { open(Route.F1Race(r.round, r.raceName, r.sprintPos != null)) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("R${r.round}  ${r.raceName.removeSuffix(" Grand Prix")} GP", style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(
                        gameDay(r.date + "T12:00:00Z").ifEmpty { null },
                        r.sprintPos?.let { "Sprint ${F1ProfileParse.finishLabel(it)}" },
                        if (!r.finished && r.status.isNotBlank()) r.status else null,
                    ).joinToString("  •  ")
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(r.grid.ifEmpty { "-" }.let { if (it == "0") "PL" else it }, Modifier.width(44.dp), textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    F1ProfileParse.finishLabel(r.posText), Modifier.width(58.dp), textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = if (r.posText == "1") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                PointsCell(r.points)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun DriverBio(d: F1DriverProfile, c: F1Career?) {
    val age = runCatching {
        java.time.Period.between(java.time.LocalDate.parse(d.dob), java.time.LocalDate.now()).years.toString()
    }.getOrNull()
    val born = runCatching {
        java.time.LocalDate.parse(d.dob).format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))
    }.getOrNull()
    InfoList(
        listOf(
            "Full name" to d.name,
            "Number" to d.number,
            "Code" to d.code,
            "Nationality" to d.nationality,
            "Born" to (born?.let { b -> age?.let { "$b (age $it)" } ?: b } ?: ""),
            "Team" to d.team,
            "Career starts" to (c?.starts?.toString() ?: if (c == null) "…" else ""),
            "Career wins" to (c?.wins?.toString() ?: if (c == null) "…" else ""),
            "Career poles" to (c?.poles?.toString() ?: if (c == null) "…" else ""),
            "World titles" to (c?.titles?.toString() ?: if (c == null) "…" else ""),
        ),
    )
}

// ---------------------------------------------------------------- team

@Composable
fun F1TeamScreen(constructorId: String, fallbackName: String, onBack: () -> Unit, open: (Route) -> Unit) {
    val p = rememberPolled<F1TeamProfile>("f1team-$constructorId", { 30 * 60_000L }) { F1Profiles.team(constructorId) }
    val career = rememberPolled<F1Career>("f1team-career-$constructorId", { 6 * 3600_000L }) { F1Profiles.teamCareer(constructorId) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.state.data?.name ?: fallbackName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = scorelineTopBarColors(),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            LoadableContent(p) { t ->
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().background(teamFade(teamHex(t.name))).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TeamBadge(t.name, 64.dp)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(t.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                if (t.nationality.isNotBlank()) {
                                    Text(t.nationality, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item {
                        val podiums = t.races.sumOf { r -> r.drivers.count { (it.third.toIntOrNull() ?: 99) <= 3 } }
                        SummaryStrip(
                            "Season",
                            listOf(
                                "POS" to (if (t.standingPos.isNotBlank()) "P${t.standingPos}" else "-"),
                                "PTS" to t.points,
                                "WINS" to t.wins.ifBlank { "-" },
                                "PODIUMS" to podiums.toString(),
                            ),
                        )
                    }
                    if (t.drivers.isNotEmpty()) {
                        item { SectionHeader("Drivers") }
                        items(t.drivers, key = { it.second }) { (name, id) ->
                            Column {
                                Row(
                                    Modifier.fillMaxWidth().clickable { open(Route.F1Driver(id, name)) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    item {
                        TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 12.dp)) {
                            listOf("Results", "Points", "Info").forEachIndexed { i, s -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(s) }) }
                        }
                    }
                    when (tab) {
                        0 -> teamResults(t, open)
                        1 -> item { PointsHistory(t.races.map { Triple("R${it.round}", it.raceName, it.points) }) }
                        else -> item {
                            val c = career.state.data
                            InfoList(
                                listOf(
                                    "Team" to t.name,
                                    "Nationality" to t.nationality,
                                    "Drivers this season" to t.drivers.joinToString(", ") { it.first },
                                    "All-time wins" to (c?.wins?.toString() ?: if (c == null) "…" else ""),
                                    "All-time poles" to (c?.poles?.toString() ?: if (c == null) "…" else ""),
                                    "Constructors' titles" to (c?.titles?.toString() ?: if (c == null) "…" else ""),
                                ),
                            )
                        }
                    }
                    item { SourceNote() }
                }
            }
        }
    }
}

private fun LazyListScope.teamResults(t: F1TeamProfile, open: (Route) -> Unit) {
    if (t.races.isEmpty()) {
        item { InlineNote("No races yet this season.") }
        return
    }
    items(t.races.asReversed(), key = { it.round }) { r ->
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable { open(Route.F1Race(r.round, r.raceName, r.hasSprint)) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("R${r.round}  ${r.raceName.removeSuffix(" Grand Prix")} GP", style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        r.drivers.joinToString("   ") { (name, _, pos) ->
                            "${name.substringAfterLast(' ')} ${F1ProfileParse.finishLabel(pos)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                PointsCell(r.points)
            }
            HorizontalDivider()
        }
    }
}

// ---------------------------------------------------------------- shared pieces

@Composable
private fun PointsCell(p: Double) {
    val scored = p > 0.0
    Text(
        if (scored) "+${F1ProfileParse.fmtPoints(p)}" else "0",
        Modifier.width(48.dp),
        textAlign = TextAlign.End,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (scored) FontWeight.Bold else FontWeight.Normal,
        color = if (scored) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SummaryStrip(title: String, stats: List<Pair<String, String>>) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(vertical = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                stats.forEach { (label, value) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoList(rows: List<Pair<String, String>>) {
    Column(Modifier.padding(vertical = 8.dp)) {
        rows.filter { it.second.isNotBlank() }.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(k, Modifier.width(150.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            HorizontalDivider()
        }
    }
}

/** Running points total across the season: a line chart, then each round's points and the total so far. */
@Composable
private fun PointsHistory(rounds: List<Triple<String, String, Double>>) {
    if (rounds.isEmpty()) {
        InlineNote("No points history yet.")
        return
    }
    val cumulative = rounds.runningFold(0.0) { acc, r -> acc + r.third }.drop(1)
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row {
                    Text("Points through the season", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(F1ProfileParse.fmtPoints(cumulative.last()), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = line)
                }
                val max = (cumulative.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
                Canvas(Modifier.fillMaxWidth().height(160.dp).padding(top = 12.dp)) {
                    val w = size.width
                    val h = size.height
                    // Light gridlines at quarters
                    for (i in 0..4) {
                        val y = h - h * i / 4f
                        drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                    }
                    val n = cumulative.size
                    fun x(i: Int) = if (n == 1) w / 2f else w * i / (n - 1).toFloat()
                    fun y(v: Double) = (h - h * (v / max)).toFloat()
                    val path = Path()
                    cumulative.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
                    drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    cumulative.forEachIndexed { i, v -> drawCircle(line, 3.5.dp.toPx(), Offset(x(i), y(v))) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(rounds.first().first, style = MaterialTheme.typography.labelSmall, color = label)
                    Spacer(Modifier.weight(1f))
                    Text(rounds.last().first, style = MaterialTheme.typography.labelSmall, color = label)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            val st = MaterialTheme.typography.labelSmall
            Text("Race", Modifier.weight(1f), style = st, color = label)
            Text("Pts", Modifier.width(48.dp), textAlign = TextAlign.End, style = st, color = label)
            Text("Total", Modifier.width(56.dp), textAlign = TextAlign.End, style = st, color = label)
        }
        rounds.indices.reversed().forEach { i ->
            val (r, name, pts) = rounds[i]
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$r  ${name.removeSuffix(" Grand Prix")} GP", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                PointsCell(pts)
                Text(F1ProfileParse.fmtPoints(cumulative[i]), Modifier.width(56.dp), textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
        }
    }
}

/**
 * Driver photo, asking formula1.com for its larger rendition ("4col") instead of the
 * thumbnail OpenF1 links to ("1col"), and falling back to the thumbnail if that fails.
 */
@Composable
private fun DriverPhoto(url: String, size: androidx.compose.ui.unit.Dp, onFail: () -> Unit = {}) {
    val hi = remember(url) { url.replace("/1col/", "/4col/") }
    var failed by remember(url) { mutableStateOf(false) }
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = rememberCrispRequest(if (failed || hi == url) url else hi, size, fill = true),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                filterQuality = FilterQuality.High,
                modifier = Modifier.size(size),
                onError = { if (!failed && hi != url) failed = true else onFail() },
            )
        }
    }
}
