package com.nate.scoreline

/** One driver in the live running order, with what the app can work out around it. */
data class LiveRow(
    val pos: Int,
    val driver: String,
    val team: String,
    /** Starting spot (from qualifying), null when unknown. */
    val grid: Int?,
    /** Points he'd score finishing where he is now (race/sprint only). */
    val points: Int,
) {
    /** Places gained (+) or lost (-) since the start, null when the grid isn't known. */
    val gained: Int? get() = grid?.let { it - pos }
}

data class LiveChamp(val name: String, val now: Int, val add: Int, val rankNow: Int, val rankLive: Int) {
    val total get() = now + add
}

/** Pure live-race math, kept free of Android so it's unit-tested. */
object F1Live {
    fun rows(results: List<F1Entry>, kind: String?, standings: List<F1DriverStanding>, grid: Map<String, Int>): List<LiveRow> {
        val teamOf = standings.associate { F1.driverKey(it.name) to it.team }
        val pts = if (kind == null) emptyMap() else F1ExtrasParse.tablePoints(results, sprint = kind == "sprint")
        return results.sortedBy { it.pos }.map { e ->
            val k = F1.driverKey(e.driver)
            LiveRow(e.pos, e.driver, teamOf[k].orEmpty(), grid[k], pts[k]?.toIntOrNull() ?: 0)
        }
    }

    /** Drivers' championship if the session ended now; ranks are 1-based, ties broken by current order. */
    fun championship(standings: List<F1DriverStanding>, rows: List<LiveRow>): List<LiveChamp> {
        val add = rows.associate { F1.driverKey(it.driver) to it.points }
        val now = standings.map { it.name to (it.points.toDoubleOrNull()?.toInt() ?: 0) }
        val nowRank = now.mapIndexed { i, (n, _) -> n to i + 1 }.toMap()
        return now.map { (n, p) -> n to (p + (add[F1.driverKey(n)] ?: 0)) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { nowRank[it.first] })
            .mapIndexed { i, (n, _) ->
                val p = now.first { it.first == n }.second
                LiveChamp(n, p, add[F1.driverKey(n)] ?: 0, nowRank.getValue(n), i + 1)
            }
    }

    /** "A P1 ahead of B P8" pairs, one per team with both drivers running, best car first. */
    fun teammates(rows: List<LiveRow>): List<Pair<LiveRow, LiveRow>> =
        rows.filter { it.team.isNotEmpty() }.groupBy { it.team }.values
            .filter { it.size >= 2 }
            .map { it.sortedBy { r -> r.pos }.let { s -> s[0] to s[1] } }
            .sortedBy { it.first.pos }

    /** Drivers whose position differs from the previous update. */
    fun changed(prev: Map<String, Int>, rows: List<LiveRow>): Set<String> =
        if (prev.isEmpty()) emptySet() else rows.filter { prev[it.driver] != null && prev[it.driver] != it.pos }.map { it.driver }.toSet()
}
