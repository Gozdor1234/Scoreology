package com.nate.scoreline

import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Wallpaper-based "Material You" colors exist on Android 12 (API 31) and newer. */
val supportsPhoneColors: Boolean get() = Build.VERSION.SDK_INT >= 31

/** True black backgrounds so OLED pixels switch off; cards stay just visible. */
private fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF0E0E0E),
    surfaceContainerHigh = Color(0xFF151515),
    surfaceContainerHighest = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFF1A1A1A),
)

/**
 * Dark mode surfaces in slate gray-blue instead of near-black. Accent colors (from the phone
 * palette or the defaults) are kept; only backgrounds, cards and outlines change.
 */
private fun ColorScheme.toGrayBlue(): ColorScheme = copy(
    background = Color(0xFF1B2230),
    surface = Color(0xFF1B2230),
    surfaceDim = Color(0xFF161C28),
    surfaceBright = Color(0xFF3A4556),
    surfaceContainerLowest = Color(0xFF141A24),
    surfaceContainerLow = Color(0xFF1F2735),
    surfaceContainer = Color(0xFF232C3B),
    surfaceContainerHigh = Color(0xFF2A3445),
    surfaceContainerHighest = Color(0xFF313C4F),
    surfaceVariant = Color(0xFF354154),
    onSurfaceVariant = Color(0xFFBFC8D6),
    outline = Color(0xFF8A94A6),
    outlineVariant = Color(0xFF3F4A5C),
)

@Composable
fun ScorelineTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val prefs = Favorites.get(ctx)
    val mode = prefs.themeMode
    val dark = when (mode) {
        "light" -> false
        "dark", "amoled" -> true
        else -> isSystemInDarkTheme()
    }
    val base = when {
        prefs.matchPhoneColors && supportsPhoneColors ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val themed = when {
        mode == "amoled" -> base.toAmoled()
        dark -> base.toGrayBlue()
        else -> base
    }
    val custom = if (prefs.customColorsOn) prefs.customColors else emptyMap()
    val modern = prefs.modernStyle
    // Modern light mode sits on a soft gray-blue page so the glass edges and shadows show.
    // With phone colors, keep their hue but shade the near-white page slightly so the white
    // highlights on glass edges stay visible.
    val modernBase = if (modern && !dark) {
        val page = if (prefs.matchPhoneColors && supportsPhoneColors) {
            Color(ColorMath.mix(themed.background.toArgb(), 0xFF1E3A64.toInt(), 0.07f))
        } else ModernLightBackground
        themed.copy(background = page, surface = page, surfaceContainer = page, surfaceContainerLow = page)
    } else themed
    val scheme = modernBase.withCustom(custom)
    val extra = extraColors(custom)

    // Status/navigation bar icons follow the actual background, so they stay readable
    // when the app's mode or a custom background differs from the phone's setting.
    val barsDark = ColorMath.isDark(scheme.background.toArgb())
    val activity = ctx as? ComponentActivity
    LaunchedEffect(barsDark, activity) {
        activity?.enableEdgeToEdge(
            statusBarStyle = if (barsDark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = if (barsDark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
    }

    CompositionLocalProvider(LocalExtraColors provides extra, LocalModern provides modern) {
        MaterialTheme(colorScheme = scheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}

/** Colors outside Material's scheme: the live indicator and optional custom bar color. */
data class ExtraColors(val live: Color, val bar: Color?, val onBar: Color?)

private val DefaultLive = Color(0xFFE53935)
val LocalExtraColors = staticCompositionLocalOf { ExtraColors(DefaultLive, null, null) }

/** The live/favorite accent (red unless customized). */
val LiveRed: Color
    @Composable @ReadOnlyComposable get() = LocalExtraColors.current.live

private fun extraColors(custom: Map<ColorSlot, Int>): ExtraColors {
    val bar = custom[ColorSlot.Bars]
    return ExtraColors(
        live = custom[ColorSlot.Live]?.let { Color(it) } ?: DefaultLive,
        bar = bar?.let { Color(it) },
        onBar = bar?.let { Color(ColorMath.onColorFor(it)) },
    )
}

/**
 * Layers the user's custom colors over the theme. Anything not customized keeps the theme's
 * color, except where that would break readability (text on a new background, text on buttons),
 * which is derived automatically.
 */
private fun ColorScheme.withCustom(c: Map<ColorSlot, Int>): ColorScheme {
    if (c.isEmpty()) return this
    var s = this
    c[ColorSlot.Background]?.let { bg ->
        val e = { amt: Float -> Color(ColorMath.elevate(bg, amt)) }
        s = s.copy(
            background = Color(bg), surface = Color(bg), surfaceDim = Color(bg), surfaceContainerLowest = Color(bg),
            surfaceContainerLow = e(0.04f), surfaceContainer = e(0.07f), surfaceContainerHigh = e(0.10f),
            surfaceContainerHighest = e(0.13f), surfaceVariant = e(0.16f), surfaceBright = e(0.20f),
        )
        // If text wasn't customized and no longer reads on this background, switch it to black or white.
        if (ColorSlot.Text !in c && ColorMath.contrast(s.onSurface.toArgb(), bg) < 4.5) {
            val on = ColorMath.onColorFor(bg)
            s = s.copy(onBackground = Color(on), onSurface = Color(on))
            if (ColorSlot.SubText !in c) s = s.copy(onSurfaceVariant = Color(ColorMath.mix(on, bg, 0.3f)))
        }
    }
    c[ColorSlot.Cards]?.let { card ->
        val bg = s.background.toArgb()
        s = s.copy(
            surfaceContainerLow = Color(ColorMath.mix(card, bg, 0.5f)),
            surfaceContainer = Color(card), surfaceContainerHigh = Color(card), surfaceContainerHighest = Color(card),
            surfaceVariant = Color(ColorMath.elevate(card, 0.06f)),
        )
    }
    c[ColorSlot.Accent]?.let { a ->
        val container = ColorMath.mix(a, s.background.toArgb(), 0.65f)
        s = s.copy(
            primary = Color(a), onPrimary = Color(ColorMath.onColorFor(a)), surfaceTint = Color(a),
            primaryContainer = Color(container), onPrimaryContainer = Color(ColorMath.onColorFor(container)),
        )
    }
    c[ColorSlot.Highlight]?.let { h ->
        s = s.copy(secondaryContainer = Color(h), onSecondaryContainer = Color(ColorMath.onColorFor(h)))
    }
    c[ColorSlot.Text]?.let { t -> s = s.copy(onBackground = Color(t), onSurface = Color(t)) }
    c[ColorSlot.SubText]?.let { t -> s = s.copy(onSurfaceVariant = Color(t)) }
    c[ColorSlot.Lines]?.let { l -> s = s.copy(outline = Color(l), outlineVariant = Color(l)) }
    return s
}

class Loadable<T>(val data: T?, val error: String?, val loading: Boolean, val updatedAt: Long)
class Polled<T>(val state: Loadable<T>, val refresh: () -> Unit)

const val LIVE_MS = 30_000L
const val IDLE_MS = 5 * 60_000L

/**
 * Fetches immediately, then again every intervalMs(data). Runs only while the screen
 * is at least STARTED, so nothing polls in the background; returning to the app refreshes.
 */
@Composable
fun <T> rememberPolled(key: Any?, intervalMs: (T?) -> Long, fetch: suspend () -> T): Polled<T> {
    var state by remember(key) { mutableStateOf(Loadable<T>(null, null, true, 0L)) }
    var tick by remember(key) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentFetch by rememberUpdatedState(fetch)
    val currentInterval by rememberUpdatedState(intervalMs)
    LaunchedEffect(key, tick) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                state = Loadable(state.data, null, true, state.updatedAt)
                state = try {
                    Loadable(currentFetch(), null, false, System.currentTimeMillis())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Loadable(state.data, e.message ?: e.javaClass.simpleName, false, state.updatedAt)
                }
                delay(currentInterval(state.data))
            }
        }
    }
    return Polled(state) { tick++ }
}

/** Standard wrapper: spinner on first load, error with retry, thin bar while refreshing. */
@Composable
fun <T> LoadableContent(polled: Polled<T>, emptyText: String = "Nothing here right now.", content: @Composable (T) -> Unit) {
    val s = polled.state
    Column(Modifier.fillMaxSize()) {
        if (s.loading && s.data != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (s.error != null && s.data != null) {
            Text(
                "Couldn't refresh (${s.error}). Showing last data.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when {
            s.data != null -> content(s.data)
            s.error != null -> Message("Couldn't load data.\n${s.error}", action = "Retry", onAction = polled.refresh)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        if (action != null) Button(onClick = onAction, modifier = Modifier.padding(top = 12.dp)) { Text(action) }
    }
}

@Composable
fun Logo(url: String, size: Dp = 28.dp) {
    if (url.isBlank()) Box(Modifier.size(size)) else AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size))
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun LiveBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).padding(0.dp)) {
            Surface(color = LiveRed, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxSize()) {}
        }
        Text(" LIVE", color = LiveRed, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

private val localFmt = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")

/** ESPN times look like 2026-09-27T17:00Z; show them in the phone's time zone. */
fun formatLocal(iso: String): String = try {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(localFmt)
} catch (e: Exception) {
    ""
}

fun agoText(updatedAt: Long): String {
    if (updatedAt == 0L) return ""
    val s = (System.currentTimeMillis() - updatedAt) / 1000
    return if (s < 60) "Updated just now" else "Updated ${s / 60} min ago"
}

/**
 * F1 team logo (official image from formula1.com) in full color, on light and dark themes.
 * On dark themes a logo that is itself mostly dark (it would vanish on the background) is
 * swapped for the team's all-white version. Falls back to a round badge with the team code.
 */
@Composable
fun TeamBadge(team: String, size: Dp = 26.dp) {
    val darkUi = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val year = remember { java.time.LocalDate.now().year }
    var white by remember(team, darkUi) { mutableStateOf(darkUi && F1Teams.darkLogo[team] == true) }
    val urls = remember(team, white, year) { F1Teams.logoUrls(team, white, year) }
    var attempt by remember(urls) { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    // Logos are mostly wordmarks, so give them a wider box than the round badge.
    Box(Modifier.width(size * 1.4f).height(size), contentAlignment = Alignment.Center) {
        if (attempt < urls.size) {
            val checkShade = darkUi && !white && F1Teams.darkLogo[team] == null
            val request = remember(urls, attempt, checkShade) {
                coil.request.ImageRequest.Builder(ctx).data(urls[attempt])
                    .apply { if (checkShade) allowHardware(false) }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = team,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onError = { attempt++ },
                onSuccess = { st ->
                    if (checkShade) {
                        val tooDark = F1Teams.isMostlyDark(st.result.drawable)
                        F1Teams.darkLogo[team] = tooDark
                        if (tooDark) white = true
                    }
                },
            )
        } else {
            val st = F1Teams.style(team)
            Box(
                Modifier.size(size).clip(CircleShape).background(Color(st.color)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    st.code,
                    color = Color.White,
                    fontSize = (size.value * 0.33f).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** Screen-header colors, honoring the custom bar color (with auto black/white content). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun scorelineTopBarColors(): TopAppBarColors {
    val e = LocalExtraColors.current
    val bar = e.bar
    val on = e.onBar
    return if (bar == null || on == null) TopAppBarDefaults.topAppBarColors()
    else TopAppBarDefaults.topAppBarColors(
        containerColor = bar,
        scrolledContainerColor = bar,
        titleContentColor = on,
        navigationIconContentColor = on,
        actionIconContentColor = on,
    )
}

/**
 * A team's color, adjusted to stay visible: on a dark screen a near-black primary (e.g. Raiders)
 * switches to the team's alternate color; on a light screen a near-white one does the same.
 */
fun teamColor(hex: String, altHex: String, darkUi: Boolean): Color? {
    val main = ColorMath.parseHex(hex)
    val alt = ColorMath.parseHex(altHex)
    val pick = when {
        main == null -> alt
        darkUi && ColorMath.luminance(main) < 0.02 && alt != null && ColorMath.luminance(alt) > ColorMath.luminance(main) -> alt
        !darkUi && ColorMath.luminance(main) > 0.85 && alt != null -> alt
        else -> main
    }
    return pick?.let { Color(it) }
}

/**
 * A team color bright enough to show as a glow. Near-black brand colors (navy, dark teal) would
 * blur into a plain gray shadow, so a dark main color gives way to a vivid alternate (Texans red),
 * or, if the alternate is gray or silver, is lifted to a brighter shade of the same hue (Cowboys blue).
 */
fun glowColor(hex: String, altHex: String, darkUi: Boolean): Color? {
    val main = ColorMath.parseHex(hex)
    val alt = ColorMath.parseHex(altHex)
    fun hsv(c: Int) = ColorMath.argbToHsv(c)
    var pick = main ?: alt ?: return null
    if (!darkUi && ColorMath.luminance(pick) > 0.85 && alt != null) pick = alt
    val h = hsv(pick)
    if (h[2] < 0.5f) {
        val a = alt?.let(::hsv)
        pick = if (a != null && a[1] > 0.45f && a[2] > 0.35f && ColorMath.luminance(alt) < 0.85) alt
        else ColorMath.hsvToArgb(h[0], h[1], maxOf(h[2], if (darkUi) 0.75f else 0.62f))
    }
    return Color(pick)
}

/** Soft team-color fades behind each side of a matchup header (away on the left, home on the right). */
fun matchupBrush(away: Color?, home: Color?): Brush = Brush.horizontalGradient(
    0f to (away?.copy(alpha = 0.55f) ?: Color.Transparent),
    0.45f to Color.Transparent,
    0.55f to Color.Transparent,
    1f to (home?.copy(alpha = 0.55f) ?: Color.Transparent),
)
