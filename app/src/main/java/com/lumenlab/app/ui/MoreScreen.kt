package com.lumenlab.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumenlab.app.camera.AppSettings
import com.lumenlab.app.camera.CameraInfo
import com.lumenlab.app.camera.CaptureLog
import com.lumenlab.app.camera.DeviceProfile
import com.lumenlab.app.camera.Thermal
import com.lumenlab.app.camera.assessReadiness
import com.lumenlab.app.camera.bestBackLimit
import com.lumenlab.app.camera.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The MORE tab: manual stacking, the camera tester, the phone check and settings. */
@Composable
fun MoreScreen(
    cameras: List<CameraInfo>,
    profile: DeviceProfile,
    settings: AppSettings,
    onOpenGallery: () -> Unit,
    onOpenStack: () -> Unit,
    onOpenTester: () -> Unit,
    onOpenSettings: () -> Unit,
    tabs: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val readiness = remember(cameras) { assessReadiness(cameras) }
    val best = remember(profile) { profile.bestBackLimit() }
    var captures by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        captures = withContext(Dispatchers.IO) { CaptureLog.sessions(context).size }
    }
    val thermal by rememberThermal(5000L)
    val rawStates = profile.cameras.filter { it.facing == "Back" }.mapNotNull { it.rawVerified }
    val rawText = when {
        rawStates.any { it } -> "Passed"
        rawStates.isNotEmpty() -> "Failed"
        else -> "Not tested"
    }
    val cardColors = CardDefaults.cardColors(containerColor = colors.surface)

    Scaffold(containerColor = colors.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("Lumenlab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Version ${profile.appVersion.ifEmpty { "unknown" }}", style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
                        TempChip(thermal, Thermal.level(thermal, settings.tempPreset)) {}
                    }
                }

                Tile(Icons.Filled.PhotoLibrary, "Gallery", "Your photos, stacks, panoramas and burst frames",
                    Modifier.fillMaxWidth(), cardColors, onOpenGallery)
                Tile(
                    Icons.Filled.Layers, "Stacking Studio",
                    if (captures == 0) "Stack saved frames by hand. No captures yet" else
                        "Stack saved frames by hand. $captures capture${if (captures == 1) "" else "s"} ready",
                    Modifier.fillMaxWidth(), cardColors, onOpenStack,
                )
                Tile(Icons.Filled.PhotoCamera, "Camera Tester", "Every camera, its limits and what it can really do",
                    Modifier.fillMaxWidth(), cardColors, onOpenTester)

                Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Phone check", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        StatusRow("Manual control", readiness.title)
                        StatusRow("Exposure", best?.describe() ?: "Not tested")
                        StatusRow("RAW capture", rawText)
                        StatusRow("Live stacking", profile.liveTest?.verdict ?: "Not tested")
                        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Icon(Icons.Filled.Science, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Tests and settings")
                        }
                    }
                }
            }
            tabs()
        }
    }
}

@Composable
private fun Tile(
    icon: ImageVector,
    title: String,
    status: String,
    modifier: Modifier,
    cardColors: CardColors,
    onClick: () -> Unit,
) {
    Card(modifier = modifier.clickable(onClick = onClick), colors = cardColors) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Text(value, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}
