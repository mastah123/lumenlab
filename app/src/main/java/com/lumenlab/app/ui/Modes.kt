package com.lumenlab.app.ui

import androidx.compose.foundation.background
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The camera's modes, shown as tabs like a phone camera. MORE holds the tools instead of a camera. */
enum class Mode(val label: String) {
    Normal("NORMAL"),
    Astro("ASTRO"),
    Trails("TRAILS"),
    DoubleExp("DOUBLE"),
    Pano("PANO"),
    Super("SUPER"),
    Focus("FOCUS"),
    Hdr("HDR"),
    Night("NIGHT"),
    Moon("MOON"),
    Paint("PAINT"),
    Milky("MILKY"),
    Meteor("METEOR"),
    Flat("FLAT"),
    Lapse("LAPSE"),
    More("MORE"),
}

/** Remembers where the tabs were scrolled to, because each mode has its own screen and its own copy of the tabs. */
private var lastTabScroll = 0

@Composable
fun ModeTabs(mode: Mode, onMode: (Mode) -> Unit, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState(lastTabScroll)
    val offsets = remember { mutableStateMapOf<Mode, Int>() }
    SideEffect { lastTabScroll = scroll.value }
    LaunchedEffect(mode, offsets.size) {
        val x = offsets[mode] ?: return@LaunchedEffect
        scroll.animateScrollTo((x - 120).coerceAtLeast(0))
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mode.values().forEach { m ->
            val selected = m == mode
            Column(
                modifier = Modifier
                    .widthIn(min = 56.dp)
                    .onGloballyPositioned { offsets[m] = it.positionInParent().x.toInt() }
                    .clickable(enabled = enabled) { onMode(m) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    m.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) colors.primary else if (enabled) colors.onSurfaceVariant else colors.outline,
                )
                Box(
                    Modifier.padding(top = 3.dp).width(18.dp).height(2.dp)
                        .background(if (selected) colors.primary else colors.background)
                )
            }
        }
    }
}
