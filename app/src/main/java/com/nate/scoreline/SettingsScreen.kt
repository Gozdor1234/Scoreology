@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(modifier: Modifier, open: (Route) -> Unit) {
    val ctx = LocalContext.current
    val fav = Favorites.get(ctx)
    var permDenied by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ColorSlot?>(null) }

    // What each area looks like right now (theme color, or the custom one), for swatches and checks.
    val cs = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val barColor = extra.bar ?: NavigationBarDefaults.containerColor
    val shown: Map<ColorSlot, Color> = mapOf(
        ColorSlot.Background to cs.background,
        ColorSlot.Cards to cs.surfaceContainerHighest,
        ColorSlot.Accent to cs.primary,
        ColorSlot.Highlight to cs.secondaryContainer,
        ColorSlot.Text to cs.onSurface,
        ColorSlot.SubText to cs.onSurfaceVariant,
        ColorSlot.Lines to cs.outlineVariant,
        ColorSlot.Bars to barColor,
        ColorSlot.Live to LiveRed,
    )
    val issues = if (!fav.customColorsOn) emptyList() else ContrastCheck.issues(
        listOf(
            Triple("Main text on Background", cs.onSurface.toArgb() to cs.background.toArgb(), 4.5),
            Triple("Main text on Cards", cs.onSurface.toArgb() to cs.surfaceContainerHighest.toArgb(), 4.5),
            Triple("Main text on Favorites highlight", cs.onSurface.toArgb() to cs.secondaryContainer.toArgb(), 4.5),
            Triple("Secondary text on Background", cs.onSurfaceVariant.toArgb() to cs.background.toArgb(), 3.0),
            Triple("Accent on Background", cs.primary.toArgb() to cs.background.toArgb(), 3.0),
            Triple("Live color on Cards", LiveRed.toArgb() to cs.surfaceContainerHighest.toArgb(), 3.0),
        ),
    )

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            fav.setAlerts(true); Alerts.schedule(ctx); permDenied = false
        } else {
            permDenied = true
        }
    }

    fun setAlerts(on: Boolean) {
        if (!on) { fav.setAlerts(false); Alerts.cancel(ctx); return }
        if (Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(ctx)) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            fav.setAlerts(true); Alerts.schedule(ctx)
        }
    }

    LazyColumn(modifier.fillMaxSize()) {
        item { SectionHeader("Favorite teams") }
        val teams = fav.teams.sorted()
        if (teams.isEmpty()) item { Hint("No favorites yet. Add some below, or tap the heart on any game.") }
        items(teams) { key ->
            val league = League.entries.firstOrNull { key.startsWith("${it.name}:") } ?: League.NFL
            val name = fav.teamNames[key] ?: key
            RemovableRow("$name  ·  ${league.label}") { fav.toggleTeam(league, key.substringAfter(':'), name) }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { open(Route.TeamPicker(League.NFL)) }) { Icon(Icons.Filled.Add, null); Text(" NFL") }
                OutlinedButton(onClick = { open(Route.TeamPicker(League.CFB)) }) { Icon(Icons.Filled.Add, null); Text(" College") }
                OutlinedButton(onClick = { open(Route.TeamPicker(League.NHL)) }) { Icon(Icons.Filled.Add, null); Text(" NHL") }
            }
        }

        item { SectionHeader("Bottom bar") }
        item { Hint("Turn sections on or off and use the arrows to set their order. Sections that are off stay in the app: tap Open to visit one anytime.") }
        item { BottomBarEditor(fav, open) }

        item { SectionHeader("Favorite F1 drivers") }
        val drivers = fav.driverNames.sorted()
        if (drivers.isEmpty()) item { Hint("None yet.") }
        items(drivers) { d -> RemovableRow(d) { fav.toggleDriver(d) } }
        item {
            OutlinedButton(onClick = { open(Route.DriverPicker) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Icon(Icons.Filled.Add, null); Text(" F1 driver")
            }
        }

        item { SectionHeader("Alerts") }
        item { SwitchRow("Notifications for my teams", fav.alertsEnabled) { setAlerts(it) } }
        item { SwitchRow("Include score changes (not just kickoff and final)", fav.scoreAlerts, enabled = fav.alertsEnabled) { fav.updateScoreAlerts(it) } }
        item { SwitchRow("F1 qualifying, sprint and race results", fav.f1Alerts, enabled = fav.alertsEnabled) { fav.updateF1Alerts(it) } }
        if (permDenied) item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Hint("Notification permission was denied. Enable it in Android settings to get alerts.")
                TextButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Open app settings") }
            }
        }
        item {
            Hint(
                "Alerts check about every 15 minutes (Android's minimum for background work) and can be delayed " +
                    "further when the phone is idle. For play-by-play, keep the app open: live screens refresh every 20 to 30 seconds. " +
                    "If alerts seem to stop, set Scoreology's battery usage to Unrestricted in Android settings.",
            )
        }

        item { SectionHeader("Betting odds") }
        item { SwitchRow("Show odds on upcoming games", fav.showOdds) { fav.updateShowOdds(it) } }
        item { OddsKeySettings(fav) }

        item { SectionHeader("Appearance") }
        item { SwitchRow("Modern style (glass panels, soft shadows)", fav.modernStyle) { fav.updateModernStyle(it) } }
        item { Hint("Off keeps the standard look. Works with every theme below. The home-screen widget always uses the standard look.") }
        item {
            SwitchRow("Match my phone's colors", fav.matchPhoneColors && supportsPhoneColors, enabled = supportsPhoneColors) {
                fav.updateMatchPhoneColors(it)
            }
        }
        item {
            Hint(
                if (supportsPhoneColors) "Uses the color palette from your phone's wallpaper and style settings. Turn off for the app's own colors."
                else "Matching phone colors needs Android 12 or newer.",
            )
        }
        item {
            Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark", "amoled" to "AMOLED").forEach { (key, label) ->
                    FilterChip(
                        selected = fav.themeMode == key,
                        onClick = { fav.updateThemeMode(key) },
                        label = { Text(label) },
                    )
                }
            }
        }
        item { Hint("System follows your phone's light/dark setting. AMOLED is dark mode with pure black backgrounds.") }

        item { SectionHeader("Custom colors") }
        item { SwitchRow("Use custom colors", fav.customColorsOn) { fav.updateCustomColorsOn(it) } }
        item {
            Hint(
                "Pick your own color for each part of the app. Custom colors sit on top of the theme above; " +
                    "anything left on Default keeps the theme's color. Turning this off keeps your picks for later.",
            )
        }
        if (fav.customColorsOn) {
            ColorSlot.entries.forEach { slot ->
                item {
                    ColorSlotRow(slot, shown[slot] ?: Color.Gray, isCustom = slot in fav.customColors) { editing = slot }
                }
            }
            if (issues.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            "Hard to read",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        issues.forEach { i ->
                            Text(
                                "${i.message}: contrast ${"%.1f".format(i.ratio)}:1 (aim for at least 4.5:1 for text)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            if (fav.customColors.isNotEmpty()) {
                item {
                    TextButton(onClick = { fav.resetCustomColors() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Reset all colors to default")
                    }
                }
            }
        }

        item { SectionHeader("About") }
        item {
            Hint(
                "Scores and stats use ESPN's public but unofficial endpoints, which can change without notice. " +
                    "F1 standings and results use the Jolpica F1 API. Personal use only.",
            )
        }
        item { SectionHeader("About the developer") }
        item {
            Text(
                "Scoreology is a one-person project by ChickenMyBobbers. What started as a weekend project " +
                    "turned into a single app with all the good parts of the sports apps out there, minus the " +
                    "ads, bulk, and nonsense. I hope you enjoy!",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item { Spacer(Modifier.padding(24.dp)) }
    }

    editing?.let { slot ->
        // key(): a fresh picker (and fresh starting color) for each area.
        key(slot) {
            ColorPickerDialog(
                title = slot.label,
                initial = (shown[slot] ?: Color.Gray).toArgb(),
                isCustom = slot in fav.customColors,
                onApply = { fav.updateCustomColor(slot, it); editing = null },
                onReset = { fav.updateCustomColor(slot, null); editing = null },
                onDismiss = { editing = null },
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun RemovableRow(text: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Favorite, null, tint = LiveRed)
        Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun PickerScaffold(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = scorelineTopBarColors(),
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { pad -> content(Modifier.padding(pad)) }
}

@Composable
private fun PickRow(name: String, sub: String, logo: String, selected: Boolean, onToggle: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (logo.isNotBlank()) { Logo(logo, 28.dp); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                if (selected) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = if (selected) LiveRed else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
    }
}

@Composable
fun TeamPickerScreen(league: League, onBack: () -> Unit) {
    val fav = Favorites.get(LocalContext.current)
    var query by rememberSaveable { mutableStateOf("") }
    val polled = rememberPolled<List<TeamRef>>("teams-${league.name}", { 24 * 60 * 60_000L }) { Espn.teams(league) }
    PickerScaffold("Add ${league.label} teams", onBack) { m ->
        Column(m.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(Modifier.weight(1f)) {
                LoadableContent(polled) { teams ->
                    val q = query.trim()
                    val shown = if (q.isEmpty()) teams else teams.filter {
                        it.name.contains(q, ignoreCase = true) || it.abbr.equals(q, ignoreCase = true)
                    }
                    LazyColumn {
                        items(shown, key = { it.id }) { t ->
                            PickRow(t.name, t.abbr, t.logo, fav.isFavTeam(league, t.id)) { fav.toggleTeam(league, t.id, t.name) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DriverPickerScreen(onBack: () -> Unit) {
    val fav = Favorites.get(LocalContext.current)
    val polled = rememberPolled<List<F1DriverStanding>>("drivers", { 60 * 60_000L }) { F1.driverStandings() }
    PickerScaffold("Add F1 drivers", onBack) { m ->
        Box(m.fillMaxSize()) {
            LoadableContent(polled, emptyText = "No drivers listed yet.") { drivers ->
                LazyColumn {
                    items(drivers, key = { it.name }) { d ->
                        PickRow(d.name, d.team, "", fav.isFavDriver(d.name)) { fav.toggleDriver(d.name) }
                    }
                }
            }
        }
    }
}

/**
 * The Odds API key entry. With a key, games show FanDuel lines; without one, ESPN's DraftKings lines.
 * Shows remaining free credits so the 500/month limit is visible.
 */
@Composable
private fun OddsKeySettings(fav: Favorites) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf(fav.oddsApiKey) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { FanDuelOdds.load(ctx) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            if (fav.oddsApiKey.isBlank()) {
                "Showing DraftKings lines from ESPN. For FanDuel lines, get a free API key from the-odds-api.com (500 credits a month) and paste it below."
            } else {
                "Showing FanDuel lines from The Odds API. Lines refresh at most every 3 hours per league to save credits; games FanDuel hasn't priced fall back to DraftKings."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://the-odds-api.com/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Open the-odds-api.com") }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("The Odds API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(
                enabled = draft.trim() != fav.oddsApiKey,
                onClick = {
                    fav.updateOddsApiKey(draft)
                    FanDuelOdds.clear(ctx)
                },
            ) { Text("Save key") }
            if (fav.oddsApiKey.isNotBlank()) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            FanDuelOdds.get(ctx, League.NFL, force = true)
                            FanDuelOdds.get(ctx, League.CFB, force = true)
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Updating…" else "Update now (6 credits)") }
            }
        }
        if (fav.oddsApiKey.isNotBlank()) {
            val credits = FanDuelOdds.creditsLeft
            val status = listOfNotNull(
                credits?.let { "$it credits left this month" },
                FanDuelOdds.lastFetch.takeIf { it > 0 }?.let { "last updated ${agoText(it).removePrefix("Updated ").ifBlank { "just now" }}" },
            ).joinToString("  ·  ")
            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
            FanDuelOdds.lastError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}


/** Settings > Bottom bar: shown tabs in order (arrows to move, switch to hide), then hidden ones. */
@Composable
private fun BottomBarEditor(fav: Favorites, open: (Route) -> Unit) {
    val shown = fav.navOrder
    val hidden = navTabs.indices.filter { it !in shown }
    Column {
        shown.forEachIndexed { i, id ->
            BarTabRow(
                id = id,
                on = true,
                // Settings always stays on the bar, and the bar keeps at least one other section.
                canToggle = id != TAB_SETTINGS && shown.size > 2,
                onToggle = { fav.updateNavOrder(shown - id) },
                onUp = if (i > 0) { { fav.updateNavOrder(shown.toMutableList().apply { add(i - 1, removeAt(i)) }) } } else null,
                onDown = if (i < shown.lastIndex) { { fav.updateNavOrder(shown.toMutableList().apply { add(i + 1, removeAt(i)) }) } } else null,
                onOpen = null,
            )
        }
        hidden.forEach { id ->
            BarTabRow(
                id = id,
                on = false,
                canToggle = true,
                // Added just before Settings when Settings is last, otherwise at the end.
                onToggle = {
                    val at = if (shown.lastOrNull() == TAB_SETTINGS) shown.lastIndex else shown.size
                    fav.updateNavOrder(shown.toMutableList().apply { add(at, id) })
                },
                onUp = null,
                onDown = null,
                onOpen = { open(Route.Section(id)) },
            )
        }
    }
}

@Composable
private fun BarTabRow(
    id: Int,
    on: Boolean,
    canToggle: Boolean,
    onToggle: () -> Unit,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    onOpen: (() -> Unit)?,
) {
    val t = navTabs[id]
    val tint = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t.icon != null) Icon(t.icon, contentDescription = null, tint = tint)
        else Icon(androidx.compose.ui.res.painterResource(t.drawable), contentDescription = null, tint = tint)
        Spacer(Modifier.width(14.dp))
        Text(t.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = tint)
        if (onOpen != null) TextButton(onClick = onOpen) { Text("Open") }
        if (on) {
            IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${t.label} up")
            }
            IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${t.label} down")
            }
        }
        Switch(checked = on, onCheckedChange = { onToggle() }, enabled = canToggle)
    }
}
