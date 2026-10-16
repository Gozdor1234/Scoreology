package com.nate.scoreline

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------- models

data class TeamInfo(
    val id: String,
    val name: String,
    val shortName: String,
    val abbr: String,
    val logo: String,
    val color: String,
    val altColor: String,
    val record: String,
    val standing: String,
)

data class RosterPlayer(
    val id: String,
    val name: String,
    val jersey: String,
    val position: String,
    val age: String,
    val height: String,
    val weight: String,
    val headshot: String,
    val college: String,
    val experience: String,
)

data class RosterGroup(val title: String, val players: List<RosterPlayer>)

data class TeamStatLine(val label: String, val value: String, val rank: String)
data class TeamStatCategory(val title: String, val stats: List<TeamStatLine>)

/** A season stat table built from box scores. rows[i] belongs to athleteIds[i]. */
data class SeasonTable(
    val key: String,
    val title: String,
    val labels: List<String>,
    val names: List<String>,
    val athleteIds: List<String>,
    val headshots: List<String>,
    val rows: List<List<String>>,
    val totals: List<String>,
)

data class Leader(val stat: String, val name: String, val athleteId: String, val headshot: String, val value: String)

data class TeamSeasonStats(val tables: List<SeasonTable>, val leaders: List<Leader>, val gamesCounted: Int)

data class SummaryStat(val label: String, val value: String, val rank: String)

data class Athlete(
    val id: String,
    val name: String,
    val firstName: String,
    val lastName: String,
    val jersey: String,
    val position: String,
    val teamId: String,
    val teamName: String,
    val teamAbbr: String,
    val teamLogo: String,
    val teamColor: String,
    val headshot: String,
    val height: String,
    val weight: String,
    val birthDate: String,
    val age: String,
    val birthPlace: String,
    val college: String,
    val draft: String,
    val experience: String,
    val status: String,
    val summaryTitle: String,
    val summary: List<SummaryStat>,
)

data class CareerTable(
    val title: String,
    val labels: List<String>,
    /** (season label, stats) */
    val rows: List<Pair<String, List<String>>>,
    val totals: List<String>,
    /** Team logo URL for each row (the team the player was on that season), "" if unknown. */
    val rowLogos: List<String> = emptyList(),
)

data class OverviewSplits(val title: String, val labels: List<String>, val rows: List<Pair<String, List<String>>>)
/** One game in a player's game log. */
data class GameLogEntry(
    val eventId: String,
    val date: String,
    /** "vs NYJ" or "@ BUF" */
    val opponent: String,
    val opponentLogo: String,
    /** "W 31-24" */
    val result: String,
    val stats: List<String>,
)

data class GameLogSection(val title: String, val games: List<GameLogEntry>)

/** Column group, e.g. "Rushing" spanning 5 columns. */
data class ColumnGroup(val title: String, val span: Int)

data class GameLog(
    val labels: List<String>,
    val groups: List<ColumnGroup>,
    val sections: List<GameLogSection>,
    /** (value for the request, label to show), newest first */
    val seasons: List<Pair<String, String>>,
    val selectedSeason: String,
)

data class NewsItem(val headline: String, val description: String, val published: String)
data class AthleteOverview(val splits: OverviewSplits?, val news: List<NewsItem>)

// ---------------------------------------------------------------- feeds

object TeamApi {
    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val COMMON = "https://site.web.api.espn.com/apis/common/v3/sports"

    suspend fun info(league: League, id: String) = parseInfo(Net.getJson("$SITE/${league.path}/teams/$id"))
    suspend fun schedule(league: League, id: String) =
        parseSchedule(Net.getJson("$SITE/${league.path}/teams/$id/schedule"), league)
    suspend fun roster(league: League, id: String) = parseRoster(Net.getJson("$SITE/${league.path}/teams/$id/roster"))
    suspend fun totals(league: League, id: String) = parseTotals(Net.getJson("$SITE/${league.path}/teams/$id/statistics"))

    suspend fun athlete(league: League, id: String) = parseAthlete(Net.getJson("$COMMON/${league.path}/athletes/$id"))
    suspend fun athleteStats(league: League, id: String) =
        parseAthleteStats(Net.getJson("$COMMON/${league.path}/athletes/$id/stats"), league)
    suspend fun gameLog(league: League, id: String, season: String?) =
        parseGameLog(Net.getJson("$COMMON/${league.path}/athletes/$id/gamelog" + if (season != null) "?season=$season" else ""))

    suspend fun athleteOverview(league: League, id: String) =
        parseOverview(Net.getJson("$COMMON/${league.path}/athletes/$id/overview"))

    /** Finished games never change, so their box scores are cached for the life of the app. */
    private val finishedSummaries = ConcurrentHashMap<String, GameDetail>()

    /** Season player stats: load every finished game's box score (in parallel) and add them up. */
    suspend fun seasonStats(league: League, teamId: String, games: List<Game>): TeamSeasonStats {
        val finished = games.filter { it.state == "post" }
        val details = supervisorScope {
            finished.map { g ->
                async {
                    finishedSummaries[g.id] ?: runCatching { Espn.summary(league, g.id) }.getOrNull()
                        ?.also { finishedSummaries[g.id] = it }
                }
            }.awaitAll().filterNotNull()
        }
        return StatMath.season(details, teamId, league)
    }

    fun headshotFallback(league: League, id: String): String = when {
        id.isEmpty() -> ""
        league == League.NFL -> "https://a.espncdn.com/i/headshots/nfl/players/full/$id.png"
        league == League.NHL -> "https://a.espncdn.com/i/headshots/nhl/players/full/$id.png"
        else -> "https://a.espncdn.com/i/headshots/college-football/players/full/$id.png"
    }

    // ------------------------------------------------------------ parsers

    fun parseInfo(root: JSONObject): TeamInfo {
        val t = root.obj("team") ?: root
        return TeamInfo(
            id = t.str("id"),
            name = t.str("displayName"),
            shortName = t.str("shortDisplayName").ifEmpty { t.str("name") },
            abbr = t.str("abbreviation"),
            logo = t.arr("logos").objects().firstOrNull()?.str("href") ?: t.str("logo"),
            color = t.str("color"),
            altColor = t.str("alternateColor"),
            record = t.obj("record")?.arr("items").objects().firstOrNull()?.str("summary")
                ?: t.str("recordSummary"),
            standing = t.str("standingSummary"),
        )
    }

    fun parseSchedule(root: JSONObject, league: League): List<Game> =
        root.arr("events").objects().mapNotNull { Espn.parseEvent(it, league) }.sortedBy { it.date }

    fun parseRoster(root: JSONObject): List<RosterGroup> {
        fun player(p: JSONObject) = RosterPlayer(
            id = p.str("id"),
            name = p.str("displayName").ifEmpty { p.str("fullName") },
            jersey = p.str("jersey"),
            position = p.obj("position")?.str("abbreviation") ?: "",
            age = p.numText("age"),
            height = p.str("displayHeight"),
            weight = p.str("displayWeight"),
            headshot = p.obj("headshot")?.str("href") ?: "",
            college = p.obj("college")?.str("name") ?: "",
            experience = p.obj("experience")?.numText("years") ?: "",
        )
        val raw = root.arr("athletes").objects()
        // NFL groups players (offense/defense/special teams); some college rosters are one flat list.
        val grouped = raw.filter { it.has("items") }
        return if (grouped.isNotEmpty()) {
            grouped.map { g ->
                val pos = g.optJSONObject("position")?.let { it.str("displayName").ifEmpty { it.str("name") } }
                    ?: g.str("position")
                RosterGroup(prettyGroup(pos), g.arr("items").objects().map { player(it) })
            }.filter { it.players.isNotEmpty() }
        } else {
            listOf(RosterGroup("Roster", raw.map { player(it) })).filter { it.players.isNotEmpty() }
        }
    }

    private fun prettyGroup(raw: String): String = when (raw.lowercase().replace(" ", "")) {
        "offense" -> "Offense"
        "defense" -> "Defense"
        "specialteam", "specialteams" -> "Special Teams"
        "injuredreserveorout" -> "Injured Reserve / Out"
        "suspended" -> "Suspended"
        "practicesquad" -> "Practice Squad"
        else -> raw.replaceFirstChar { it.uppercase() }.ifEmpty { "Roster" }
    }

    fun parseTotals(root: JSONObject): List<TeamStatCategory> {
        val cats = root.obj("results")?.obj("stats")?.arr("categories").objects()
        return cats.map { c ->
            TeamStatCategory(
                title = c.str("displayName").ifEmpty { c.str("name") },
                stats = c.arr("stats").objects().map { st ->
                    TeamStatLine(
                        label = st.str("displayName").ifEmpty { st.str("name") },
                        value = st.str("displayValue"),
                        rank = st.str("rankDisplayValue"),
                    )
                }.filter { it.value.isNotEmpty() },
            )
        }.filter { it.stats.isNotEmpty() }
    }

    fun parseAthlete(root: JSONObject): Athlete {
        val a = root.obj("athlete") ?: root
        val team = a.obj("team")
        val summary = a.obj("statsSummary")
        return Athlete(
            id = a.str("id"),
            name = a.str("displayName"),
            firstName = a.str("firstName"),
            lastName = a.str("lastName"),
            jersey = a.str("jersey"),
            position = a.obj("position")?.let { it.str("abbreviation").ifEmpty { it.str("displayName") } } ?: "",
            teamId = team?.str("id") ?: "",
            teamName = team?.str("displayName") ?: "",
            teamAbbr = team?.str("abbreviation") ?: "",
            teamLogo = team?.arr("logos").objects().firstOrNull()?.str("href") ?: team?.str("logo") ?: "",
            teamColor = team?.str("color") ?: "",
            headshot = a.obj("headshot")?.str("href") ?: "",
            height = a.str("displayHeight"),
            weight = a.str("displayWeight"),
            birthDate = a.str("displayDOB"),
            age = a.numText("age"),
            birthPlace = a.str("displayBirthPlace").ifEmpty {
                a.obj("birthPlace")?.let { bp -> listOf(bp.str("city"), bp.str("state"), bp.str("country")).filter { it.isNotBlank() }.joinToString(", ") } ?: ""
            },
            college = a.obj("college")?.let { it.str("name").ifEmpty { it.str("shortName") } } ?: "",
            draft = a.str("displayDraft"),
            experience = a.str("displayExperience"),
            status = a.obj("status")?.str("name") ?: "",
            summaryTitle = summary?.str("displayName") ?: "",
            summary = summary?.arr("statistics").objects().map { st ->
                SummaryStat(
                    label = st.str("abbreviation").ifEmpty { st.str("shortDisplayName") }.ifEmpty { st.str("displayName") },
                    value = st.str("displayValue"),
                    rank = st.str("rankDisplayValue"),
                )
            },
        )
    }

    fun parseAthleteStats(root: JSONObject, league: League? = null): List<CareerTable> {
        // Top-level "teams" lists every team in the player's career, with logos. Index by id and by key (slug).
        val teamLogos = HashMap<String, String>()
        root.obj("teams")?.let { teams ->
            val keys = teams.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val t = teams.obj(k) ?: continue
                val logo = t.arr("logos").objects().firstOrNull()?.str("href")?.ifEmpty { null } ?: t.str("logo")
                if (logo.isNotEmpty()) {
                    teamLogos[k] = logo
                    t.str("id").takeIf { it.isNotEmpty() }?.let { teamLogos[it] = logo }
                    t.str("slug").takeIf { it.isNotEmpty() }?.let { teamLogos[it] = logo }
                }
            }
        }
        fun logoFor(r: JSONObject): String {
            val id = r.str("teamId")
            return teamLogos[id] ?: teamLogos[r.str("teamSlug")]
                ?: if (league == League.CFB && id.isNotEmpty()) "https://a.espncdn.com/i/teamlogos/ncaa/500/$id.png" else ""
        }
        return root.arr("categories").objects().mapNotNull { c ->
            val raw = c.arr("statistics").objects()
            val rows = raw.map { r ->
                val season = r.obj("season")
                val label = season?.str("displayName")?.ifEmpty { null } ?: season?.numText("year") ?: ""
                label to r.arr("stats").strings()
            }
            if (rows.isEmpty()) return@mapNotNull null
            CareerTable(
                title = c.str("displayName").ifEmpty { c.str("name").replaceFirstChar { it.uppercase() } },
                labels = c.arr("labels").strings(),
                rows = rows,
                totals = c.arr("totals").strings(),
                rowLogos = raw.map(::logoFor),
            )
        }.filter { t -> t.rows.any { r -> r.second.any { it.isNotBlank() && it != "0" && it != "0.0" && it != "-" } } }
    }

    fun parseGameLog(root: JSONObject): GameLog {
        val labels = root.arr("labels").strings()
        val groups = root.arr("categories").objects().mapNotNull { c ->
            val span = c.optInt("count", 0)
            if (span <= 0) null else ColumnGroup(c.str("displayName").ifEmpty { c.str("name") }, span)
        }.takeIf { g -> g.sumOf { it.span } == labels.size } ?: emptyList() // only use groups that line up exactly
        val eventsObj = root.obj("events")
        fun entry(eventId: String, stats: List<String>): GameLogEntry {
            val e = eventsObj?.obj(eventId)
            val opp = e?.obj("opponent")
            val atVs = e?.str("atVs").orEmpty().ifEmpty { "vs" }
            val res = e?.str("gameResult").orEmpty()
            val score = e?.str("score").orEmpty()
            return GameLogEntry(
                eventId = eventId,
                date = e?.str("gameDate").orEmpty(),
                opponent = "$atVs ${opp?.str("abbreviation").orEmpty()}".trim(),
                opponentLogo = opp?.str("logo").orEmpty(),
                result = listOf(res, score).filter { it.isNotBlank() }.joinToString(" "),
                stats = stats,
            )
        }
        val sections = root.arr("seasonTypes").objects().mapNotNull { st ->
            val seen = HashSet<String>()
            val games = st.arr("categories").objects()
                .filter { it.str("type").let { t -> t.isEmpty() || t == "event" } } // skip monthly totals
                .flatMap { it.arr("events").objects() }
                .mapNotNull { ev ->
                    val id = ev.str("eventId")
                    if (id.isEmpty() || !seen.add(id)) null else entry(id, ev.arr("stats").strings())
                }
                .sortedByDescending { it.date }
            if (games.isEmpty()) null else GameLogSection(st.str("displayName"), games)
        }
        // Season picker: the filter whose name mentions "season", else the first one.
        val filters = root.arr("filters").objects()
        val f = filters.firstOrNull { it.str("name").contains("season", true) } ?: filters.firstOrNull()
        val seasons = f?.arr("options").objects()
            ?.map { it.str("value") to it.str("displayValue").ifEmpty { it.str("value") } }
            ?.filter { it.first.isNotEmpty() }
            .orEmpty()
        return GameLog(labels, groups, sections, seasons, f?.str("value").orEmpty().ifEmpty { seasons.firstOrNull()?.first.orEmpty() })
    }

    fun parseOverview(root: JSONObject): AthleteOverview {
        val st = root.obj("statistics")
        val splits = st?.let { s ->
            val rows = s.arr("splits").objects().map { it.str("displayName") to it.arr("stats").strings() }
            if (rows.isEmpty()) null else OverviewSplits(s.str("displayName"), s.arr("labels").strings(), rows)
        }
        val news = root.arr("news").objects().map {
            NewsItem(it.str("headline"), it.str("description"), it.str("published"))
        }.filter { it.headline.isNotBlank() }
        return AthleteOverview(splits, news)
    }
}

// ---------------------------------------------------------------- season totals from box scores

/**
 * Adds up box-score lines into season lines. Column rules, by label:
 * "a/b" values (C/ATT, FG, XP) sum each part; "a-b" values (sacks-yards) sum each part;
 * LONG/LNG take the max; AVG is recomputed as YDS / first column (attempts, carries, catches...);
 * PCT is recomputed from FG made/attempted; RTG is recomputed with the NFL passer-rating formula
 * (college box scores use a different efficiency formula, so it's left blank there);
 * everything else is summed.
 */
object StatMath {
    private val slash = Regex("""^(\d+)/(\d+)$""")
    private val dash = Regex("""^(\d+)-(\d+)$""")

    fun num(s: String): Double? = s.replace(",", "").trim().toDoubleOrNull()

    private fun fmt(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e9) d.toLong().toString() else "%.1f".format(d)

    /** Combine the same column across several games. */
    fun combine(labels: List<String>, lines: List<List<String>>, nflRating: Boolean): List<String> {
        if (lines.isEmpty()) return emptyList()
        val width = labels.size
        val out = MutableList(width) { "" }
        for (i in 0 until width) {
            val col = lines.mapNotNull { it.getOrNull(i) }.filter { it.isNotBlank() && it != "--" && it != "-" }
            val label = labels[i].uppercase()
            out[i] = when {
                col.isEmpty() -> ""
                col.all { slash.matches(it) } -> {
                    val parts = col.map { slash.find(it)!!.groupValues }
                    "${parts.sumOf { it[1].toInt() }}/${parts.sumOf { it[2].toInt() }}"
                }
                col.all { dash.matches(it) } -> {
                    val parts = col.map { dash.find(it)!!.groupValues }
                    "${parts.sumOf { it[1].toInt() }}-${parts.sumOf { it[2].toInt() }}"
                }
                label == "LONG" || label == "LNG" -> col.mapNotNull { num(it) }.maxOrNull()?.let { fmt(it) } ?: ""
                label == "AVG" || label == "PCT" || label == "RTG" || label == "QBR" -> "" // recomputed below
                // Hockey: percentages and averages are recomputed below; ice time and "year to date" columns don't add up.
                label.endsWith("%") || label == "GAA" || label.contains("TOI") || label.startsWith("YTD") -> ""
                else -> col.mapNotNull { num(it) }.let { if (it.isEmpty()) "" else fmt(it.sum()) }
            }
        }
        val idx = labels.map { it.uppercase() }
        fun at(name: String) = idx.indexOf(name).takeIf { it >= 0 }
        // AVG = YDS / count, where count is the first column (ATT part of C/ATT for passing).
        at("AVG")?.let { a ->
            val yds = at("YDS")?.let { num(out[it]) }
            val first = out.firstOrNull()?.let { f -> slash.find(f)?.groupValues?.get(2)?.toDoubleOrNull() ?: num(f) }
            out[a] = if (yds != null && first != null && first > 0) "%.1f".format(yds / first) else ""
        }
        at("FO%")?.let { p ->
            val w = at("FW")?.let { num(out[it]) }
            val l = at("FL")?.let { num(out[it]) }
            out[p] = if (w != null && l != null && w + l > 0) "%.1f".format(w * 100 / (w + l)) else ""
        }
        at("SV%")?.let { p ->
            val sv = at("SV")?.let { num(out[it]) }
            val sa = at("SA")?.let { num(out[it]) }
            out[p] = if (sv != null && sa != null && sa > 0) "%.3f".format(sv / sa) else ""
        }
        at("PCT")?.let { p ->
            val fg = at("FG")?.let { slash.find(out[it])?.groupValues }
            out[p] = if (fg != null && fg[2].toInt() > 0) "%.1f".format(fg[1].toDouble() * 100 / fg[2].toDouble()) else ""
        }
        at("RTG")?.let { r ->
            val ca = at("C/ATT")?.let { slash.find(out[it])?.groupValues }
            val yds = at("YDS")?.let { num(out[it]) }
            val td = at("TD")?.let { num(out[it]) }
            val ints = at("INT")?.let { num(out[it]) }
            out[r] = if (nflRating && ca != null && yds != null && td != null && ints != null && ca[2].toInt() > 0) {
                "%.1f".format(passerRating(ca[1].toDouble(), ca[2].toDouble(), yds, td, ints))
            } else ""
        }
        return out
    }

    /** Official NFL passer rating. */
    fun passerRating(comp: Double, att: Double, yds: Double, td: Double, ints: Double): Double {
        fun clamp(x: Double) = x.coerceIn(0.0, 2.375)
        val a = clamp((comp / att - 0.3) * 5)
        val b = clamp((yds / att - 3) * 0.25)
        val c = clamp(td / att * 20)
        val d = clamp(2.375 - ints / att * 25)
        return (a + b + c + d) / 6 * 100
    }

    private val categoryOrder = listOf(
        "passing", "rushing", "receiving", "defensive", "interceptions", "fumbles",
        "kicking", "punting", "kickReturns", "puntReturns",
    )
    private val niceTitle = mapOf(
        "passing" to "Passing", "rushing" to "Rushing", "receiving" to "Receiving", "defensive" to "Defense",
        "interceptions" to "Interceptions", "fumbles" to "Fumbles", "kicking" to "Kicking", "punting" to "Punting",
        "kickReturns" to "Kick Returns", "puntReturns" to "Punt Returns",
    )

    fun season(details: List<GameDetail>, teamId: String, league: League): TeamSeasonStats {
        val nfl = league == League.NFL
        // category key -> labels, and per player: name, headshot, lines
        data class Acc(var name: String, var headshot: String, val lines: MutableList<List<String>>, var games: Int)
        val labelsByCat = LinkedHashMap<String, List<String>>()
        val players = HashMap<String, LinkedHashMap<String, Acc>>()
        val teamLines = HashMap<String, MutableList<List<String>>>()
        for (d in details) {
            for (t in d.players) {
                if (t.teamId != teamId || t.key.isEmpty()) continue
                val labels = labelsByCat.getOrPut(t.key) { t.labels }
                if (labels != t.labels) continue // column layout changed; skip rather than misalign
                val cat = players.getOrPut(t.key) { LinkedHashMap() }
                t.rows.forEachIndexed { i, (display, stats) ->
                    val id = t.athleteIds.getOrNull(i).orEmpty()
                    val name = t.names.getOrNull(i) ?: display
                    val k = id.ifEmpty { "name:$name" }
                    val acc = cat.getOrPut(k) { Acc(name, "", mutableListOf(), 0) }
                    acc.lines += stats
                    acc.games += 1
                    if (acc.headshot.isEmpty()) {
                        acc.headshot = t.headshots.getOrNull(i).orEmpty().ifEmpty { TeamApi.headshotFallback(league, id) }
                    }
                }
                if (t.totals.isNotEmpty()) teamLines.getOrPut(t.key) { mutableListOf() } += t.totals
            }
        }
        val tables = labelsByCat.keys
            .sortedBy { k -> categoryOrder.indexOf(k).let { if (it < 0) 99 else it } }
            .map { key ->
                val labels = labelsByCat.getValue(key)
                val accs = players[key].orEmpty()
                val built = accs.map { (id, acc) -> Triple(id, acc, combine(labels, acc.lines, nfl)) }
                val sortCol = labels.indexOfFirst { it.equals("YDS", true) }.takeIf { it >= 0 }
                    ?: labels.indexOfFirst { it.equals("PTS", true) }.takeIf { it >= 0 }
                    ?: labels.indexOfFirst { it.equals("SV", true) }.takeIf { it >= 0 }
                    ?: labels.indexOfFirst { it.equals("G", true) }.takeIf { it >= 0 }
                    ?: labels.indexOfFirst { it.equals("TOT", true) }.takeIf { it >= 0 } ?: 0
                val sorted = built.sortedByDescending { (_, _, line) ->
                    line.getOrNull(sortCol)?.let { c -> slash.find(c)?.groupValues?.get(1)?.toDoubleOrNull() ?: num(c) } ?: -1.0
                }
                SeasonTable(
                    key = key,
                    title = niceTitle[key] ?: key.replaceFirstChar { it.uppercase() },
                    labels = listOf("GP") + labels,
                    names = sorted.map { it.second.name },
                    athleteIds = sorted.map { (id, _, _) -> if (id.startsWith("name:")) "" else id },
                    headshots = sorted.map { it.second.headshot },
                    rows = sorted.map { (_, acc, line) -> listOf(acc.games.toString()) + line },
                    totals = teamLines[key]?.let { listOf(details.size.toString()) + combine(labels, it, nfl) } ?: emptyList(),
                )
            }
        fun leader(key: String, stat: String): Leader? {
            val t = tables.firstOrNull { it.key == key } ?: return null
            val col = t.labels.indexOfFirst { it.equals("YDS", true) }
            if (col < 0 || t.rows.isEmpty()) return null
            return Leader(stat, t.names[0], t.athleteIds[0], t.headshots[0], t.rows[0][col])
        }
        val leaders = listOfNotNull(
            leader("passing", "Passing Yards"),
            leader("rushing", "Rushing Yards"),
            leader("receiving", "Receiving Yards"),
        )
        return TeamSeasonStats(tables, leaders, details.size)
    }
}
