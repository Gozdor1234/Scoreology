@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background

@Composable
fun F1Screen(modifier: Modifier, open: (Route) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val polled = rememberPolled<List<F1Weekend>>(
        key = "f1w",
        intervalMs = { w -> if (w?.any { e -> e.sessions.any { it.isLive } } == true) LIVE_MS else IDLE_MS },
    ) { F1.weekends() }
    // While a session is live, the first tab becomes the live page.
    val live = polled.state.data?.firstNotNullOfOrNull { w -> w.sessions.firstOrNull { it.isLive }?.let { w to it } }
    Column(modifier.fillMaxSize()) {
        SearchTabs(open) {
            TabRow(selectedTabIndex = tab) {
                listOf(if (live != null) "● Live" else "This weekend", "Season", "Last race").forEachIndexed { i, s ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = {
                        if (i == 0 && live != null) Text(s, color = LiveRed, fontWeight = FontWeight.SemiBold) else Text(s)
                    })
                }
            }
        }
        when (tab) {
            0 -> if (live != null) F1LiveView(live.first, live.second, polled.state.updatedAt, open) else WeekendView(polled)
            1 -> SeasonView(open)
            else -> LastRaceView(open)
        }
    }
}

@Composable
private fun WeekendView(polled: Polled<List<F1Weekend>>) {
    val fav = Favorites.get(LocalContext.current)

    // Tyre stints (OpenF1) and points (Jolpica), refreshed alongside the weekend data.
    val appCtx = LocalContext.current
    remember { F1Extras.init(appCtx) }
    var extras by remember { mutableStateOf<Map<String, SessionExtra>>(emptyMap()) }
    LaunchedEffect(polled.state.updatedAt) {
        val ws = polled.state.data ?: return@LaunchedEffect
        val m = HashMap<String, SessionExtra>()
        ws.forEach { w -> m.putAll(runCatching { F1Extras.load(w) }.getOrDefault(emptyMap())) }
        extras = m
    }

    LoadableContent(polled) { weekends ->
        if (weekends.isEmpty()) {
            Message("No race weekend listed right now.", action = "Refresh", onAction = polled.refresh)
            return@LoadableContent
        }
        LazyColumn {
            weekends.forEach { w ->
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                listOf(w.circuit, w.location).filter { it.isNotBlank() }.joinToString("  •  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = polled.refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    }
                }
                item(key = "circuit-${w.id}") { CircuitCard(w) }
                // Newest session first: Race, then Qualifying, then practice.
                items(w.sessions.sortedByDescending { it.date }, key = { "${w.id}-${it.id}" }) { s -> SessionCard(s, fav, extras[s.id]) }
            }
            item {
                Text(
                    agoText(polled.state.updatedAt) + "  •  Live order from ESPN (running order, not official timing). Tyres from OpenF1 after each session; points from Jolpica.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SessionCard(s: F1Session, fav: Favorites, extra: SessionExtra?) {
    var expanded by remember(s.id) { mutableStateOf(s.isLive) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                if (s.isLive) LiveBadge()
                Spacer(Modifier.weight(1f))
                Text(
                    if (s.state == "pre") formatLocal(s.date).ifEmpty { s.detail } else s.detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val shown = if (expanded) s.results else s.results.take(3)
            shown.forEach { e ->
                val isFav = fav.isFavDriver(e.driver)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("P${e.pos}", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Logo(e.flag, 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        e.driver + if (isFav) "  ♥" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isFav || e.winner) FontWeight.Bold else FontWeight.Normal,
                        color = if (isFav) LiveRed else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1.15f),
                    )
                    val key = F1.driverKey(e.driver)
                    // Tyres sit centered in their own column between the name and the points.
                    if (!extra?.tyres.isNullOrEmpty()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            extra?.tyres?.get(key)?.let { TyreStrip(it) }
                        }
                    }
                    extra?.points?.let { pts ->
                        val raw = pts[key] ?: "0"
                        val scored = (raw.toDoubleOrNull() ?: 0.0) > 0.0
                        Text(
                            if (scored) "+$raw" else raw,
                            Modifier.width(40.dp),
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (scored) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (scored) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (s.results.size > 3) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show top 3" else "Show all ${s.results.size}")
                }
            }
        }
    }
}

@Composable
private fun LastRaceView(open: (Route) -> Unit) {
    val fav = Favorites.get(LocalContext.current)
    val polled = rememberPolled<F1Race?>("f1last", { IDLE_MS * 2 }) { F1.lastRace() }
    val s = polled.state
    // F1Race? as T: a successful load that returns null means "no race yet this season".
    if (s.data == null && !s.loading && s.error == null) {
        Message("No completed race yet this season.")
        return
    }
    LoadableContent(polled) { race ->
        if (race == null) return@LoadableContent
        LazyColumn {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(race.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Round ${race.round}  •  ${race.circuit}  •  ${race.date}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            resultItems(race, fav, open)
            item { SourceNote() }
        }
    }
}

/** Loads the bundled circuit dataset once (off the main thread) and keeps it for the app's lifetime. */
object CircuitStore {
    @Volatile private var cache: List<Circuit>? = null
    fun all(ctx: android.content.Context): List<Circuit> = cache ?: synchronized(this) {
        cache ?: runCatching {
            val text = ctx.assets.open("f1-circuits.geojson").bufferedReader().use { it.readText() }
            Circuits.parse(org.json.JSONObject(text))
        }.getOrDefault(emptyList()).also { cache = it }
    }
}

/** Track map plus circuit facts, under the race title. Hidden if the circuit isn't in the dataset. */
@Composable
private fun CircuitCard(w: F1Weekend) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        CircuitInfo(w.circuit, w.location)
    }
}

/** Track map and facts for a circuit, or nothing if it isn't in the bundled dataset. */
@Composable
private fun CircuitInfo(circuitName: String, location: String, mapHeight: Dp = 200.dp) {
    val ctx = LocalContext.current
    val all by produceState<List<Circuit>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { CircuitStore.all(ctx) }
    }
    val list = all ?: return
    val city = location.substringBefore(",").trim()
    val c = remember(list, circuitName, city) { Circuits.find(list, circuitName, city) } ?: return
    val projected = remember(c.id) { Circuits.project(c.points) }
    val line = MaterialTheme.colorScheme.onSurface
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)

    run {
        Column(Modifier.padding(bottom = 10.dp)) {
            Canvas(Modifier.fillMaxWidth().height(mapHeight).padding(18.dp)) {
                val (pts, _) = projected
                val wU = pts.maxOf { it.first }.coerceAtLeast(1e-6f)
                val hU = pts.maxOf { it.second }.coerceAtLeast(1e-6f)
                val scale = minOf(size.width / wU, size.height / hU)
                val ox = (size.width - wU * scale) / 2f
                val oy = (size.height - hU * scale) / 2f
                val path = Path()
                pts.forEachIndexed { i, (x, y) ->
                    val px = ox + x * scale
                    val py = oy + y * scale
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                drawPath(path, glow, style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, line, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Text(
                c.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val facts = listOf(
                "Length" to if (c.lengthM > 0) "%.3f km".format(c.lengthM / 1000.0) else "",
                "First Grand Prix" to if (c.firstGp > 0) c.firstGp.toString() else "",
                "Location" to location.ifBlank { c.location },
                "Opened" to if (c.opened > 0) c.opened.toString() else "",
                "Altitude" to "${c.altitudeM} m",
                // Races run the fewest laps that exceed 305 km (Monaco: 260 km).
                "Race laps (approx.)" to if (c.lengthM > 0) {
                    val target = if ("monaco" in c.name.lowercase()) 260_000.0 else 305_000.0
                    (Math.floor(target / c.lengthM).toInt() + 1).toString()
                } else "",
            ).filter { it.second.isNotBlank() }
            // Two columns of facts
            val half = (facts.size + 1) / 2
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                listOf(facts.take(half), facts.drop(half)).forEach { col ->
                    Column(Modifier.weight(1f)) {
                        col.forEach { (k, v) ->
                            Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp))
                            Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Text(
                "Track map: f1-circuits by Tomislav Bacinger (MIT license)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** Tyre icons in fitting order, first set on the left. */
@Composable
private fun TyreStrip(tyres: List<Tyre>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        tyres.forEach { TyreIcon(it) }
    }
}

/**
 * Drawn tyre: black sidewall, a colored compound ring with a small gap at the top
 * (where the maker's name sits on the real thing), and a dark wheel with spokes.
 */
@Composable
private fun TyreIcon(t: Tyre, size: Dp = 18.dp) {
    val ring = tyreColor(t)
    Canvas(Modifier.size(size).semantics { contentDescription = t.name.lowercase() + " tyre" }) {
        val c = center
        val r = this.size.minDimension / 2f
        drawCircle(Color(0xFF111214), r, c)
        // Compound ring, open at the top.
        val ringR = r * 0.74f
        val stroke = r * 0.14f
        drawArc(
            color = ring,
            startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(c.x - ringR, c.y - ringR),
            size = Size(ringR * 2, ringR * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Short bar in the gap, like the logo band on the sidewall.
        drawLine(ring, Offset(c.x - r * 0.28f, c.y - ringR), Offset(c.x + r * 0.28f, c.y - ringR), strokeWidth = stroke * 0.8f)
        // Wheel and spokes.
        val wheelR = r * 0.46f
        drawCircle(Color(0xFF2E3136), wheelR, c)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            drawLine(
                Color(0xFF1A1C1F),
                c,
                Offset(c.x + (wheelR * 0.9f * Math.cos(a)).toFloat(), c.y + (wheelR * 0.9f * Math.sin(a)).toFloat()),
                strokeWidth = r * 0.07f,
            )
        }
        drawCircle(Color(0xFF1A1C1F), wheelR * 0.25f, c)
    }
}

private fun tyreColor(t: Tyre): Color = when (t) {
    Tyre.HARD -> Color.White
    Tyre.SOFT -> Color(0xFFFF3B30)
    Tyre.MEDIUM -> Color(0xFFFFD60A)
    Tyre.INTER -> Color(0xFF34C759)
    Tyre.WET -> Color(0xFF3A8DFF)
}

/** Result rows shared by Last race and the season race screen. */
fun LazyListScope.resultItems(race: F1Race, fav: Favorites, open: ((Route) -> Unit)? = null) {
    item { SimpleRow("Pos", "Driver", "", "Grid", "Pts", header = true) }
    items(race.results) { r ->
        SimpleRow(
            pos = r.pos,
            main = r.driver + if (r.fastestLap) "  (fastest lap)" else "",
            sub = "${r.team}  •  ${r.timeOrStatus}",
            a = r.grid,
            b = r.points,
            highlight = fav.isFavDriver(r.driver),
            leading = { TeamBadge(r.team) },
            onClick = if (open != null && r.driverId.isNotEmpty()) { { open(Route.F1Driver(r.driverId, r.driver)) } } else null,
        )
    }
}

/** Every round this season: results for completed races (tap for the full classification), dates for upcoming ones. */
@Composable
private fun SeasonView(open: (Route) -> Unit) {
    val polled = rememberPolled<List<F1Round>>("f1season", { IDLE_MS * 2 }) { F1.season() }
    var upcoming by rememberSaveable { mutableStateOf(false) }
    var expandedRound by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !upcoming, onClick = { upcoming = false }, label = { Text("Results") })
            FilterChip(selected = upcoming, onClick = { upcoming = true }, label = { Text("Upcoming") })
        }
        LoadableContent(polled) { rounds ->
            val now = java.time.Instant.now()
            fun started(r: F1Round) = r.winner != null ||
                (F1ExtrasParse.instant(r.dateIso.let { if ('T' in it) it else it + "T23:59:00Z" })?.isBefore(now) == true)
            val list = if (upcoming) rounds.filterNot(::started) else rounds.filter(::started).reversed()
            if (list.isEmpty()) {
                Message(if (upcoming) "No races left this season." else "No completed races yet this season.")
                return@LoadableContent
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(list, key = { it.round }) { r ->
                    val isOpen = upcoming && expandedRound == r.round
                    RoundCard(r, next = upcoming && r == list.first(), expandable = upcoming, expanded = isOpen) {
                        if (upcoming) expandedRound = if (isOpen) null else r.round
                        else open(Route.F1Race(r.round, r.name, r.hasSprint))
                    }
                }
                item { SourceNote() }
            }
        }
    }
}

@Composable
private fun RoundCard(r: F1Round, next: Boolean, expandable: Boolean = false, expanded: Boolean = false, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        // animateContentSize sits on the inner column: on the card itself it clips to a square
        // and cuts the Modern style's rounded shadow into sharp corners.
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        colors = if (next) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
      Column(Modifier.animateContentSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("R${r.round}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (r.hasSprint) Text("Sprint", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (next) {
                        Spacer(Modifier.width(8.dp))
                        Text("NEXT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    listOf(r.circuit, r.location).filter { it.isNotBlank() }.joinToString("  •  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    roundDate(r.dateIso),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (r.winner != null) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TeamBadge(r.winnerTeam ?: "", 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("🏆 ${r.winner}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                } else if ('T' in r.dateIso && F1ExtrasParse.instant(r.dateIso)?.isBefore(java.time.Instant.now()) == true) {
                    Text("Results pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (expandable) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) RaceDetails(r)
      }
    }
}

/** Dropdown under an upcoming race: countdown, weekend schedule, last winner here, and the track. */
@Composable
private fun RaceDetails(r: F1Round) {
    val start = F1ExtrasParse.instant(r.dateIso.let { if ('T' in it) it else it + "T12:00:00Z" })
    val year = start?.atOffset(java.time.ZoneOffset.UTC)?.year ?: java.time.LocalDate.now().year
    val prev by produceState<Triple<Int, String, String>?>(null, r.circuitId) {
        value = runCatching { F1.previousWinner(r.circuitId, year) }.getOrNull()
    }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(horizontal = 12.dp))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (start != null) {
                val d = java.time.Duration.between(java.time.Instant.now(), start)
                if (!d.isNegative) {
                    val days = d.toDays()
                    val hours = d.minusDays(days).toHours()
                    Text(
                        "Lights out in " + (if (days > 0) "$days day${if (days == 1L) "" else "s"}, " else "") +
                            "$hours hour${if (hours == 1L) "" else "s"}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (r.sessions.isNotEmpty()) {
                Text("Weekend schedule (your time)", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                r.sessions.forEach { (label, iso) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (label == "Race") FontWeight.Bold else FontWeight.Normal)
                        Text(roundDate(iso), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (label == "Race") FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            prev?.let { (y, driver, team) ->
                Text("Last winner here", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamBadge(team, 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("$driver ($y)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        CircuitInfo(r.circuit, r.location, mapHeight = 180.dp)
    }
}

/** "Sun, Sep 26 • 7:00 AM" in local time, or just the date when Jolpica has no start time. */
private fun roundDate(iso: String): String = runCatching {
    if ('T' in iso) {
        java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d  •  h:mm a"))
    } else {
        java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
}.getOrDefault(iso)

/** Full classification for one round, with a Sprint tab on sprint weekends. */
@Composable
fun F1RaceScreen(round: String, name: String, hasSprint: Boolean, onBack: () -> Unit, open: (Route) -> Unit = {}) {
    val fav = Favorites.get(LocalContext.current)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Missing results load as an empty race, so the screen shows a message instead of spinning.
    val empty = F1Race(name, round, "", "", emptyList())
    val race = rememberPolled<F1Race>("f1race-$round", { IDLE_MS * 6 }) { F1.raceResults(round) ?: empty }
    val sprint = if (hasSprint) rememberPolled<F1Race>("f1sprint-$round", { IDLE_MS * 6 }) { F1.sprintResults(round) ?: empty } else null
    val quali = rememberPolled<List<F1QualiRow>>("f1quali-$round", { IDLE_MS * 6 }) { F1.qualifying(round) }
    val tabs = if (hasSprint) listOf("Race", "Sprint", "Qualifying") else listOf("Race", "Qualifying")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = scorelineTopBarColors(),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab.coerceIn(0, tabs.lastIndex)) {
                tabs.forEachIndexed { i, s -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(s) }) }
            }
            if (tabs.getOrNull(tab) == "Qualifying") {
                QualifyingList(quali, fav)
                return@Column
            }
            val shown = if (tabs.getOrNull(tab) == "Sprint" && sprint != null) sprint else race
            LoadableContent(shown) { r ->
                if (r.results.isEmpty()) {
                    Message("Results aren't posted yet.")
                    return@LoadableContent
                }
                LazyColumn {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text("Round ${r.round}  •  ${r.circuit}  •  ${r.date}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    resultItems(r, fav, open)
                    item { SourceNote() }
                }
            }
        }
    }
}

/** Qualifying classification: best stage reached and that lap time; Q1/Q2/Q3 times underneath. */
@Composable
private fun QualifyingList(polled: Polled<List<F1QualiRow>>, fav: Favorites) {
    LoadableContent(polled) { rows ->
        if (rows.isEmpty()) {
            Message("Qualifying results aren't posted yet.")
            return@LoadableContent
        }
        LazyColumn {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val st = MaterialTheme.typography.labelSmall
                    val c = MaterialTheme.colorScheme.onSurfaceVariant
                    Text("Pos", Modifier.width(36.dp), style = st, color = c, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(46.dp))
                    Text("Driver", Modifier.weight(1f), style = st, color = c)
                    Text("Best", style = st, color = c)
                }
            }
            items(rows) { r ->
                val (stage, time) = r.best
                Column {
                    Row(
                        Modifier.fillMaxWidth().background(favTint(fav.isFavDriver(r.driver))).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(r.pos, Modifier.width(36.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        TeamBadge(r.team)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.driver, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf("Q1" to r.q1, "Q2" to r.q2, "Q3" to r.q3).filter { it.second.isNotBlank() }
                                    .joinToString("   ") { "${it.first} ${it.second}" }.ifEmpty { r.team },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(time, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            if (stage.isNotEmpty()) {
                                Text(stage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            item { SourceNote() }
        }
    }
}
