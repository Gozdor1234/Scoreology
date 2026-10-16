@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A hidden tab (one not on the bottom bar) opened full screen, with a back arrow. */
@Composable
fun SectionScreen(tabId: Int, onBack: () -> Unit, open: (Route) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = scorelineTopBarColors(),
                title = { Text(navTabs.getOrNull(tabId)?.label ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad -> TabContent(tabId, Modifier.padding(pad), open) }
}
