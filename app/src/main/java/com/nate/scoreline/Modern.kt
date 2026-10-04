package com.nate.scoreline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * "Modern" style: frosted-glass surfaces with soft neumorphic (raised / pressed-in) shadows.
 * Turned on in Settings > Appearance. Everything here falls back to the standard Material look
 * when it's off, so screens just call Card / TabRow / Tab / FilterChip as usual.
 * The home-screen widget is untouched (it can't draw these effects).
 */

/** True when the Modern style is on. */
val LocalModern = staticCompositionLocalOf { false }

@Immutable
data class ModernPalette(
    /** Solid page color the shadows are cast from. */
    val base: Color,
    /** Translucent glass fill on raised surfaces. */
    val glass: Color,
    /** Fill for pressed-in wells. */
    val well: Color,
    /** Thin highlight edge around glass. */
    val edge: Color,
    /** Bottom-right shadow. */
    val shade: Color,
    /** Top-left highlight. */
    val light: Color,
    /** Faint dark hairline so edges stay visible on every side, even on a near-white page. */
    val outline: Color = Color.Transparent,
    /** Soft all-around shadow that outlines the left and top edges. */
    val ambient: Color = Color.Transparent,
)

val modernPalette: ModernPalette
    @Composable @ReadOnlyComposable get() {
        val bg = MaterialTheme.colorScheme.background
        return if (bg.luminance() < 0.3f) {
            ModernPalette(
                base = bg,
                glass = Color.White.copy(alpha = 0.06f),
                well = Color.Black.copy(alpha = 0.18f),
                edge = Color.White.copy(alpha = 0.10f),
                shade = Color.Black.copy(alpha = 0.60f),
                light = Color.White.copy(alpha = 0.05f),
            )
        } else {
            ModernPalette(
                base = bg,
                glass = Color.White.copy(alpha = 0.42f),
                well = Color.White.copy(alpha = 0.16f),
                edge = Color.White.copy(alpha = 0.80f),
                shade = Color(0xFF1E3A64).copy(alpha = 0.22f),
                light = Color.White.copy(alpha = 0.90f),
                outline = Color(0xFF1E3A64).copy(alpha = 0.12f),
                ambient = Color(0xFF1E3A64).copy(alpha = 0.10f),
            )
        }
    }

/** Light page color used by Modern in light mode (unless a custom background is set). */
val ModernLightBackground = Color(0xFFE6ECF3)

/** Raised glass surface: soft shadow bottom-right, glow top-left, translucent fill, bright edge. */
fun Modifier.neuRaised(p: ModernPalette, radius: Dp, distance: Dp = 5.dp, blur: Dp = 10.dp, shadows: Boolean = true): Modifier = this.drawBehind {
    val r = radius.toPx()
    val d = distance.toPx()
    val b = blur.toPx()
    drawIntoCanvas { c ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = p.base.toArgb()
        if (!shadows) {
            // Card with a team glow: keep the solid backing but skip the gray shadow (bottom right)
            // and white highlight (top left), which would make one team's glow look stronger.
            c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
            return@drawIntoCanvas
        }
        paint.setShadowLayer(b, d, d, p.shade.toArgb())
        c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
        paint.setShadowLayer(b, -d, -d, p.light.toArgb())
        c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
        if (p.ambient.alpha > 0f) {
            paint.setShadowLayer(b * 0.6f, 0f, 0f, p.ambient.toArgb())
            c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
        }
    }
    val cr = androidx.compose.ui.geometry.CornerRadius(r)
    drawRoundRect(p.glass, cornerRadius = cr)
    // Dark hairline outside, bright edge just inside: a crisp border on all four sides.
    if (p.outline.alpha > 0f) drawRoundRect(p.outline, cornerRadius = cr, style = Stroke(1.dp.toPx()))
    val inset = 1.dp.toPx()
    drawRoundRect(
        p.edge,
        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
        size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius((r - inset).coerceAtLeast(0f)),
        style = Stroke(1.dp.toPx()),
    )
}

/** Pressed-in well: inner shadow top-left, inner glow bottom-right. */
fun Modifier.neuInset(p: ModernPalette, radius: Dp, distance: Dp = 3.dp, blur: Dp = 7.dp): Modifier = this.drawBehind {
    val r = radius.toPx()
    val d = distance.toPx()
    val b = blur.toPx()
    drawRoundRect(p.well, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        val clip = android.graphics.Path().apply {
            addRoundRect(0f, 0f, size.width, size.height, r, r, android.graphics.Path.Direction.CW)
        }
        nc.save()
        nc.clipPath(clip)
        // A frame just outside the shape casts its shadow inward; the clip keeps only that shadow.
        val frame = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = b * 2
        }
        frame.color = p.shade.toArgb()
        frame.setShadowLayer(b, d, d, p.shade.toArgb())
        nc.drawRoundRect(-b, -b, size.width + b, size.height + b, r + b, r + b, frame)
        frame.color = p.light.toArgb()
        frame.setShadowLayer(b, -d, -d, p.light.toArgb())
        nc.drawRoundRect(-b, -b, size.width + b, size.height + b, r + b, r + b, frame)
        nc.restore()
    }
}

// ---------------------------------------------------------------- drop-in components

/** Card that becomes a raised glass panel in Modern. A non-default container color becomes a soft tint. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalModern.current) {
        androidx.compose.material3.Card(modifier = modifier, colors = colors, content = content)
    } else {
        ModernCard(modifier, colors, null, content)
    }
}

@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.cardColors(),
    glow: CardGlow? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalModern.current) {
        val m = if (glow != null) modifier.teamGlow(glow.top, glow.bottom, 12.dp) else modifier
        androidx.compose.material3.Card(onClick = onClick, modifier = m, colors = colors, content = content)
    } else {
        ModernCard(modifier, colors, onClick, content, glow)
    }
}

/** Team colors for a card's outer glow: [top] along the upper edge, [bottom] along the lower edge. */
data class CardGlow(val top: Color?, val bottom: Color?)

@Composable
private fun ModernCard(
    modifier: Modifier,
    colors: CardColors,
    onClick: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
    glow: CardGlow? = null,
) {
    val p = modernPalette
    val glowing = glow != null && (glow.top != null || glow.bottom != null)
    val shape = RoundedCornerShape(20.dp)
    val default = CardDefaults.cardColors().containerColor
    val tint = colors.containerColor.takeIf { it != default }
    Column(
        modifier
            .then(if (glowing) Modifier.teamGlow(glow!!.top, glow.bottom, 20.dp) else Modifier)
            .neuRaised(p, 20.dp, shadows = !glowing)
            .clip(shape)
            .then(if (tint != null) Modifier.background(tint.copy(alpha = 0.35f)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}

/** Top tab row. Modern: a pressed-in track with the selected tab as a raised pill (see [Tab]). */
@Composable
fun TabRow(selectedTabIndex: Int, modifier: Modifier = Modifier, tabs: @Composable () -> Unit) {
    if (!LocalModern.current) {
        androidx.compose.material3.TabRow(selectedTabIndex = selectedTabIndex, modifier = modifier, tabs = tabs)
    } else {
        val p = modernPalette
        Box(modifier.padding(horizontal = 10.dp, vertical = 6.dp).neuInset(p, 24.dp).padding(4.dp)) {
            androidx.compose.material3.TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = Color.Transparent,
                indicator = {},
                divider = {},
                tabs = tabs,
            )
        }
    }
}

@Composable
fun ScrollableTabRow(selectedTabIndex: Int, modifier: Modifier = Modifier, edgePadding: Dp = 52.dp, tabs: @Composable () -> Unit) {
    if (!LocalModern.current) {
        androidx.compose.material3.ScrollableTabRow(selectedTabIndex = selectedTabIndex, modifier = modifier, edgePadding = edgePadding, tabs = tabs)
    } else {
        val p = modernPalette
        Box(modifier.padding(horizontal = 10.dp, vertical = 6.dp).neuInset(p, 24.dp).padding(4.dp)) {
            androidx.compose.material3.ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = Color.Transparent,
                edgePadding = 0.dp,
                indicator = {},
                divider = {},
                tabs = tabs,
            )
        }
    }
}

@Composable
fun Tab(selected: Boolean, onClick: () -> Unit, text: @Composable () -> Unit) {
    if (!LocalModern.current) {
        androidx.compose.material3.Tab(selected = selected, onClick = onClick, text = text)
    } else {
        val p = modernPalette
        androidx.compose.material3.Tab(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.heightIn(min = 40.dp).then(if (selected) Modifier.neuRaised(p, 20.dp, 3.dp, 6.dp) else Modifier).clip(RoundedCornerShape(20.dp)),
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            text = { ProvideTextStyle(MaterialTheme.typography.titleSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)) { text() } },
        )
    }
}

/** Filter chip. Modern: a raised pill, pressed in (with accent text) when selected. */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    if (!LocalModern.current) {
        androidx.compose.material3.FilterChip(
            selected = selected, onClick = onClick, label = label, modifier = modifier,
            leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        )
    } else {
        val p = modernPalette
        val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            modifier
                .padding(vertical = 4.dp)
                .heightIn(min = 36.dp)
                .then(if (selected) Modifier.neuInset(p, 18.dp) else Modifier.neuRaised(p, 18.dp, 3.dp, 6.dp))
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides color) {
                if (leadingIcon != null) {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { leadingIcon() }
                    Spacer(Modifier.width(6.dp))
                }
                ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)) { label() }
                if (trailingIcon != null) {
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { trailingIcon() }
                }
            }
        }
    }
}

/** Round raised backing for an icon button in Modern (no-op otherwise). */
@Composable
fun Modifier.modernRound(): Modifier =
    if (LocalModern.current) this.padding(4.dp).neuRaised(modernPalette, 24.dp, 3.dp, 7.dp) else this

/** Pressed-in well in Modern (no-op otherwise). */
@Composable
fun Modifier.modernWell(radius: Dp = 14.dp): Modifier =
    if (LocalModern.current) this.neuInset(modernPalette, radius) else this

/**
 * Soft halo in two team colors around a card: [top] glows along the upper edge, [bottom] along
 * the lower edge, blending down the sides. Drawn behind the card, so the card itself stays plain.
 */
fun Modifier.teamGlow(top: Color?, bottom: Color?, radius: Dp, blur: Dp = 10.dp, alpha: Float = 0.95f): Modifier {
    if (top == null && bottom == null) return this
    val a = top ?: bottom!!
    val b = bottom ?: top!!
    return this.drawBehind {
        val r = radius.toPx()
        val grow = 1.dp.toPx()
        drawIntoCanvas { c ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, size.height,
                    intArrayOf(a.copy(alpha = alpha).toArgb(), a.copy(alpha = alpha).toArgb(), b.copy(alpha = alpha).toArgb(), b.copy(alpha = alpha).toArgb()),
                    floatArrayOf(0f, 0.25f, 0.75f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                maskFilter = android.graphics.BlurMaskFilter(blur.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            c.nativeCanvas.drawRoundRect(-grow, -grow, size.width + grow, size.height + grow, r, r, paint)
        }
    }
}
