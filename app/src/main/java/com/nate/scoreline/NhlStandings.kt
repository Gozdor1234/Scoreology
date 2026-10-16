package com.nate.scoreline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** One NHL team's line in the standings. */
data class NhlTeamRow(
    val teamId: String,
    val name: String,
    val shortName: String,
    val abbr: String,
    val logo: String,
    val conference: String,
    val division: String,
    val gp: Int,
    val w: Int,
    val l: Int,
    val otl: Int,
    val pts: Int,
    val rw: Int,
    val gf: Int,
    val ga: Int,
    val streak: String,
    val lastTen: String,
    /** ESPN's clinch mark: "x" playoff spot, "y" division, "z" conference, "p" Presidents' Trophy, "e" eliminated. */
    val clinch: String,
) {
    val diff get() = gf - ga
}

object NhlStandings {
    const val EAST = "Eastern"
    const val WEST = "Western"

    /** Current alignment, used when ESPN's response doesn't say which division a team is in. */
    private val divisionOf: Map<String, String> = buildMap {
        listOf("BOS", "BUF", "DET", "FLA", "MTL", "OTT", "TB", "TBL", "TOR").forEach { put(it, "Atlantic") }
        listOf("CAR", "CBJ", "NJ", "NJD", "NYI", "NYR", "PHI", "PIT", "WSH").forEach { put(it, "Metropolitan") }
        listOf("CHI", "COL", "DAL", "MIN", "NSH", "STL", "UTAH", "UTA", "WPG").forEach { put(it, "Central") }
        listOf("ANA", "CGY", "EDM", "LA", "LAK", "SJ", "SJS", "SEA", "VAN", "VGK").forEach { put(it, "Pacific") }
    }

    fun conferenceOf(division: String) = if (division == "Atlantic" || division == "Metropolitan") EAST else WEST

    /** "Atlantic Division" -> "Atlantic", "Eastern Conference" -> "Eastern". */
    private fun short(name: String) = name.replace(" Division", "").replace(" Conference", "").trim()

    suspend fun load(): List<NhlTeamRow> =
        parse(Net.getJson("https://site.api.espn.com/apis/v2/sports/hockey/nhl/standings?level=3"))

    /** Walks ESPN's nested standings (league > conference > division), whichever depth it returns. */
    fun parse(root: JSONObject): List<NhlTeamRow> {
        val out = LinkedHashMap<String, NhlTeamRow>()
        fun walk(node: JSONObject, path: List<String>) {
            val here = path + short(node.str("name").ifEmpty { node.str("abbreviation") })
            node.obj("standings")?.arr("entries").objects().forEach { e ->
                val row = parseEntry(e, here) ?: return@forEach
                out[row.teamId.ifEmpty { row.abbr }] = row
            }
            node.arr("children").objects().forEach { walk(it, here) }
        }
        walk(root, emptyList())
        return out.values.toList()
    }

    private fun parseEntry(e: JSONObject, path: List<String>): NhlTeamRow? {
        val t = e.obj("team") ?: return null
        val stats = e.arr("stats").objects()
        fun stat(vararg keys: String): JSONObject? = keys.firstNotNullOfOrNull { k ->
            stats.firstOrNull { s ->
                s.str("name").equals(k, true) || s.str("abbreviation").equals(k, true) || s.str("type").equals(k, true)
            }
        }
        fun int(vararg keys: String): Int = stat(*keys)?.let { s ->
            if (s.has("value") && !s.isNull("value")) s.optDouble("value", 0.0).toInt()
            else s.str("displayValue").toIntOrNull() ?: 0
        } ?: 0
        fun text(vararg keys: String): String = stat(*keys)?.let { it.str("displayValue").ifEmpty { it.str("summary") } } ?: ""
        val abbr = t.str("abbreviation")
        val fromPath = path.map { it }.lastOrNull { it in setOf("Atlantic", "Metropolitan", "Central", "Pacific") }
        val division = fromPath ?: divisionOf[abbr.uppercase()] ?: ""
        val confFromPath = path.lastOrNull { it == EAST || it == WEST }
        val logo = t.arr("logos").objects().firstOrNull()?.str("href").orEmpty()
            .ifEmpty { Espn.fallbackLogo(League.NHL, t.str("id"), abbr) }
        return NhlTeamRow(
            teamId = t.str("id"),
            name = t.str("displayName").ifEmpty { t.str("name") },
            shortName = t.str("shortDisplayName").ifEmpty { t.str("name") }.ifEmpty { abbr },
            abbr = abbr,
            logo = logo,
            conference = confFromPath ?: if (division.isNotEmpty()) conferenceOf(division) else "",
            division = division,
            gp = int("gamesPlayed", "GP"),
            w = int("wins", "W"),
            l = int("losses", "L"),
            otl = int("otLosses", "OTL", "overtimeLosses"),
            pts = int("points", "PTS"),
            rw = int("regulationWins", "RW"),
            gf = int("pointsFor", "GF", "goalsFor"),
            ga = int("pointsAgainst", "GA", "goalsAgainst"),
            streak = text("streak", "STRK"),
            lastTen = text("lastTen", "L10", "lasttengames"),
            clinch = text("clincher").lowercase().let { if (it == "-" || it == "*") "" else it },
        )
    }

    /** NHL order: points, then fewer games played (better points pace), regulation wins, wins. */
    val order: Comparator<NhlTeamRow> = compareByDescending<NhlTeamRow> { it.pts }
        .thenBy { it.gp }
        .thenByDescending { it.rw }
        .thenByDescending { it.w }
        .thenByDescending { it.diff }

    data class WildCard(
        val conference: String,
        /** Each division's top three, in division order. */
        val divisions: List<Pair<String, List<NhlTeamRow>>>,
        /** Everyone else in the conference; the first two hold the wild-card spots. */
        val wildCard: List<NhlTeamRow>,
    )

    fun wildCard(rows: List<NhlTeamRow>, conference: String): WildCard {
        val conf = rows.filter { it.conference == conference }
        val divs = conf.groupBy { it.division }.map { (d, list) -> d to list.sortedWith(order) }
            // The division whose leader has more points goes first.
            .sortedWith(compareBy(order) { it.second.first() })
        val top = divs.map { (d, list) -> d to list.take(3) }
        val inTop = top.flatMap { it.second }.map { it.teamId }.toSet()
        return WildCard(conference, top, conf.filter { it.teamId !in inTop }.sortedWith(order))
    }

    data class Series(val high: NhlTeamRow, val highSeed: String, val low: NhlTeamRow, val lowSeed: String)

    /**
     * First-round matchups if the season ended today: the division winner with more points
     * plays the second wild card, the other winner plays the first wild card, and the 2nd and
     * 3rd teams in each division play each other.
     */
    fun bracket(rows: List<NhlTeamRow>, conference: String): List<Series> {
        val wc = wildCard(rows, conference)
        if (wc.divisions.size < 2 || wc.wildCard.size < 2) return emptyList()
        val (aName, a) = wc.divisions[0]
        val (bName, b) = wc.divisions[1]
        if (a.size < 3 || b.size < 3) return emptyList()
        val w1 = wc.wildCard[0]
        val w2 = wc.wildCard[1]
        return listOf(
            Series(a[0], "${aName.take(1)}1", w2, "WC2"),
            Series(a[1], "${aName.take(1)}2", a[2], "${aName.take(1)}3"),
            Series(b[0], "${bName.take(1)}1", w1, "WC1"),
            Series(b[1], "${bName.take(1)}2", b[2], "${bName.take(1)}3"),
        )
    }
}

private val nhlViews = listOf("Wild Card", "Division", "Conference", "League", "Playoff picture")

@Composable
fun NhlStandingsView(open: (Route) -> Unit) {
    val fav = Favorites.get(LocalContext.current)
    var view by rememberSaveable { mutableIntStateOf(0) }
    val polled = rememberPolled<List<NhlTeamRow>>("nhl-standings", { 30 * 60_000L }) { NhlStandings.load() }
    Column {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            nhlViews.forEachIndexed { i, v -> FilterChip(selected = view == i, onClick = { view = i }, label = { Text(v) }) }
        }
        LoadableContent(polled) { rows ->
            if (rows.isEmpty()) {
                Message("No NHL standings published yet.")
                return@LoadableContent
            }
            val isFav = { r: NhlTeamRow -> fav.isFavTeam(League.NHL, r.teamId) }
            val onTeam = { r: NhlTeamRow -> open(Route.Team(League.NHL, r.teamId)) }
            val confs = listOf(NhlStandings.EAST, NhlStandings.WEST).filter { c -> rows.any { it.conference == c } }
            LazyColumn {
                when (view) {
                    0 -> confs.forEach { c ->
                        val wc = NhlStandings.wildCard(rows, c)
                        item { ConfTitle("$c Conference") }
                        wc.divisions.forEach { (d, list) ->
                            item { NhlHeader(d) }
                            items(list, key = { "wcd-${it.teamId}" }) { r -> NhlLine(list.indexOf(r) + 1, r, isFav(r)) { onTeam(r) } }
                        }
                        item { NhlHeader("Wild Card") }
                        wc.wildCard.forEachIndexed { i, r ->
                            item(key = "wc-${r.teamId}") {
                                NhlLine(i + 1, r, isFav(r), rankLabel = if (i < 2) "WC${i + 1}" else null) { onTeam(r) }
                                // Line under the second wild card: teams below are out as of today.
                                if (i == 1) HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    1 -> confs.forEach { c ->
                        item { ConfTitle("$c Conference") }
                        rows.filter { it.conference == c }.groupBy { it.division }
                            .forEach { (d, list) ->
                                val sorted = list.sortedWith(NhlStandings.order)
                                item { NhlHeader(d) }
                                items(sorted, key = { "div-${it.teamId}" }) { r -> NhlLine(sorted.indexOf(r) + 1, r, isFav(r)) { onTeam(r) } }
                            }
                    }
                    2 -> confs.forEach { c ->
                        val sorted = rows.filter { it.conference == c }.sortedWith(NhlStandings.order)
                        item { NhlHeader("$c Conference") }
                        items(sorted, key = { "conf-${it.teamId}" }) { r -> NhlLine(sorted.indexOf(r) + 1, r, isFav(r)) { onTeam(r) } }
                    }
                    3 -> {
                        val sorted = rows.sortedWith(NhlStandings.order)
                        item { NhlHeader("League (Presidents' Trophy race)") }
                        items(sorted, key = { "lg-${it.teamId}" }) { r -> NhlLine(sorted.indexOf(r) + 1, r, isFav(r)) { onTeam(r) } }
                    }
                    else -> {
                        item {
                            Text(
                                "Stanley Cup Playoffs first round if the season ended today.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
                            )
                        }
                        confs.forEach { c ->
                            item { ConfTitle("$c Conference") }
                            val series = NhlStandings.bracket(rows, c)
                            if (series.isEmpty()) item { Text("Not enough games yet.", Modifier.padding(16.dp)) }
                            items(series, key = { "po-${it.high.teamId}" }) { s -> SeriesCard(s, isFav, onTeam) }
                        }
                    }
                }
                item {
                    Text(
                        "x clinched playoff spot  ·  y clinched division  ·  z clinched conference  ·  p Presidents' Trophy  ·  e eliminated",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
    )
}

private val nhlCols = listOf("GP", "W", "L", "OTL", "PTS", "DIFF")

@Composable
private fun NhlHeader(title: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        nhlCols.forEach { c ->
            Text(
                c,
                Modifier.width(if (c == "DIFF") 40.dp else 32.dp),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NhlLine(rank: Int, r: NhlTeamRow, highlight: Boolean, rankLabel: String? = null, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().background(favTint(highlight)).clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                rankLabel ?: "$rank",
                Modifier.width(34.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Logo(r.logo, 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                (if (r.clinch.isNotEmpty()) "${r.clinch} - " else "") + r.shortName,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val diff = if (r.diff > 0) "+${r.diff}" else "${r.diff}"
            listOf("${r.gp}", "${r.w}", "${r.l}", "${r.otl}", "${r.pts}", diff).forEachIndexed { i, v ->
                Text(
                    v,
                    Modifier.width(if (i == 5) 40.dp else 32.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (i == 4) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun SeriesCard(s: NhlStandings.Series, isFav: (NhlTeamRow) -> Boolean, onTeam: (NhlTeamRow) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            listOf(s.high to s.highSeed, s.low to s.lowSeed).forEach { (r, seed) ->
                Row(
                    Modifier.fillMaxWidth().background(favTint(isFav(r))).clickable { onTeam(r) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(seed, Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Logo(r.logo, 24.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.pts} pts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
