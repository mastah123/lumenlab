package com.lumenlab.app.ui

import androidx.compose.foundation.background
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
    More("MORE"),
}

@Composable
fun ModeTabs(mode: Mode, onMode: (Mode) -> Unit, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mode.values().forEach { m ->
            val selected = m == mode
            Column(
                modifier = Modifier.clickable(enabled = enabled) { onMode(m) }.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    m.label,
                    style = MaterialTheme.typography.labelLarge,
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
