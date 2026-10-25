package com.nate.scoreline

import kotlin.math.roundToInt

/**
 * Season trends for offensive skill players (QB, RB, FB, WR, TE), built only from the
 * player's game log and his team's results in those games. Each trend has to clear a
 * threshold, so most players get 1 to 3 and only standout seasons reach the cap of 5.
 */
object PlayerTrends {
    val positions = setOf("QB", "RB", "FB", "WR", "TE")
    private const val MAX = 5

    private class G(val opp: String, val result: Char, val v: Map<String, Double>)
    private class T(val text: String, val score: Int, val stat: String)

    /** [teamName] like "Carolina Panthers"; the record trend uses its location ("Carolina"). */
    fun build(log: GameLog, position: String, teamName: String): List<String> {
        if (position.uppercase() !in positions) return emptyList()
        val sec = log.sections.firstOrNull { it.title.contains("Regular", true) } ?: log.sections.firstOrNull() ?: return emptyList()
        val cols = columns(log)
        fun idx(group: String, vararg labels: String) =
            labels.firstNotNullOfOrNull { l -> cols[group to l.uppercase()] }
        val keys = mapOf(
            "car" to idx("rush", "CAR"), "rushYds" to idx("rush", "YDS"), "rushTd" to idx("rush", "TD"), "ypc" to idx("rush", "AVG"),
            "rec" to idx("receiv", "REC"), "tgt" to idx("receiv", "TGTS", "TGT"), "recYds" to idx("receiv", "YDS"), "recTd" to idx("receiv", "TD"),
            "att" to idx("pass", "ATT"), "passYds" to idx("pass", "YDS"), "passTd" to idx("pass", "TD"), "int" to idx("pass", "INT"),
        ).filterValues { it != null }.mapValues { it.value!! }
        // Oldest game first.
        val games = sec.games.reversed().map { e ->
            G(
                opp = e.opponent.replace("@ ", "at "),
                result = e.result.firstOrNull()?.uppercaseChar() ?: ' ',
                v = keys.mapNotNull { (k, i) -> e.stats.getOrNull(i)?.replace(",", "")?.toDoubleOrNull()?.let { k to it } }.toMap(),
            )
        }
        if (games.size < 3) return emptyList()
        val team = teamName.substringBeforeLast(' ').ifBlank { "His team" }
        return candidates(games, position.uppercase(), team)
            .sortedByDescending { it.score }
            .fold(mutableListOf<T>()) { acc, t -> if (acc.count { it.stat == t.stat } < 2 && acc.size < MAX) acc += t; acc }
            .map { it.text }
    }

    /** (group keyword, label) -> column, using the column groups ("Rushing", "Receiving", "Passing"). */
    private fun columns(log: GameLog): Map<Pair<String, String>, Int> {
        val out = HashMap<Pair<String, String>, Int>()
        var i = 0
        for (grp in log.groups) {
            val kw = listOf("rush", "receiv", "pass").firstOrNull { grp.title.contains(it, true) }
            repeat(grp.span) {
                if (kw != null) out.putIfAbsent(kw to log.labels[i].uppercase(), i)
                i++
            }
        }
        return out
    }

    private fun fmt(d: Double) = if (d == d.roundToInt().toDouble()) "%,d".format(d.roundToInt()) else "%.1f".format(d)

    private fun record(list: List<G>): String {
        val w = list.count { it.result == 'W' }
        val l = list.count { it.result == 'L' }
        val t = list.count { it.result == 'T' }
        return if (t > 0) "$w-$l-$t" else "$w-$l"
    }

    private fun winPct(list: List<G>) = if (list.isEmpty()) 0.0 else list.count { it.result == 'W' } / list.size.toDouble()

    private fun candidates(games: List<G>, pos: String, team: String): List<T> {
        val out = mutableListOf<T>()
        fun vals(k: String) = games.map { it.v[k] }
        fun has(k: String) = games.all { it.v[k] != null }

        // 1. Workload up every game (needs a real workload by the end).
        val volume = when (pos) {
            "QB" -> listOf(Triple("att", "pass attempts", 25.0))
            "RB", "FB" -> listOf(Triple("car", "carries", 12.0), Triple("tgt", "targets", 6.0))
            else -> listOf(Triple("tgt", "targets", 6.0), Triple("rec", "catches", 5.0))
        }
        for ((k, noun, min) in volume) {
            if (!has(k) || games.size < 4) continue
            val v = vals(k).map { it!! }
            if (v.zipWithNext().all { (a, b) -> b > a } && v.last() >= min) {
                out += T("His $noun have gone up every game this season, from ${fmt(v.first())} ${games.first().opp} to a season-high ${fmt(v.last())} ${games.last().opp}.", 90, k)
                break
            }
        }

        // 2. Active yardage streaks (100 rushing/receiving, 300 passing), with share of season yards when it's big.
        val yardStats = listOf(Triple("rushYds", "rushing", 100.0), Triple("recYds", "receiving", 100.0), Triple("passYds", "passing", 300.0))
        for ((k, kind, line) in yardStats) {
            if (!has(k)) continue
            val v = vals(k).map { it!! }
            val streak = v.reversed().takeWhile { it >= line }.size
            if (streak < 2) continue
            val recent = games.takeLast(streak)
            val total = v.sum()
            val share = if (total > 0) v.takeLast(streak).sum() / total else 0.0
            val list = recent.joinToString(", ") { "${fmt(it.v[k]!!)} ${it.opp}" }
            val label = "${fmt(line)}-yard"
            out += when {
                streak == 2 && share >= 0.45 ->
                    T("Back-to-back $label games ($list) make up ${(share * 100).roundToInt()}% of his ${fmt(total)} $kind yards.", 82, k)
                streak == 2 -> T("He has back-to-back $label $kind games ($list).", 80, k)
                else -> T("He has $streak straight $label $kind games.", 80 + streak, k)
            }
        }

        // 3. Touchdown and interception streaks.
        val tdStats = listOf(Triple("rushTd", "a rushing touchdown", 1.0), Triple("recTd", "a receiving touchdown", 1.0), Triple("passTd", "2 or more touchdown passes", 2.0))
        for ((k, what, line) in tdStats) {
            if (!has(k)) continue
            val streak = vals(k).map { it!! }.reversed().takeWhile { it >= line }.size
            if (streak >= 3) out += T("He has $what in $streak straight games.", 78 + streak, k)
        }
        if (pos == "QB" && has("int")) {
            val streak = vals("int").map { it!! }.reversed().takeWhile { it >= 1 }.size
            if (streak >= 3) out += T("He has thrown an interception in $streak straight games.", 76 + streak, "int")
            val td = vals("passTd").sumOf { it ?: 0.0 }
            val ints = vals("int").sumOf { it ?: 0.0 }
            if (td >= 6 && ints * 3 <= td) {
                out += T("He has thrown ${fmt(td)} touchdowns to ${if (ints == 0.0) "no" else "just ${fmt(ints)}"} interception${if (ints == 1.0) "" else "s"}.", 64, "passTd")
            }
        }

        // 4. Last 3 games vs the rest of the season (skipped when a streak already covers that stat).
        val main = when (pos) { "QB" -> "passYds"; "RB", "FB" -> "rushYds"; else -> "recYds" }
        val kindOf = mapOf("passYds" to "passing", "rushYds" to "rushing", "recYds" to "receiving")
        if (has(main) && games.size >= 5 && out.none { it.stat == main }) {
            val v = vals(main).map { it!! }
            val last = v.takeLast(3).average()
            val before = v.dropLast(3).average()
            val minGap = if (main == "passYds") 50.0 else 20.0
            if (before > 0 && kotlin.math.abs(last - before) >= minGap && kotlin.math.abs(last - before) / before >= 0.4) {
                val dir = if (last > before) "up" else "down"
                val n = v.size - 3
                out += T("Over his last 3 games he's averaging ${last.roundToInt()} ${kindOf[main]} yards, $dir from ${before.roundToInt()} in his first $n.", 70, main)
            }
        }

        // 5. Team record when he gets a big workload vs when he doesn't.
        val split = when (pos) {
            "QB" -> Triple("passYds", 250.0, "when he throws for 250 or more yards")
            "RB", "FB" -> Triple("car", 20.0, "when he gets 20 or more carries")
            else -> Triple("tgt", 8.0, "when he gets 8 or more targets")
        }
        if (has(split.first) && games.all { it.result in "WLT" }) {
            val (hi, lo) = games.partition { it.v[split.first]!! >= split.second }
            if (hi.isNotEmpty() && lo.isNotEmpty() && kotlin.math.abs(winPct(hi) - winPct(lo)) >= 0.5) {
                out += T("$team is ${record(hi)} ${split.third} and ${record(lo)} when he doesn't.", 65, "team")
            }
        }

        // 6. Consistency every game (needs at least 4 games).
        if (games.size >= 4) {
            if (pos in setOf("RB", "FB") && has("ypc") && has("car") && games.all { it.v["car"]!! >= 8 && it.v["ypc"]!! >= 4.5 }) {
                out += T("He has averaged at least 4.5 yards per carry in every game this season.", 60, "ypc")
            }
            if (pos in setOf("WR", "TE", "RB") && has("rec")) {
                val minRec = vals("rec").minOf { it!! }
                if (minRec >= 4) out += T("He has at least ${fmt(minRec)} catches in every game this season.", 60, "rec")
            }
            if (pos == "QB" && has("passTd") && games.all { it.v["passTd"]!! >= 1 }) {
                out += T("He has thrown a touchdown pass in every game this season.", 58, "passTd")
            }
        }

        // 7. Last game was a clear season high (unless a streak already covers it).
        if (has(main) && out.none { it.stat == main }) {
            val v = vals(main).map { it!! }
            val minYds = if (main == "passYds") 250.0 else 75.0
            if (v.last() >= minYds && v.dropLast(1).all { it < v.last() }) {
                out += T("His ${fmt(v.last())} ${kindOf[main]} yards ${games.last().opp} last game were a season high.", 55, main)
            }
        }
        return out
    }
}
