package com.nate.scoreline

import org.json.JSONObject
import java.text.Normalizer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class F1Entry(val pos: Int, val driver: String, val flag: String, val winner: Boolean)

data class F1Session(
    val id: String,
    val name: String,
    val date: String,
    val state: String,
    val detail: String,
    val results: List<F1Entry>,
) {
    val isLive get() = state == "in"
}

data class F1Weekend(val id: String, val name: String, val circuit: String, val location: String, val date: String, val sessions: List<F1Session>)

data class F1ResultRow(
    val pos: String,
    val driver: String,
    val code: String,
    val team: String,
    val grid: String,
    val laps: String,
    val timeOrStatus: String,
    val points: String,
    val fastestLap: Boolean,
    val driverId: String = "",
    val constructorId: String = "",
)

data class F1Race(val name: String, val round: String, val circuit: String, val date: String, val results: List<F1ResultRow>)

/** One round of the season schedule. dateIso is UTC start ("2026-09-26T11:00:00Z") or just the date. */
data class F1Round(
    val round: String,
    val name: String,
    val circuit: String,
    val location: String,
    val dateIso: String,
    val hasSprint: Boolean,
    val winner: String? = null,
    val winnerTeam: String? = null,
    val circuitId: String = "",
    /** Weekend sessions in order: (label, UTC ISO start). */
    val sessions: List<Pair<String, String>> = emptyList(),
)

/** One qualifying classification line. q1..q3 are lap times ("1:29.123"), blank if not set. */
data class F1QualiRow(val pos: String, val driver: String, val team: String, val q1: String, val q2: String, val q3: String) {
    /** Best stage reached, with its time: ("Q3", "1:29.123"). */
    val best: Pair<String, String>
        get() = when {
            q3.isNotBlank() -> "Q3" to q3
            q2.isNotBlank() -> "Q2" to q2
            q1.isNotBlank() -> "Q1" to q1
            else -> "" to "No time"
        }
}

data class F1DriverStanding(
    val pos: String, val name: String, val code: String, val team: String, val points: String, val wins: String,
    val driverId: String = "", val constructorId: String = "",
)
data class F1TeamStanding(val pos: String, val team: String, val points: String, val wins: String, val constructorId: String = "")

/**
 * Live/current-weekend session order comes from ESPN.
 * Standings and detailed classified results come from Jolpica (the community
 * successor to the Ergast F1 API): documented, free, updated after each session.
 */
object F1 {
    private const val ESPN = "https://site.api.espn.com/apis/site/v2/sports/racing/f1/scoreboard"
    private const val JOLPICA = "https://api.jolpi.ca/ergast/f1/current"

    /**
     * ESPN keeps listing a finished weekend for days after the race. From the day after its last
     * session, show the next round from Jolpica's schedule instead (no results yet, just the sessions).
     */
    suspend fun weekends(): List<F1Weekend> {
        val espn = parseWeekends(Net.getJson(ESPN))
        if (!weekendOver(espn, java.time.LocalDate.now())) return espn
        val next = runCatching { nextWeekend(season(), java.time.Instant.now()) }.getOrNull() ?: return espn
        return listOf(next)
    }

    /** True when every listed session is final and the last one was before [today] (phone's time zone). */
    fun weekendOver(ws: List<F1Weekend>, today: java.time.LocalDate, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Boolean =
        ws.isNotEmpty() && ws.all { w ->
            val last = w.sessions.mapNotNull { runCatching { java.time.OffsetDateTime.parse(it.date).atZoneSameInstant(zone).toLocalDate() }.getOrNull() }.maxOrNull()
            w.sessions.isNotEmpty() && w.sessions.all { it.state == "post" } && last != null && last < today
        }

    /** The first round whose race hasn't started yet, as an upcoming weekend. */
    fun nextWeekend(rounds: List<F1Round>, now: java.time.Instant): F1Weekend? {
        fun start(iso: String): java.time.Instant? = runCatching { java.time.Instant.parse(iso) }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(iso.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).plusDays(1).toInstant() }.getOrNull()
        val r = rounds.firstOrNull { start(it.dateIso)?.isAfter(now) == true } ?: return null
        return F1Weekend(
            id = "round-${r.round}",
            name = r.name,
            circuit = r.circuit,
            location = r.location,
            date = r.dateIso,
            sessions = r.sessions.map { (label, iso) -> F1Session("round-${r.round}-$label", label, iso, "pre", "", emptyList()) },
        )
    }
    suspend fun lastRace(): F1Race? = parseLastRace(Net.getJson("$JOLPICA/last/results.json"))
    /** Full season schedule, with winners filled in for completed rounds. */
    suspend fun season(): List<F1Round> = coroutineScope {
        val sched = async { parseSchedule(Net.getJson("$JOLPICA/races.json?limit=40")) }
        val winners = async { runCatching { parseWinners(Net.getJson("$JOLPICA/results/1.json?limit=40")) }.getOrDefault(emptyMap()) }
        val w = winners.await()
        sched.await().map { r -> w[r.round]?.let { (d, t) -> r.copy(winner = d, winnerTeam = t) } ?: r }
    }
    /** Winner (driver, team) of the most recent earlier race at this circuit, with its year. */
    suspend fun previousWinner(circuitId: String, beforeYear: Int): Triple<Int, String, String>? {
        if (circuitId.isEmpty()) return null
        val root = Net.getJson("https://api.jolpi.ca/ergast/f1/circuits/$circuitId/results/1.json?limit=100")
        val races = raceTable(root).filter { (it.str("season").toIntOrNull() ?: 0) < beforeYear }
        val last = races.maxByOrNull { it.str("season").toIntOrNull() ?: 0 } ?: return null
        val res = last.arr("Results").objects().firstOrNull() ?: return null
        val d = res.obj("Driver")
        return Triple(
            last.str("season").toIntOrNull() ?: 0,
            "${d?.str("givenName") ?: ""} ${d?.str("familyName") ?: ""}".trim(),
            res.obj("Constructor")?.str("name") ?: "",
        )
    }
    suspend fun raceResults(round: String): F1Race? = parseLastRace(Net.getJson("$JOLPICA/$round/results.json"))
    suspend fun sprintResults(round: String): F1Race? = parseLastRace(Net.getJson("$JOLPICA/$round/sprint.json"), "SprintResults")
    suspend fun qualifying(round: String): List<F1QualiRow> = parseQualifying(Net.getJson("$JOLPICA/$round/qualifying.json"))
    suspend fun driverStandings(): List<F1DriverStanding> = parseDriverStandings(Net.getJson("$JOLPICA/driverStandings.json"))
    suspend fun teamStandings(): List<F1TeamStanding> = parseTeamStandings(Net.getJson("$JOLPICA/constructorStandings.json"))

    /** Lowercase, accents removed, anything else non-alphanumeric turned into spaces. */
    fun normalizeForSearch(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Match key for favorite drivers: accent-stripped, lowercased surname. Works across ESPN and Jolpica naming. */
    fun driverKey(fullName: String): String {
        val last = fullName.trim().split(Regex("\\s+")).lastOrNull() ?: return ""
        val stripped = Normalizer.normalize(last, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return stripped.lowercase()
    }

    fun parseWeekends(root: JSONObject): List<F1Weekend> = root.arr("events").objects().map { ev ->
        val circuit = ev.obj("circuit")
        val addr = circuit?.obj("address")
        val location = listOf(addr?.str("city") ?: "", addr?.str("country") ?: "").filter { it.isNotBlank() }.joinToString(", ")
        val sessions = ev.arr("competitions").objects().map { c ->
            val type = c.obj("type")
            val st = c.obj("status")?.obj("type")
            val results = c.arr("competitors").objects().mapNotNull { comp ->
                val ath = comp.obj("athlete") ?: return@mapNotNull null
                F1Entry(
                    pos = comp.optInt("order", 0),
                    driver = ath.str("displayName").ifEmpty { ath.str("fullName") },
                    flag = ath.obj("flag")?.str("href") ?: "",
                    winner = comp.optBoolean("winner", false),
                )
            }.filter { it.pos > 0 }.sortedBy { it.pos }
            F1Session(
                id = c.str("id"),
                name = type?.str("abbreviation")?.ifEmpty { null } ?: type?.str("name") ?: "Session",
                date = c.str("date"),
                state = st?.str("state")?.ifEmpty { null } ?: "pre",
                detail = st?.str("shortDetail")?.ifEmpty { null } ?: st?.str("description") ?: "",
                results = results,
            )
        }
        F1Weekend(
            id = ev.str("id"),
            name = ev.str("name"),
            circuit = circuit?.str("fullName") ?: "",
            location = location,
            date = ev.str("date"),
            sessions = sessions,
        )
    }

    fun raceTable(root: JSONObject) = root.obj("MRData")?.obj("RaceTable")?.arr("Races").objects()
    private fun standingsList(root: JSONObject) =
        root.obj("MRData")?.obj("StandingsTable")?.arr("StandingsLists").objects()?.firstOrNull()

    fun parseLastRace(root: JSONObject, key: String = "Results"): F1Race? {
        val race = raceTable(root).firstOrNull() ?: return null
        val rows = race.arr(key).objects().map { r ->
            val d = r.obj("Driver")
            val time = r.obj("Time")?.str("time") ?: ""
            F1ResultRow(
                pos = r.str("position"),
                driver = "${d?.str("givenName") ?: ""} ${d?.str("familyName") ?: ""}".trim(),
                code = d?.str("code") ?: "",
                team = r.obj("Constructor")?.str("name") ?: "",
                grid = r.str("grid"),
                laps = r.str("laps"),
                timeOrStatus = time.ifEmpty { r.str("status") },
                points = r.str("points"),
                fastestLap = r.obj("FastestLap")?.str("rank") == "1",
                driverId = d?.str("driverId") ?: "",
                constructorId = r.obj("Constructor")?.str("constructorId") ?: "",
            )
        }
        return F1Race(
            name = race.str("raceName"),
            round = race.str("round"),
            circuit = race.obj("Circuit")?.str("circuitName") ?: "",
            date = race.str("date"),
            results = rows,
        )
    }

    fun parseSchedule(root: JSONObject): List<F1Round> = raceTable(root).map { r ->
        val c = r.obj("Circuit")
        val loc = c?.obj("Location")
        val time = r.str("time")
        fun iso(o: JSONObject?): String? {
            val d = o?.str("date") ?: return null
            if (d.isEmpty()) return null
            val t = o.str("time")
            return if (t.isNotEmpty()) "${d}T$t" else d
        }
        val sessions = listOf(
            "Practice 1" to "FirstPractice", "Practice 2" to "SecondPractice", "Practice 3" to "ThirdPractice",
            "Sprint Qualifying" to "SprintQualifying", "Sprint Qualifying" to "SprintShootout",
            "Sprint" to "Sprint", "Qualifying" to "Qualifying",
        ).mapNotNull { (label, key) -> iso(r.obj(key))?.let { label to it } } +
            listOfNotNull(if (r.str("date").isNotEmpty()) "Race" to (if (time.isNotEmpty()) "${r.str("date")}T$time" else r.str("date")) else null)
        F1Round(
            circuitId = c?.str("circuitId") ?: "",
            sessions = sessions.sortedBy { it.second },
            round = r.str("round"),
            name = r.str("raceName"),
            circuit = c?.str("circuitName") ?: "",
            location = listOf(loc?.str("locality") ?: "", loc?.str("country") ?: "").filter { it.isNotBlank() }.joinToString(", "),
            dateIso = if (time.isNotEmpty()) "${r.str("date")}T$time" else r.str("date"),
            hasSprint = r.obj("Sprint") != null,
        )
    }

    /** round -> (winner name, team) from a results/1 query. */
    fun parseWinners(root: JSONObject): Map<String, Pair<String, String>> = raceTable(root).mapNotNull { r ->
        val res = r.arr("Results").objects().firstOrNull() ?: return@mapNotNull null
        val d = res.obj("Driver")
        r.str("round") to Pair(
            "${d?.str("givenName") ?: ""} ${d?.str("familyName") ?: ""}".trim(),
            res.obj("Constructor")?.str("name") ?: "",
        )
    }.toMap()

    fun parseQualifying(root: JSONObject): List<F1QualiRow> =
        raceTable(root).firstOrNull()?.arr("QualifyingResults").objects().map { r ->
            val d = r.obj("Driver")
            F1QualiRow(
                pos = r.str("position"),
                driver = "${d?.str("givenName") ?: ""} ${d?.str("familyName") ?: ""}".trim(),
                team = r.obj("Constructor")?.str("name") ?: "",
                q1 = r.str("Q1"), q2 = r.str("Q2"), q3 = r.str("Q3"),
            )
        }

    fun parseDriverStandings(root: JSONObject): List<F1DriverStanding> =
        standingsList(root)?.arr("DriverStandings").objects().map { s ->
            val d = s.obj("Driver")
            F1DriverStanding(
                pos = s.str("position").ifEmpty { s.str("positionText") },
                name = "${d?.str("givenName") ?: ""} ${d?.str("familyName") ?: ""}".trim(),
                code = d?.str("code") ?: "",
                team = s.arr("Constructors").objects().lastOrNull()?.str("name") ?: "",
                points = s.str("points"),
                wins = s.str("wins"),
                driverId = d?.str("driverId") ?: "",
                constructorId = s.arr("Constructors").objects().lastOrNull()?.str("constructorId") ?: "",
            )
        }

    fun parseTeamStandings(root: JSONObject): List<F1TeamStanding> =
        standingsList(root)?.arr("ConstructorStandings").objects().map { s ->
            F1TeamStanding(
                pos = s.str("position").ifEmpty { s.str("positionText") },
                team = s.obj("Constructor")?.str("name") ?: "",
                points = s.str("points"),
                wins = s.str("wins"),
                constructorId = s.obj("Constructor")?.str("constructorId") ?: "",
            )
        }
}

/**
 * Team badge styling. No free, stable source publishes F1 team logo images, so the app
 * draws a badge in the team's color with a short code. Matching is by keyword so it
 * tolerates Jolpica's naming ("Haas F1 Team", "RB F1 Team", "Alpine F1 Team"...).
 */
object F1Teams {
    /** slug is formula1.com's team folder name, used for the official logo images. */
    data class Style(val code: String, val color: Long, val slug: String? = null)

    private val styles = listOf(
        listOf("mercedes") to Style("MER", 0xFF00A19C, "mercedes"),
        listOf("ferrari") to Style("FER", 0xFFE8002D, "ferrari"),
        listOf("mclaren") to Style("MCL", 0xFFFF8000, "mclaren"),
        listOf("racing bulls", "rb f1", "visa", "alphatauri") to Style("RB", 0xFF6692FF, "racingbulls"),
        listOf("red bull") to Style("RBR", 0xFF3671C6, "redbullracing"),
        listOf("aston") to Style("AMR", 0xFF229971, "astonmartin"),
        listOf("alpine") to Style("ALP", 0xFF0093CC, "alpine"),
        listOf("williams") to Style("WIL", 0xFF1868DB, "williams"),
        listOf("haas") to Style("HAA", 0xFF9C9FA2, "haasf1team"),
        listOf("audi", "sauber", "kick") to Style("AUD", 0xFFBB0A30, "audi"),
        listOf("cadillac") to Style("CAD", 0xFF5A5A5A, "cadillac"),
    )

    /**
     * Official logo image URLs from formula1.com's media server, newest season first.
     * white = the all-white version for dark themes; otherwise the full-color logo.
     */
    fun logoUrls(team: String, white: Boolean, year: Int): List<String> {
        val slug = style(team).slug ?: return emptyList()
        val variant = if (white) "logowhite" else "logo"
        return listOf(year, 2026).distinct().map { y ->
            "https://media.formula1.com/image/upload/c_fit,h_96/q_auto/v1740000000/common/f1/$y/$slug/$y$slug$variant.webp"
        }
    }

    /** Team name -> whether its color logo is too dark to show on a dark background. */
    val darkLogo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** True when the visible pixels of a logo are, on average, dark (luminance under 0.3). */
    fun isMostlyDark(d: android.graphics.drawable.Drawable): Boolean {
        val bmp = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return false
        if (bmp.config == android.graphics.Bitmap.Config.HARDWARE) return false
        val stepX = maxOf(1, bmp.width / 48)
        val stepY = maxOf(1, bmp.height / 48)
        var sum = 0.0
        var weight = 0.0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val a = (c ushr 24 and 0xFF) / 255.0
                if (a > 0.2) {
                    val r = (c shr 16 and 0xFF) / 255.0
                    val g = (c shr 8 and 0xFF) / 255.0
                    val b = (c and 0xFF) / 255.0
                    sum += (0.2126 * r + 0.7152 * g + 0.0722 * b) * a
                    weight += a
                }
                x += stepX
            }
            y += stepY
        }
        return weight > 0 && sum / weight < 0.3
    }

    fun style(team: String): Style {
        val t = team.lowercase()
        return styles.firstOrNull { (keys, _) -> keys.any { it in t } }?.second
            ?: Style(team.filter { it.isLetter() }.take(3).uppercase().ifEmpty { "F1" }, 0xFF6B7280)
    }
}
