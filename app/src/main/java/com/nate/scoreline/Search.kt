@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

/** A page's top tabs with a search button at the left, on the same line. */
@Composable
fun SearchTabs(open: (Route) -> Unit, tabs: @Composable () -> Unit) {
    val modern = LocalModern.current
    Surface(color = if (modern) Color.Transparent else TabRowDefaults.primaryContainerColor) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = if (modern) Modifier.padding(start = 8.dp, top = 4.dp) else Modifier) {
            IconButton(onClick = { open(Route.Search) }, modifier = Modifier.modernRound()) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
            Box(Modifier.weight(1f)) { tabs() }
        }
    }
}

enum class HitKind(val label: String) { F1_DRIVER("F1 drivers"), F1_TEAM("F1 teams"), TEAM("Teams"), PLAYER("Players"), GOLFER("Golfers") }

data class SearchHit(val kind: HitKind, val name: String, val subtitle: String, val image: String, val route: Route)

object SearchData {
    /**
     * ESPN search results -> hits for the leagues this app covers (NFL, college football, PGA golf).
     * uid looks like "s:20~l:28~t:3" (team) or "s:20~l:23~a:5075805" (athlete).
     */
    fun parseEspn(root: JSONObject): List<SearchHit> =
        root.arr("results").objects().filter { it.str("type") == "team" || it.str("type") == "player" }.flatMap { group ->
            group.arr("contents").objects().mapNotNull { c ->
                val uid = c.str("uid")
                val parts = uid.split('~').associate { it.substringBefore(':') to it.substringAfter(':') }
                val slug = c.str("defaultLeagueSlug")
                val img = c.obj("image")?.str("default").orEmpty()
                val name = c.str("displayName")
                val sub = listOf(c.str("subtitle"), c.str("description")).filter { it.isNotBlank() }.distinct().joinToString("  ·  ")
                val league = when (slug) {
                    "nfl" -> League.NFL
                    "college-football" -> League.CFB
                    "nhl" -> League.NHL
                    else -> null
                }
                when {
                    league != null && parts["t"] != null ->
                        SearchHit(HitKind.TEAM, name, sub, img, Route.Team(league, parts.getValue("t")))
                    league != null && parts["a"] != null ->
                        SearchHit(HitKind.PLAYER, name, sub, img, Route.Player(league, parts.getValue("a")))
                    slug == "pga" && parts["a"] != null ->
                        SearchHit(HitKind.GOLFER, name, sub.ifBlank { "Golf" }, img, Route.Golfer(parts.getValue("a")))
                    else -> null
                }
            }
        }

    /** Words in the query must each start a word in the name ("lan nor" finds Lando Norris). */
    fun matches(name: String, query: String): Boolean {
        val words = F1.normalizeForSearch(name).split(' ').filter { it.isNotEmpty() }
        val q = F1.normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
        return q.isNotEmpty() && q.all { t -> words.any { it.startsWith(t) } }
    }

    private var f1Cache: Pair<Long, Pair<List<F1DriverStanding>, List<F1TeamStanding>>>? = null

    private suspend fun f1Lists(): Pair<List<F1DriverStanding>, List<F1TeamStanding>> {
        f1Cache?.takeIf { System.currentTimeMillis() - it.first < 3600_000L }?.let { return it.second }
        return coroutineScope {
            val d = async { runCatching { F1.driverStandings() }.getOrDefault(emptyList()) }
            val t = async { runCatching { F1.teamStandings() }.getOrDefault(emptyList()) }
            (d.await() to t.await()).also { if (it.first.isNotEmpty()) f1Cache = System.currentTimeMillis() to it }
        }
    }

    suspend fun search(query: String): List<SearchHit> = coroutineScope {
        val q = query.trim()
        if (q.length < 2) return@coroutineScope emptyList()
        val espn = async {
            runCatching {
                parseEspn(Net.getJson("https://site.web.api.espn.com/apis/search/v2?query=${URLEncoder.encode(q, "UTF-8")}&limit=25"))
            }.getOrDefault(emptyList())
        }
        val (drivers, teams) = f1Lists()
        val f1 = drivers.filter { it.driverId.isNotEmpty() && matches(it.name, q) }.map {
            SearchHit(HitKind.F1_DRIVER, it.name, "F1  ·  ${it.team}", "", Route.F1Driver(it.driverId, it.name))
        } + teams.filter { it.constructorId.isNotEmpty() && matches(it.team, q) }.map {
            SearchHit(HitKind.F1_TEAM, it.team, "F1  ·  P${it.pos} in the constructors' championship", "", Route.F1Team(it.constructorId, it.team))
        }
        f1 + espn.await()
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, open: (Route) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Wait for a short pause in typing before searching.
    LaunchedEffect(query) {
        if (query.trim().length < 2) { hits = emptyList(); loading = false; return@LaunchedEffect }
        loading = true
        delay(350)
        hits = SearchData.search(query)
        loading = false
    }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = scorelineTopBarColors(),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Teams, players, drivers, golfers") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                query.trim().length < 2 -> Message("Search NFL and college teams and players, F1 drivers and teams, and golfers.")
                loading && hits.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                hits.isEmpty() -> Message("No matches for \"${query.trim()}\".")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    HitKind.entries.forEach { kind ->
                        val group = hits.filter { it.kind == kind }
                        if (group.isNotEmpty()) {
                            item(key = "h-$kind") { SectionHeader(kind.label) }
                            items(group, key = { "${kind}-${it.route}" }) { h -> HitRow(h) { open(h.route) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HitRow(h: SearchHit, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (h.kind) {
                HitKind.F1_DRIVER, HitKind.F1_TEAM -> TeamBadge(h.subtitle.substringAfter("·").trim().takeIf { h.kind == HitKind.F1_DRIVER } ?: h.name, 30.dp)
                HitKind.PLAYER, HitKind.GOLFER -> Headshot(h.image, 40.dp)
                HitKind.TEAM -> Logo(h.image, 36.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(h.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (h.subtitle.isNotBlank()) {
                    Text(h.subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider()
    }
}
