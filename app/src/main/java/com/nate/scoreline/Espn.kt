package com.nate.scoreline

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject

enum class League(val label: String, val path: String, val defaultGroup: String?, val regularWeeks: Int) {
    NFL("NFL", "football/nfl", null, 18),
    // groups=80 = all FBS games; without it ESPN only returns a curated subset.
    CFB("College", "football/college-football", Conferences.FBS, 16),
    NHL("NHL", "hockey/nhl", null, 0);

    val isFootball get() = this != NHL
    val isHockey get() = this == NHL

    companion object {
        val football = listOf(NFL, CFB)
    }
}

/** ESPN group ids for FBS conferences (same ids in scoreboard and standings). */
object Conferences {
    const val FBS = "80"
    val all: List<Pair<String, String>> = listOf(
        "1" to "ACC",
        "151" to "American",
        "4" to "Big 12",
        "5" to "Big Ten",
        "12" to "C-USA",
        "18" to "FBS Independents",
        "15" to "MAC",
        "17" to "Mountain West",
        "9" to "Pac-12",
        "8" to "SEC",
        "37" to "Sun Belt",
    )
    fun name(id: String?): String = all.firstOrNull { it.first == id }?.second ?: "Conference"
}

data class TeamSide(
    val id: String,
    val abbr: String,
    val name: String,
    val logo: String,
    val score: String,
    val record: String,
    val rank: Int?,
    val winner: Boolean,
    val homeAway: String,
    val linescores: List<String>,
    /** Nickname, e.g. "Lions" (ESPN shortDisplayName). */
    val shortName: String = "",
    /** Team colors as hex without '#', e.g. "0076b6". Empty when ESPN leaves them out. */
    val color: String = "",
    val altColor: String = "",
)

data class Game(
    val id: String,
    val league: League,
    val date: String,
    /** "pre", "in" or "post" */
    val state: String,
    val detail: String,
    val away: TeamSide,
    val home: TeamSide,
    val possessionTeamId: String?,
    val downDistance: String,
    val broadcast: String,
    /** Pre-game lines from ESPN's feed (DraftKings), if posted. */
    val odds: GameOdds? = null,
    /** Game-time forecast from ESPN, when posted. */
    val weather: GameWeather? = null,
    val venue: GameVenue? = null,
) {
    val isLive get() = state == "in"
    val matchup get() = "${away.abbr} @ ${home.abbr}"
}

data class WeekInfo(val seasonType: Int, val week: Int) {
    val label: String
        get() = when (seasonType) {
            1 -> "Preseason wk $week"
            3 -> "Postseason wk $week"
            else -> "Week $week"
        }
}

data class Scoreboard(val games: List<Game>, val week: WeekInfo?)

data class StatLine(val label: String, val away: String, val home: String)

data class PlayerTable(
    val teamAbbr: String,
    val title: String,
    val labels: List<String>,
    val rows: List<Pair<String, List<String>>>,
    val totals: List<String>,
    /** Category id, e.g. "passing"; same across games, used to combine box scores. */
    val key: String = "",
    val teamId: String = "",
    /** Parallel to rows: ESPN athlete id and headshot URL (may be empty). */
    val athleteIds: List<String> = emptyList(),
    val headshots: List<String> = emptyList(),
    /** Parallel to rows: plain display name without jersey. */
    val names: List<String> = emptyList(),
)

data class ScoringPlay(
    val period: Int,
    val clock: String,
    val team: String,
    val text: String,
    val awayScore: String,
    val homeScore: String,
)

data class Play(
    val id: String,
    val period: Int,
    val clock: String,
    val downDistance: String,
    val text: String,
    val type: String,
    val awayScore: String,
    val homeScore: String,
    val scoring: Boolean,
)

data class Drive(
    val id: String,
    val team: String,
    val logo: String,
    val summary: String,
    val result: String,
    val isScore: Boolean,
    val inProgress: Boolean,
    /** Newest play first. */
    val plays: List<Play>,
)

data class GameDetail(
    val game: Game?,
    val teamStats: List<StatLine>,
    val players: List<PlayerTable>,
    val scoring: List<ScoringPlay>,
    /** Newest drive first. */
    val drives: List<Drive> = emptyList(),
    /** Team colors from the box score (teamId -> hex), used when the header lacks them. */
    val teamColors: Map<String, String> = emptyMap(),
)

data class StandingRow(val teamId: String, val name: String, val abbr: String, val logo: String, val cols: List<String>)
data class StandingGroup(val id: String, val title: String, val headers: List<String>, val rows: List<StandingRow>)

data class TeamRef(val league: League, val id: String, val name: String, val abbr: String, val logo: String)

object Espn {
    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val V2 = "https://site.api.espn.com/apis/v2/sports"

    fun scoreboardUrl(league: League, week: WeekInfo?, group: String? = league.defaultGroup): String {
        val params = mutableListOf<String>()
        if (group != null) params += "groups=$group"
        if (league == League.CFB) params += "limit=300"
        if (week != null) {
            params += "seasontype=${week.seasonType}"
            params += "week=${week.week}"
        }
        return "$SITE/${league.path}/scoreboard" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    suspend fun scoreboard(league: League, week: WeekInfo? = null, group: String? = league.defaultGroup): Scoreboard =
        parseScoreboard(Net.getJson(scoreboardUrl(league, week, group)), league)

    /** One day's games (hockey and other daily sports). [yyyymmdd] like "20261016". */
    suspend fun scoreboardOn(league: League, yyyymmdd: String): Scoreboard =
        parseScoreboard(Net.getJson("$SITE/${league.path}/scoreboard?dates=$yyyymmdd&limit=100"), league)

    suspend fun summary(league: League, eventId: String): GameDetail =
        parseSummary(Net.getJson("$SITE/${league.path}/summary?event=$eventId"), league)

    suspend fun standings(league: League): List<StandingGroup> {
        if (league == League.NFL) {
            // level=3 asks for division-level groups.
            return parseStandings(Net.getJson("$V2/${league.path}/standings?level=3"), league)
        }
        // College: ESPN's all-FBS standings response is huge and has come back partial, so ask for
        // each conference separately (in parallel). One failed conference doesn't sink the rest.
        val results = supervisorScope {
            Conferences.all.map { (id, _) ->
                async { runCatching { parseStandings(Net.getJson("$V2/${league.path}/standings?group=$id"), league, forceId = id) } }
            }.awaitAll()
        }
        val groups = results.mapNotNull { it.getOrNull() }.flatten()
        if (groups.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        return groups
    }

    suspend fun teams(league: League): List<TeamRef> =
        parseTeams(Net.getJson("$SITE/${league.path}/teams?limit=1000"), league)

    // ---------- parsers (pure, unit-testable) ----------

    fun parseScoreboard(root: JSONObject, league: League): Scoreboard {
        val games = root.arr("events").objects().mapNotNull { parseEvent(it, league) }
        val seasonType = root.obj("season")?.optInt("type", 0) ?: 0
        val weekNum = root.obj("week")?.optInt("number", 0) ?: 0
        val week = if (seasonType > 0 && weekNum > 0) WeekInfo(seasonType, weekNum) else null
        return Scoreboard(games, week)
    }

    fun parseEvent(ev: JSONObject, league: League): Game? {
        val comp = ev.arr("competitions").objects().firstOrNull() ?: return null
        val statusType = (comp.obj("status") ?: ev.obj("status"))?.obj("type")
        val sides = comp.arr("competitors").objects().map { parseSide(it) }
        val home = sides.firstOrNull { it.homeAway == "home" } ?: sides.getOrNull(0) ?: return null
        val away = sides.firstOrNull { it.homeAway == "away" } ?: sides.getOrNull(1) ?: return null
        val situation = comp.obj("situation")
        val broadcast = comp.arr("broadcasts").objects()
            .flatMap { it.arr("names").strings() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
        return Game(
            id = ev.str("id").ifEmpty { comp.str("id") },
            league = league,
            date = ev.str("date").ifEmpty { comp.str("date") },
            state = statusType?.str("state")?.ifEmpty { null } ?: "pre",
            detail = statusType?.str("shortDetail")?.ifEmpty { null } ?: statusType?.str("detail") ?: "",
            away = away,
            home = home,
            possessionTeamId = situation?.str("possession")?.ifEmpty { null },
            downDistance = situation?.str("shortDownDistanceText")?.ifEmpty { null }
                ?: situation?.str("downDistanceText") ?: "",
            broadcast = broadcast,
            odds = comp.arr("odds").objects().firstNotNullOfOrNull { OddsParse.espn(it, away.abbr, home.abbr) },
            weather = WeatherData.parseEspn(ev.obj("weather") ?: comp.obj("weather")),
            venue = comp.obj("venue")?.let { v ->
                val a = v.obj("address")
                GameVenue(a?.str("city").orEmpty(), a?.str("state").orEmpty(), v.optBoolean("indoor", false))
            },
        )
    }

    private fun parseSide(c: JSONObject): TeamSide {
        val t = c.obj("team") ?: JSONObject()
        val logo = t.str("logo").ifEmpty { t.arr("logos").objects().firstOrNull()?.str("href") ?: "" }
        // Scoreboard uses "records"; the summary header uses "record".
        val record = (c.arr("records") ?: c.arr("record")).objects()
            .firstOrNull()?.let { it.str("summary").ifEmpty { it.str("displayValue") } } ?: ""
        val rankRaw = c.obj("curatedRank")?.optInt("current", 99) ?: c.optInt("rank", 99)
        val lines = c.arr("linescores").objects().map { ls ->
            ls.str("displayValue").ifEmpty { ls.numText("value") }
        }
        val scoreObj = c.optJSONObject("score")
        val score = scoreObj?.str("displayValue") ?: c.numText("score")
        return TeamSide(
            id = t.str("id"),
            abbr = t.str("abbreviation").ifEmpty { t.str("shortDisplayName") },
            name = t.str("displayName").ifEmpty { t.str("name") },
            logo = logo,
            score = score,
            record = record,
            rank = rankRaw.takeIf { it in 1..25 },
            winner = c.optBoolean("winner", false),
            homeAway = c.str("homeAway"),
            linescores = lines,
            shortName = t.str("shortDisplayName"),
            color = t.str("color"),
            altColor = t.str("alternateColor"),
        )
    }

    fun parseSummary(root: JSONObject, league: League): GameDetail {
        val header = root.obj("header")
        val game = header?.let { parseEvent(it, league) }?.let { g ->
            // The game page's feed keeps betting lines in "pickcenter" rather than on the event.
            if (g.odds != null) g
            else g.copy(odds = root.arr("pickcenter").objects().firstNotNullOfOrNull { OddsParse.espn(it, g.away.abbr, g.home.abbr) })
        }?.let { g ->
            // The game page's feed keeps weather and the stadium under "gameInfo".
            val info = root.obj("gameInfo")
            val v = info?.obj("venue")
            g.copy(
                weather = g.weather ?: WeatherData.parseEspn(info?.obj("weather")),
                venue = g.venue?.takeIf { it.city.isNotBlank() } ?: v?.let {
                    val a = it.obj("address")
                    GameVenue(a?.str("city").orEmpty(), a?.str("state").orEmpty(), it.optBoolean("indoor", false))
                } ?: g.venue,
            )
        }
        val box = root.obj("boxscore")

        // Team stats: pair away/home by label.
        val boxTeams = box?.arr("teams").objects()
        val awayBox = boxTeams.firstOrNull { it.str("homeAway") == "away" } ?: boxTeams.getOrNull(0)
        val homeBox = boxTeams.firstOrNull { it.str("homeAway") == "home" } ?: boxTeams.getOrNull(1)
        fun statMap(o: JSONObject?): List<Pair<String, String>> = o?.arr("statistics").objects().map {
            val label = it.str("label").ifEmpty { it.str("name") }
            label to it.str("displayValue").ifEmpty { it.numText("value") }
        }
        val awayStats = statMap(awayBox)
        val homeStats = statMap(homeBox).toMap()
        val teamStats = awayStats.map { (label, v) -> StatLine(label, v, homeStats[label] ?: "") }

        // Player stat tables, one per team per category (passing, rushing, ...).
        val players = box?.arr("players").objects().flatMap { teamBlock ->
            val abbr = teamBlock.obj("team")?.str("abbreviation") ?: ""
            val teamId = teamBlock.obj("team")?.str("id") ?: ""
            teamBlock.arr("statistics").objects().mapNotNull { cat ->
                val athletes = cat.arr("athletes").objects()
                val rows = athletes.map { a ->
                    val ath = a.obj("athlete")
                    val name = ath?.str("displayName") ?: "?"
                    val jersey = ath?.str("jersey") ?: ""
                    (if (jersey.isNotEmpty()) "$name #$jersey" else name) to a.arr("stats").strings()
                }
                if (rows.isEmpty()) return@mapNotNull null
                PlayerTable(
                    teamAbbr = abbr,
                    title = cat.str("text").ifEmpty { cat.str("name").replaceFirstChar { it.uppercase() } },
                    labels = cat.arr("labels").strings(),
                    rows = rows,
                    totals = cat.arr("totals").strings(),
                    key = cat.str("name").ifEmpty { cat.str("text") },
                    teamId = teamId,
                    athleteIds = athletes.map { it.obj("athlete")?.str("id") ?: "" },
                    headshots = athletes.map { it.obj("athlete")?.obj("headshot")?.str("href") ?: "" },
                    names = athletes.map { it.obj("athlete")?.str("displayName") ?: "?" },
                )
            }
        }
        val teamColors = (box?.arr("players").objects() + boxTeams)
            .mapNotNull { it.obj("team") }
            .filter { it.str("id").isNotEmpty() && it.str("color").isNotEmpty() }
            .associate { it.str("id") to it.str("color") }

        val hockeyPlays = if (league == League.NHL) root.arr("plays").objects() else emptyList()
        val scoringSource = root.arr("scoringPlays").objects().ifEmpty { hockeyPlays.filter { it.optBoolean("scoringPlay", false) } }
        val abbrById = listOfNotNull(game?.away, game?.home).associate { it.id to it.abbr }
        val scoring = scoringSource.map { p ->
            val team = p.obj("team")
            ScoringPlay(
                period = p.obj("period")?.optInt("number", 0) ?: 0,
                clock = p.obj("clock")?.str("displayValue") ?: "",
                team = team?.str("abbreviation")?.ifEmpty { null } ?: abbrById[team?.str("id")]
                    ?: team?.str("displayName") ?: "",
                text = p.str("text"),
                awayScore = p.numText("awayScore"),
                homeScore = p.numText("homeScore"),
            )
        }
        val drives = if (league == League.NHL) hockeyPeriods(hockeyPlays, abbrById) else parseDrives(root)
        return GameDetail(game, teamStats, players, scoring, drives, teamColors = teamColors)
    }

    /**
     * Hockey play-by-play grouped by period (shown like football drives), newest period and
     * newest play first. Hockey clocks count down from 20:00, so a smaller clock is later.
     */
    fun hockeyPeriods(plays: List<JSONObject>, abbrById: Map<String, String>): List<Drive> {
        fun secs(clock: String): Int {
            val p = clock.split(':')
            return if (p.size == 2) (p[0].toIntOrNull() ?: 0) * 60 + (p[1].toIntOrNull() ?: 0) else 0
        }
        val parsed = plays.mapIndexed { i, p ->
            val team = p.obj("team")?.str("id").orEmpty()
            i to Play(
                id = p.str("id").ifEmpty { "p$i" },
                period = p.obj("period")?.optInt("number", 0) ?: 0,
                clock = p.obj("clock")?.str("displayValue") ?: "",
                downDistance = abbrById[team].orEmpty(),
                text = p.str("text"),
                type = p.obj("type")?.str("text") ?: "",
                awayScore = p.numText("awayScore"),
                homeScore = p.numText("homeScore"),
                scoring = p.optBoolean("scoringPlay", false),
            )
        }.filter { it.second.text.isNotBlank() && it.second.period > 0 }
        return parsed.groupBy { it.second.period }.toSortedMap(compareByDescending { it }).map { (period, list) ->
            val ordered = list.sortedWith(compareBy<Pair<Int, Play>>({ secs(it.second.clock) }, { -it.first })).map { it.second }
            val goals = ordered.count { it.scoring }
            Drive(
                id = "period-$period",
                team = hockeyPeriodName(period),
                logo = "",
                summary = "${ordered.size} plays" + if (goals > 0) "  ·  $goals goal${if (goals == 1) "" else "s"}" else "",
                result = "",
                isScore = false,
                inProgress = false,
                plays = ordered,
            )
        }
    }

    fun hockeyPeriodName(p: Int): String = when (p) {
        1 -> "1st Period"
        2 -> "2nd Period"
        3 -> "3rd Period"
        4 -> "Overtime"
        else -> "${p - 3}OT"
    }


    /** Seconds of game time elapsed at a play, for ordering (15-minute quarters). */
    private fun elapsed(period: Int, clock: String): Int {
        val parts = clock.split(':')
        val left = if (parts.size == 2) (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0) else 0
        return period * 900 - left
    }

    private fun ordinal(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

    fun parseDrives(root: JSONObject): List<Drive> {
        val drivesObj = root.obj("drives") ?: return emptyList()
        val current = drivesObj.obj("current")
        val raw = drivesObj.arr("previous").objects().map { it to false } +
            listOfNotNull(current).map { it to true }
        val seen = mutableSetOf<String>()
        val drives = raw.mapNotNull { (d, isCurrent) ->
            val id = d.str("id")
            if (id.isNotEmpty() && !seen.add(id)) return@mapNotNull null // current can repeat a previous drive
            val plays = d.arr("plays").objects().map { p ->
                val start = p.obj("start")
                val down = start?.optInt("down", 0) ?: 0
                val dd = start?.str("downDistanceText")?.ifEmpty { null }
                    ?: start?.str("shortDownDistanceText")?.ifEmpty { null }
                    ?: if (down > 0) "${ordinal(down)} & ${start?.optInt("distance", 0)}" +
                        (start?.str("possessionText")?.let { if (it.isNotEmpty()) " at $it" else "" } ?: "")
                    else ""
                Play(
                    id = p.str("id"),
                    period = p.obj("period")?.optInt("number", 0) ?: 0,
                    clock = p.obj("clock")?.str("displayValue") ?: "",
                    downDistance = dd,
                    text = p.str("text"),
                    type = p.obj("type")?.str("text") ?: "",
                    awayScore = p.numText("awayScore"),
                    homeScore = p.numText("homeScore"),
                    scoring = p.optBoolean("scoringPlay", false),
                )
            }.reversed().sortedByDescending { elapsed(it.period, it.clock) } // reversed first: stable sort keeps same-clock plays newest-first
            if (plays.isEmpty() && d.str("description").isEmpty()) return@mapNotNull null
            val team = d.obj("team")
            Drive(
                id = id,
                team = team?.str("abbreviation") ?: "",
                logo = team?.arr("logos").objects().firstOrNull()?.str("href") ?: "",
                summary = d.str("description"),
                result = d.str("displayResult").ifEmpty { d.str("result") },
                isScore = d.optBoolean("isScore", false),
                inProgress = isCurrent,
                plays = plays,
            )
        }
        // Don't trust ESPN's drive order; sort by the game clock of each drive's latest play.
        return drives.sortedByDescending { dr -> dr.plays.firstOrNull()?.let { elapsed(it.period, it.clock) } ?: -1 }
    }

    /** forceId: the conference group we asked for, used as the id for single-group responses. */
    fun parseStandings(root: JSONObject, league: League? = null, forceId: String? = null): List<StandingGroup> {
        val out = mutableListOf<StandingGroup>()
        fun walk(node: JSONObject) {
            val entries = node.obj("standings")?.arr("entries").objects()
            if (entries.isNotEmpty()) out += buildGroup(node.str("id"), node.str("name"), entries, league)
            node.arr("children").objects().forEach { walk(it) }
        }
        walk(root)
        // Pinning works by conference: every group in a per-conference response carries that conference's id.
        return if (forceId != null) out.map { it.copy(id = forceId) } else out
    }

    private val standingHeaders = listOf("W-L", "Conf", "PF", "PA", "Strk")

    /** ESPN's logo CDN, for rows whose team object came without a logos array. */
    fun fallbackLogo(league: League?, id: String, abbr: String): String = when {
        league == League.NFL && abbr.isNotEmpty() -> "https://a.espncdn.com/i/teamlogos/nfl/500/${abbr.lowercase()}.png"
        league == League.CFB && id.isNotEmpty() -> "https://a.espncdn.com/i/teamlogos/ncaa/500/$id.png"
        league == League.NHL && abbr.isNotEmpty() -> "https://a.espncdn.com/i/teamlogos/nhl/500/${abbr.lowercase()}.png"
        else -> ""
    }

    private fun buildGroup(id: String, title: String, entries: List<JSONObject>, league: League?): StandingGroup {
        data class Tmp(val row: StandingRow, val seed: Int, val pct: Double)
        val tmps = entries.map { e ->
            val t = e.obj("team") ?: JSONObject()
            val stats = e.arr("stats").objects()
            fun byName(vararg names: String): JSONObject? =
                names.firstNotNullOfOrNull { n -> stats.firstOrNull { it.str("name") == n } }
            fun dv(vararg names: String): String = byName(*names)?.let {
                it.str("displayValue").ifEmpty { it.str("summary") }
            } ?: ""
            val overall = dv("overall").ifEmpty {
                val w = dv("wins"); val l = dv("losses"); val tie = dv("ties")
                if (w.isEmpty()) "" else if (tie.isEmpty() || tie == "0") "$w-$l" else "$w-$l-$tie"
            }
            val row = StandingRow(
                teamId = t.str("id"),
                name = t.str("displayName").ifEmpty { t.str("name") },
                abbr = t.str("abbreviation"),
                logo = t.arr("logos").objects().firstOrNull()?.str("href")?.ifEmpty { null }
                    ?: fallbackLogo(league, t.str("id"), t.str("abbreviation")),
                cols = listOf(overall, dv("vs. Conf.", "vsConf"), dv("pointsFor"), dv("pointsAgainst"), dv("streak")),
            )
            val seed = byName("playoffSeed")?.optDouble("value", 0.0)?.toInt() ?: 0
            val pct = byName("winPercent")?.optDouble("value", -1.0) ?: -1.0
            Tmp(row, seed, pct)
        }
        val sorted = if (tmps.all { it.seed > 0 }) tmps.sortedBy { it.seed }
        else if (tmps.any { it.pct >= 0 }) tmps.sortedByDescending { it.pct }
        else tmps
        // Drop columns that are empty for every team (e.g. college PF/PA).
        val rows = sorted.map { it.row }
        val keep = standingHeaders.indices.filter { i -> rows.any { it.cols[i].isNotBlank() } }
        return StandingGroup(
            id = id,
            title = title,
            headers = keep.map { standingHeaders[it] },
            rows = rows.map { r -> r.copy(cols = keep.map { r.cols[it] }) },
        )
    }

    fun parseTeams(root: JSONObject, league: League): List<TeamRef> =
        root.arr("sports").objects().flatMap { s ->
            s.arr("leagues").objects().flatMap { l ->
                l.arr("teams").objects().mapNotNull { wrap ->
                    val t = wrap.obj("team") ?: return@mapNotNull null
                    TeamRef(
                        league = league,
                        id = t.str("id"),
                        name = t.str("displayName"),
                        abbr = t.str("abbreviation"),
                        logo = t.arr("logos").objects().firstOrNull()?.str("href") ?: "",
                    )
                }
            }
        }.filter { it.id.isNotEmpty() }.sortedBy { it.name }
}
