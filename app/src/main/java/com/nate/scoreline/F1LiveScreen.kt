package com.nate.scoreline

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import kotlin.math.abs

private val Gain = Color(0xFF2E7D32)
private val Loss = Color(0xFFC62828)

/** Standings and starting grid for the live page; refreshed every 30 minutes. */
private data class LiveContext(val standings: List<F1DriverStanding>, val grid: Map<String, Int>)

private suspend fun loadContext(s: F1Session, kind: String?): LiveContext = coroutineScope {
    val standings = async { runCatching { F1.driverStandings() }.getOrDefault(emptyList()) }
    // Grid = qualifying order for this weekend's race (Jolpica round matched by race date).
    val grid = if (kind != "race") emptyMap() else runCatching {
        val day = OffsetDateTime.parse(s.date).toLocalDate()
        val round = F1.season().minByOrNull { r ->
            runCatching { abs(java.time.LocalDate.parse(r.dateIso.take(10)).toEpochDay() - day.toEpochDay()) }.getOrDefault(99)
        }?.takeIf { r -> abs(java.time.LocalDate.parse(r.dateIso.take(10)).toEpochDay() - day.toEpochDay()) <= 1 }
        round?.let { r -> F1.qualifying(r.round).associate { F1.driverKey(it.driver) to (it.pos.toIntOrNull() ?: 0) } }.orEmpty()
    }.getOrDefault(emptyMap())
    LiveContext(standings.await(), grid)
}

/** Current temperature (°F) and conditions at the track, from Open-Meteo. */
private suspend fun trackWeather(lat: Double, lon: Double): GameWeather? = runCatching {
    val cur = Net.getJson(
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&temperature_unit=fahrenheit",
    ).obj("current") ?: return null
    val code = cur.optInt("weather_code")
    GameWeather(cur.optDouble("temperature_2m").let { if (it.isNaN()) null else Math.round(it).toInt() }, WeatherData.wmoText(code), WeatherData.wmoSky(code), cur.optInt("is_day", 1) == 0)
}.getOrNull()

/** Shown in place of "This weekend" while any F1 session is live. */
@Composable
fun F1LiveView(w: F1Weekend, s: F1Session, updatedAt: Long, open: (Route) -> Unit) {
    val ctx = LocalContext.current
    val fav = Favorites.get(ctx)
    val kind = F1ExtrasParse.scoringKind(null, s.name)
    val ctxP = rememberPolled<LiveContext>("f1live-${s.id}", { 30 * 60_000L }) { loadContext(s, kind) }
    val lc = ctxP.state.data
    val rows = remember(s.results, lc) { F1Live.rows(s.results, kind, lc?.standings.orEmpty(), lc?.grid.orEmpty()) }

    // Highlight drivers who moved since the previous update.
    var prev by remember(s.id) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var moved by remember(s.id) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(rows) {
        moved = F1Live.changed(prev, rows)
        prev = rows.associate { it.driver to it.pos }
    }

    val circuit by produceState<Circuit?>(null, w.circuit) {
        value = withContext(Dispatchers.IO) {
            Circuits.find(CircuitStore.all(ctx), w.circuit, w.location.substringBefore(",").trim())
        }
    }
    val weather by produceState<GameWeather?>(null, circuit?.id) {
        val p = circuit?.points?.firstOrNull() ?: return@produceState
        value = trackWeather(p.second, p.first)
    }
    // Ticking clock since the session started.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val started = runCatching { OffsetDateTime.parse(s.date).toInstant().toEpochMilli() }.getOrNull()

    LazyColumn {
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Surface(color = LiveRed.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp)) {
                        Text("●  LIVE  ·  ${s.name.uppercase()}", color = LiveRed, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Text(w.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text(listOf(w.circuit, w.location).filter { it.isNotBlank() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (started != null && now >= started) Chip("Started ${elapsed(now - started)} ago")
                        weather?.let { wx ->
                            Chip(listOfNotNull(wx.tempF?.let { "$it°" }, wx.text).joinToString(" ")) {
                                WeatherIcon(wx.sky, wx.night, Modifier.size(14.dp))
                            }
                        }
                        circuit?.takeIf { it.lengthM > 0 }?.let { c ->
                            val len = "%.3f km".format(c.lengthM / 1000.0)
                            if (kind == "race") {
                                val target = if ("monaco" in c.name.lowercase()) 260_000.0 else 305_000.0
                                Chip("${Math.floor(target / c.lengthM).toInt() + 1} laps  ·  $len")
                            } else Chip(len)
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(vertical = 12.dp)) {
                    CardTitle("Running order", agoText(updatedAt))
                    val hasGrid = rows.any { it.grid != null }
                    val hasPts = kind != null
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Head("POS", 34); if (hasGrid) Head("+/-", 34)
                        Text("DRIVER", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (hasGrid) Head("GRID", 38, TextAlign.End); if (hasPts) Head("PTS", 38, TextAlign.End)
                    }
                    rows.forEach { r ->
                        val isFav = fav.isFavDriver(r.driver)
                        val bg = when {
                            r.driver in moved -> Gain.copy(alpha = 0.12f)
                            isFav -> MaterialTheme.colorScheme.secondaryContainer
                            else -> Color.Transparent
                        }
                        Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("P${r.pos}", Modifier.width(34.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            if (hasGrid) Delta(r.gained, 34)
                            Box(Modifier.width(4.dp).height(26.dp).background(Color(F1Teams.style(r.team).color), RoundedCornerShape(2.dp)))
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(r.driver, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (r.team.isNotEmpty()) Text(r.team, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (hasGrid) Text(r.grid?.let { "P$it" }.orEmpty(), Modifier.width(38.dp), textAlign = TextAlign.End,
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (hasPts) Text(if (r.points > 0) "+${r.points}" else "", Modifier.width(38.dp), textAlign = TextAlign.End,
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Gain)
                        }
                    }
                    Note(buildString {
                        append("Green row: changed position since the last update.")
                        if (hasGrid) append(" +/- is places gained or lost vs. the starting grid.")
                    })
                }
            }
        }

        val standings = lc?.standings.orEmpty()
        if (kind != null && standings.isNotEmpty()) {
            item {
                val champ = F1Live.championship(standings, rows).take(10)
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        CardTitle("Championship if it ended now", "drivers")
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Head("", 34); Head("", 34)
                            Text("DRIVER", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Head("NOW", 44, TextAlign.End); Head(if (kind == "sprint") "SPRINT" else "RACE", 48, TextAlign.End); Head("LIVE", 48, TextAlign.End)
                        }
                        champ.forEach { c ->
                            Row(Modifier.fillMaxWidth().background(favTint(fav.isFavDriver(c.name))).padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${c.rankLive}", Modifier.width(34.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Delta(c.rankNow - c.rankLive, 34)
                                Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${c.now}", Modifier.width(44.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (c.add > 0) "+${c.add}" else "", Modifier.width(48.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall, color = Gain, fontWeight = FontWeight.Medium)
                                Text("${c.total}", Modifier.width(48.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        val withGrid = rows.filter { it.gained != null }
        if (withGrid.isNotEmpty()) {
            item {
                val up = withGrid.filter { it.gained!! > 0 }.sortedByDescending { it.gained }.take(3)
                val down = withGrid.filter { it.gained!! < 0 }.sortedBy { it.gained }.take(3)
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        CardTitle("Biggest movers", "vs. grid")
                        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MoverBox("GAINED", up.joinToString("  ·  ") { "${lastName(it.driver)} ▲${it.gained}" }, Modifier.weight(1f))
                            MoverBox("LOST", down.joinToString("  ·  ") { "${lastName(it.driver)} ▼${-it.gained!!}" }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        val mates = F1Live.teammates(rows)
        if (mates.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        CardTitle("Teammate battles", "")
                        mates.chunked(2).forEach { pair ->
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { (a, b) -> MateBox(a, b, Modifier.weight(1f)) }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        item {
            Note(
                "Running order from ESPN (not official timing)." +
                    (if (kind == "race") " Grid from qualifying." else "") +
                    (if (kind != null) " Points from Jolpica standings plus this session's points table." else "") +
                    " Weather: Open-Meteo.",
                Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}

private fun lastName(n: String) = n.trim().substringAfterLast(' ')

private fun elapsed(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Chip(text: String, icon: (@Composable () -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { icon(); Spacer(Modifier.width(5.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CardTitle(title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Head(text: String, widthDp: Int, align: TextAlign = TextAlign.Start) {
    Text(text, Modifier.width(widthDp.dp), textAlign = align, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Delta(d: Int?, widthDp: Int) {
    val (t, c) = when {
        d == null -> "" to Color.Unspecified
        d > 0 -> "▲$d" to Gain
        d < 0 -> "▼${-d}" to Loss
        else -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(t, Modifier.width(widthDp.dp), color = c, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MoverBox(label: String, text: String, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text.ifEmpty { "None yet" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun MateBox(a: LiveRow, b: LiveRow, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Row(Modifier.padding(10.dp)) {
            Box(Modifier.width(4.dp).height(32.dp).background(Color(F1Teams.style(a.team).color), RoundedCornerShape(2.dp)))
            Column(Modifier.padding(start = 8.dp)) {
                Text("${lastName(a.driver)} P${a.pos} ahead of ${lastName(b.driver)} P${b.pos}", style = MaterialTheme.typography.bodySmall)
                Text(a.team, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

