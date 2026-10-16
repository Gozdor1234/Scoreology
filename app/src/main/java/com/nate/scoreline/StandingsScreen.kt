package com.nate.scoreline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val standingTabs = listOf("NFL", "College", "NHL", "F1 Drivers", "F1 Teams")

@Composable
fun StandingsScreen(modifier: Modifier, open: (Route) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        // No search button here, so the tabs get the full width; they scroll sideways if they don't all fit.
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            standingTabs.forEachIndexed { i, s -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(s, maxLines = 1, softWrap = false) }) }
        }
        when (tab) {
            0 -> TeamStandings(League.NFL, open)
            1 -> TeamStandings(League.CFB, open)
            2 -> NhlStandingsView(open)
            3 -> DriverStandings(open)
            else -> ConstructorStandings(open)
        }
    }
}

@Composable
fun favTint(isFav: Boolean): Color =
    if (isFav) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent

@Composable
private fun TeamStandings(league: League, open: (Route) -> Unit) {
    val fav = Favorites.get(LocalContext.current)
    val polled = rememberPolled<List<StandingGroup>>(league, { 30 * 60_000L }) { Espn.standings(league) }
    LoadableContent(polled) { groups ->
        if (groups.isEmpty()) {
            Message("No standings published yet.")
            return@LoadableContent
        }
        val pinId = fav.pinned(league)
        // Pinned division/conference first; everything else keeps ESPN's order.
        val ordered = groups.sortedBy { if (pinId != null && it.id == pinId) 0 else 1 }
        LazyColumn {
            item {
                Text(
                    "Tap the star on a ${if (league == League.NFL) "division" else "conference"} to pin it to the top.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
            }
            ordered.forEach { grp ->
                item {
                    PinHeader(grp.title, pinned = grp.id.isNotEmpty() && grp.id == pinId, canPin = grp.id.isNotEmpty()) {
                        fav.togglePin(league, grp.id)
                    }
                }
                item { StandingLine("Team", grp.headers, header = true, logo = "") }
                items(grp.rows) { r ->
                    StandingLine(r.name, r.cols, logo = r.logo, highlight = fav.isFavTeam(league, r.teamId),
                        onClick = if (r.teamId.isNotEmpty()) { { open(Route.Team(league, r.teamId)) } } else null)
                }
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}

@Composable
fun PinHeader(title: String, pinned: Boolean, canPin: Boolean = true, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (pinned) {
            Text("Pinned", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (canPin) {
            IconButton(onClick = onToggle) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (pinned) "Unpin" else "Pin to top",
                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun StandingLine(
    name: String, cols: List<String>, logo: String, header: Boolean = false, highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().background(favTint(highlight))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!header) { Logo(logo, 22.dp); Spacer(Modifier.width(8.dp)) } else Spacer(Modifier.width(30.dp))
            Text(
                name,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            cols.forEachIndexed { i, c ->
                Text(
                    c,
                    Modifier.width(if (i == 0) 58.dp else 44.dp),
                    textAlign = TextAlign.End,
                    style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    fontWeight = if (i == 0 && !header) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (!header) HorizontalDivider()
    }
}

@Composable
private fun DriverStandings(open: (Route) -> Unit) {
    val fav = Favorites.get(LocalContext.current)
    val polled = rememberPolled<List<F1DriverStanding>>("f1d", { 30 * 60_000L }) { F1.driverStandings() }
    LoadableContent(polled) { rows ->
        LazyColumn {
            item { SimpleRow("Pos", "Driver", "Team", "Wins", "Pts", header = true) }
            items(rows) { r ->
                SimpleRow(r.pos, r.name, r.team, r.wins, r.points, highlight = fav.isFavDriver(r.name), leading = { TeamBadge(r.team) },
                    onClick = if (r.driverId.isNotEmpty()) { { open(Route.F1Driver(r.driverId, r.name)) } } else null)
            }
            item { SourceNote() }
        }
    }
}

@Composable
private fun ConstructorStandings(open: (Route) -> Unit) {
    val polled = rememberPolled<List<F1TeamStanding>>("f1c", { 30 * 60_000L }) { F1.teamStandings() }
    LoadableContent(polled) { rows ->
        LazyColumn {
            item { SimpleRow("Pos", "Team", "", "Wins", "Pts", header = true) }
            items(rows) { r ->
                SimpleRow(r.pos, r.team, "", r.wins, r.points, leading = { TeamBadge(r.team) },
                    onClick = if (r.constructorId.isNotEmpty()) { { open(Route.F1Team(r.constructorId, r.team)) } } else null)
            }
            item { SourceNote() }
        }
    }
}

@Composable
fun SimpleRow(
    pos: String,
    main: String,
    sub: String,
    a: String,
    b: String,
    header: Boolean = false,
    highlight: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Column {
        Row(
            Modifier.fillMaxWidth().background(favTint(highlight))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pos, Modifier.width(36.dp), style = style, color = color, fontWeight = FontWeight.SemiBold)
            if (leading != null) {
                leading()
                Spacer(Modifier.width(10.dp))
            } else if (header) {
                Spacer(Modifier.width(46.dp)) // keep header columns aligned with logo rows (36 + 10 gap)
            }
            Column(Modifier.weight(1f)) {
                Text(main, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotBlank() && !header) {
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(a, Modifier.width(48.dp), textAlign = TextAlign.End, style = style, color = color)
            Text(b, Modifier.width(52.dp), textAlign = TextAlign.End, style = style, color = color,
                fontWeight = if (header) FontWeight.Normal else FontWeight.Bold)
        }
        if (!header) HorizontalDivider()
    }
}

@Composable
fun SourceNote() {
    Text(
        "F1 standings and results: Jolpica F1 API. Updated after each session.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
