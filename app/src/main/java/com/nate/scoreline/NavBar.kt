package com.nate.scoreline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** A bottom-bar tab: either a Material icon or one of the app's own single-color drawables. The list index is its id. */
data class NavTab(val label: String, val icon: ImageVector? = null, val drawable: Int = 0)

val navTabs = listOf(
    NavTab("Scores", Icons.Filled.Home),
    NavTab("F1", drawable = R.drawable.ic_nav_f1),
    NavTab("Golf", drawable = R.drawable.ic_nav_golf),
    NavTab("Standings", Icons.AutoMirrored.Filled.List),
    NavTab("Settings", Icons.Filled.Settings),
    NavTab("NHL", drawable = R.drawable.ic_nav_nhl),
)

const val TAB_SCORES = 0
const val TAB_GOLF = 2
const val TAB_SETTINGS = 4
const val TAB_NHL = 5

/**
 * Bottom navigation that looks like Material's bar (icon in a pill when selected, label below),
 * plus press-and-hold then drag sideways to reorder. The order is reported when the finger lifts.
 */
@Composable
fun ReorderableNavBar(order: List<Int>, selected: Int, onSelect: (Int) -> Unit, onReorder: (List<Int>) -> Unit) {
    var working by remember(order) { mutableStateOf(order) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowWidth by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentOnSelect by rememberUpdatedState(onSelect)

    val extra = LocalExtraColors.current
    val barColor = extra.bar ?: NavigationBarDefaults.containerColor
    val cs = MaterialTheme.colorScheme
    val unselected = extra.onBar?.copy(alpha = 0.75f) ?: cs.onSurfaceVariant
    val selectedText = extra.onBar ?: cs.onSurface
    val pill = cs.secondaryContainer
    val onPill = cs.onSecondaryContainer

    fun endDrag() {
        dragging = null
        dragOffset = 0f
        currentOnReorder(working)
    }

    val modern = LocalModern.current
    val mp = modernPalette
    // Modern: a floating raised glass bar with the selected item pressed in.
    Surface(color = if (modern) Color.Transparent else barColor) {
        Row(
            (if (modern) {
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp, top = 4.dp).height(74.dp).neuRaised(mp, 26.dp)
            } else {
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(80.dp)
            }).onSizeChanged { rowWidth = it.width },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            working.forEach { id ->
                key(id) {
                    val t = navTabs[id]
                    val isSel = selected == id
                    val isDragging = dragging == id
                    Column(
                        Modifier
                            .weight(1f)
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationX = if (isDragging) dragOffset else 0f
                                val sc = if (isDragging) 1.1f else 1f
                                scaleX = sc
                                scaleY = sc
                            }
                            .pointerInput(id) {
                                detectTapGestures(onTap = { currentOnSelect(id) })
                            }
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragging = id
                                        dragOffset = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.x
                                        val cell = if (working.isEmpty()) 0f else rowWidth.toFloat() / working.size
                                        val idx = working.indexOf(id)
                                        if (cell > 0f && idx >= 0) {
                                            if (dragOffset > cell / 2 && idx < working.lastIndex) {
                                                working = working.toMutableList().apply { add(idx + 1, removeAt(idx)) }
                                                dragOffset -= cell
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            } else if (dragOffset < -cell / 2 && idx > 0) {
                                                working = working.toMutableList().apply { add(idx - 1, removeAt(idx)) }
                                                dragOffset += cell
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    },
                                    onDragEnd = { endDrag() },
                                    onDragCancel = { endDrag() },
                                )
                            }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.width(60.dp).height(32.dp)
                                .then(
                                    if (modern) { if (isSel || isDragging) Modifier.neuInset(mp, 16.dp) else Modifier }
                                    else Modifier.background(if (isSel || isDragging) pill else Color.Transparent, RoundedCornerShape(16.dp)),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val tint = if (isSel || isDragging) { if (modern) cs.primary else onPill } else unselected
                            if (t.icon != null) Icon(t.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                            else Icon(painterResource(t.drawable), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                        }
                        Text(
                            t.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSel) { if (modern) cs.primary else selectedText } else unselected,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
