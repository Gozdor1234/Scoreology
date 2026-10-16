package com.nate.scoreline

/**
 * Pure change-detection logic for alerts, kept free of Android types so it can be tested.
 * Snapshot format per game: "state|awayScore|homeScore".
 */
object AlertLogic {
    data class Alert(val title: String, val text: String)

    fun snapshot(g: Game) = "${g.state}|${g.away.score}|${g.home.score}"

    fun line(g: Game) = "${g.away.abbr} ${g.away.score} - ${g.home.abbr} ${g.home.score}"

    /**
     * Returns the alert to post (or null) given the previous snapshot.
     * First sighting (prev == null) never alerts, so installing the app or adding a
     * favorite mid-game doesn't fire a burst of stale notifications.
     */
    fun evaluate(prev: String?, g: Game, scoreAlerts: Boolean): Alert? {
        if (prev == null) return null
        val parts = prev.split('|')
        val prevState = parts.getOrNull(0) ?: return null
        val prevAway = parts.getOrNull(1) ?: ""
        val prevHome = parts.getOrNull(2) ?: ""
        return when {
            prevState != "post" && g.state == "post" ->
                Alert("Final: ${g.matchup}", line(g))
            prevState == "pre" && g.state == "in" ->
                Alert((if (g.league.isHockey) "Puck drop: " else "Kickoff: ") + g.matchup, "${line(g)}  (${g.detail})")
            g.state == "in" && scoreAlerts && (prevAway != g.away.score || prevHome != g.home.score) ->
                Alert("Score: ${g.matchup}", "${line(g)}  (${g.detail})")
            else -> null
        }
    }

    /** F1: alert once when a Race or Qualifying session goes final, naming the top 3. */
    fun f1Alert(prevState: String?, weekend: String, s: F1Session): Alert? {
        if (prevState == null || prevState == "post" || s.state != "post") return null
        val isKey = s.name.equals("Race", true) || s.name.startsWith("Qual", true) || s.name.contains("Sprint", true)
        if (!isKey) return null
        val podium = s.results.take(3).joinToString("  ") { "P${it.pos} ${it.driver}" }
        return Alert("$weekend: ${s.name} final", podium.ifEmpty { "Results posted" })
    }
}
