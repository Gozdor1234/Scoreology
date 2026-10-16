package com.nate.scoreline

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource

class MainActivity : ComponentActivity() {
    /** A game to open, set when the app is launched from a widget row. */
    private val pendingRoute = mutableStateOf<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Alerts.createChannel(this)
        if (Favorites.get(this).alertsEnabled) Alerts.schedule(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { ScorelineTheme { App(pendingRoute) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val league = intent?.getStringExtra(ScoresWidget.EXTRA_LEAGUE) ?: return
        val event = intent.getStringExtra(ScoresWidget.EXTRA_EVENT) ?: return
        val l = runCatching { League.valueOf(league) }.getOrNull() ?: return
        pendingRoute.value = Route.GameDetail(l, event)
    }
}

/** Screens pushed on top of the tabs. A tiny back stack instead of a navigation library. */
sealed interface Route {
    data class GameDetail(val league: League, val eventId: String) : Route
    data class TeamPicker(val league: League) : Route
    data class Team(val league: League, val teamId: String) : Route
    data class Player(val league: League, val athleteId: String) : Route
    data class GolfEvent(val eventId: String) : Route
    data class Golfer(val athleteId: String) : Route
    data object DriverPicker : Route
    data class F1Race(val round: String, val name: String, val hasSprint: Boolean) : Route
    data class F1Driver(val driverId: String, val name: String) : Route
    data object Search : Route
    data class F1Team(val constructorId: String, val name: String) : Route
    /** A section that isn't on the bottom bar right now (e.g. Golf), opened from Settings. */
    data class Section(val tabId: Int) : Route
}

/** The screen for a bottom-bar tab id. */
@Composable
fun TabContent(id: Int, modifier: Modifier, open: (Route) -> Unit) {
    when (id) {
        TAB_SCORES -> ScoresScreen(modifier, open)
        1 -> F1Screen(modifier, open)
        TAB_GOLF -> GolfScreen(modifier, open)
        3 -> StandingsScreen(modifier, open)
        TAB_NHL -> NhlScreen(modifier, open)
        else -> SettingsScreen(modifier, open)
    }
}


@Composable
fun App(pendingRoute: MutableState<Route?>) {
    val stack = remember { mutableStateListOf<Route>() }
    // Opened from the widget: show that game on top of the Scores tab.
    val incoming = pendingRoute.value
    LaunchedEffect(incoming) {
        if (incoming != null) {
            stack.clear()
            stack.add(incoming)
            pendingRoute.value = null
        }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val open: (Route) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.isNotEmpty()) { back() }
    // Keeps tab state (league, week, filters) alive while a detail screen is on top.
    val holder = rememberSaveableStateHolder()

    when (val top = stack.lastOrNull()) {
        is Route.GameDetail -> GameDetailScreen(top.league, top.eventId, onBack = back, open = open)
        is Route.Team -> TeamScreen(top.league, top.teamId, onBack = back, open = open)
        is Route.Player -> PlayerScreen(top.league, top.athleteId, onBack = back, open = open)
        is Route.GolfEvent -> GolfEventScreen(top.eventId, onBack = back, open = open)
        is Route.Golfer -> GolferScreen(top.athleteId, onBack = back, open = open)
        is Route.TeamPicker -> TeamPickerScreen(top.league, onBack = back)
        Route.DriverPicker -> DriverPickerScreen(onBack = back)
        is Route.F1Race -> F1RaceScreen(top.round, top.name, top.hasSprint, onBack = back, open = open)
        Route.Search -> SearchScreen(onBack = back, open = open)
        is Route.F1Driver -> F1DriverScreen(top.driverId, top.name, onBack = back, open = open)
        is Route.F1Team -> F1TeamScreen(top.constructorId, top.name, onBack = back, open = open)
        is Route.Section -> SectionScreen(top.tabId, onBack = back, open = open)
        null -> holder.SaveableStateProvider("tabs") {
          Scaffold(
            bottomBar = {
                // Press and hold an item, then drag sideways to rearrange; the order is saved.
                val fav = Favorites.get(LocalContext.current)
                ReorderableNavBar(
                    order = fav.navOrder,
                    selected = if (tab in fav.navOrder) tab else fav.navOrder.first(),
                    onSelect = { tab = it },
                    onReorder = { fav.updateNavOrder(it) },
                )
            },
        ) { pad ->
            val m = Modifier.padding(pad)
            // A tab that was just taken off the bar falls back to the first one still on it.
            val order = Favorites.get(LocalContext.current).navOrder
            TabContent(if (tab in order) tab else order.first(), m, open)
          }
        }
    }
}
