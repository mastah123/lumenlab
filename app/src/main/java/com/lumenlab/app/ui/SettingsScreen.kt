package com.lumenlab.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.lumenlab.app.camera.CrashLog
import com.lumenlab.app.camera.DeviceProfile
import com.lumenlab.app.camera.OriginalsPolicy
import com.lumenlab.app.camera.StackMode
import com.lumenlab.app.camera.StackPlan
import com.lumenlab.app.camera.TempPreset
import com.lumenlab.app.camera.Thermal
import com.lumenlab.app.camera.appBuild
import com.lumenlab.app.camera.describe
import com.lumenlab.app.camera.Notifier
import com.lumenlab.app.camera.formatExposure
import com.lumenlab.app.camera.toJson

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    profile: DeviceProfile,
    onResetProfile: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenRaw: () -> Unit,
    onOpenLive: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var confirmReset by remember { mutableStateOf(false) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Without the permission the switch would do nothing, so it stays off.
        onSettings(settings.copy(notifyDone = granted))
    }
    var confirmProtectionOff by remember { mutableStateOf(false) }
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    val thermal by rememberThermal()

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Appearance", Icons.Filled.Palette)
            SettingsCard {
                SwitchRow("Red night-vision mode", "Red-only interface that protects your night vision",
                    settings.redMode) { onSettings(settings.copy(redMode = it)) }
            }

            SectionTitle("Stacking", Icons.Filled.Layers)
            SettingsCard {
                SwitchRow("Night noise reduction (2×2)",
                    "Combines four pixels into one. Halves the picture size and cuts noise about 2×",
                    settings.nightBin) { onSettings(settings.copy(nightBin = it)) }
                SwitchRow("Correct sky rotation",
                    "Stars turn about 15° an hour. Lines up that turn as well as the shift. Slower; helps most on long sessions and when combining batches",
                    settings.rotateAlign) { onSettings(settings.copy(rotateAlign = it)) }
                SwitchRow("Correct vignetting and dust",
                    "Uses the flat frame made on the FLAT tab, if there is one, to brighten the corners and remove dust shadows before stacking",
                    settings.useFlat) { onSettings(settings.copy(useFlat = it)) }
                Text(
                    "How often to stack is set with the STACK slider on the camera screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text("Original frames after stacking", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                OriginalsPolicy.values().forEach { policy ->
                    OptionRow(
                        selected = settings.originals == policy,
                        title = policy.label,
                        detail = policy.detail,
                    ) {
                        onSettings(
                            settings.copy(
                                deleteOriginals = policy == OriginalsPolicy.Delete,
                                askOriginals = policy == OriginalsPolicy.Ask,
                            )
                        )
                    }
                }
            }

            SectionTitle("Notifications", Icons.Filled.Notifications)
            SettingsCard {
                SwitchRow("Tell me when a session finishes",
                    "A notification when a burst or star trails run of over a minute is done and the app is not on screen",
                    settings.notifyDone) { on ->
                    if (on && !Notifier.permitted(context) && Build.VERSION.SDK_INT >= 33) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onSettings(settings.copy(notifyDone = on))
                    }
                }
            }

            SectionTitle("Safety", Icons.Filled.DeviceThermostat)
            SettingsCard {
                SwitchRow("Temperature protection", "Warns, pauses and stops a burst before the phone overheats",
                    settings.tempProtection) { on ->
                    if (on) onSettings(settings.copy(tempProtection = true)) else confirmProtectionOff = true
                }
                if (settings.tempProtection) {
                    TempPreset.values().forEach { preset ->
                        OptionRow(
                            selected = settings.tempPreset == preset,
                            title = preset.label,
                            detail = "Warn ${preset.warm.toInt()}°C · Pause ${preset.hot.toInt()}°C · Stop ${preset.stop.toInt()}°C",
                        ) { onSettings(settings.copy(tempPreset = preset)) }
                    }
                }
            }

            SectionTitle("Device profile", Icons.Filled.PhoneAndroid)
            SettingsCard {
                Text(profile.device, color = colors.onSurfaceVariant)
                profile.cameras.forEach { c ->
                    Text("Camera ${c.cameraId} · ${c.facing}", fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp))
                    Text("Reported exposure: ${c.reportedMaxExposureNs?.let(::formatExposure) ?: "n/a"}")
                    Text("Verified exposure: ${c.verified?.describe() ?: "not tested"}")
                    Text("RAW capture: ${when (c.rawVerified) { true -> "passed"; false -> "failed"; null -> "not tested" }}")
                }
                profile.liveTest?.let { Text("Live stacking test: ${it.verdict}", modifier = Modifier.padding(top = 6.dp)) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { shareReport(context, profile.toJson(), "Lumenlab device profile") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share profile")
                    }
                    OutlinedButton(
                        onClick = { if (confirmReset) { onResetProfile(); confirmReset = false } else confirmReset = true },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (confirmReset) "Tap to confirm" else "Reset profile") }
                }
            }

            SectionTitle("Tests and experiments", Icons.Filled.Science)
            SettingsCard {
                Text(
                    "These tests push the camera hardware to its limits. Results vary by phone and some are slow.",
                    color = colors.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenProbe, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Timer, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Long exposure test")
                }
                OutlinedButton(onClick = onOpenRaw, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("RAW capture test")
                }
                OutlinedButton(onClick = onOpenLive, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Layers, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Live stacking test")
                }
                SwitchRow("Live stacking (experimental)",
                    "Stack each frame as it arrives instead of in batches. Heavy on memory and heat",
                    settings.liveStacking) { onSettings(settings.copy(liveStacking = it)) }
            }

            SectionTitle("Diagnostics", Icons.Filled.BugReport)
            SettingsCard {
                Text(Thermal.summary(thermal), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (crash == null) "No crash recorded" else "A crash was recorded. Share the log so it can be fixed.",
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { crash?.let { shareReport(context, it, "Lumenlab crash log") } },
                        enabled = crash != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share log")
                    }
                    OutlinedButton(
                        onClick = { CrashLog.clear(context); crash = null },
                        enabled = crash != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.DeleteOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear log")
                    }
                }
            }

            Text(
                "Lumenlab ${profile.appVersion.ifEmpty { "unknown" }} (build ${appBuild(context)})",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
            )
        }
    }

    if (confirmProtectionOff) {
        AlertDialog(
            onDismissRequest = { confirmProtectionOff = false },
            title = { Text("Turn off temperature protection?") },
            text = {
                Text("Long bursts and stacking heat the phone. Without protection, a burst will not pause or stop when it gets hot.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmProtectionOff = false
                    onSettings(settings.copy(tempProtection = false))
                }) { Text("Turn off") }
            },
            dismissButton = { TextButton(onClick = { confirmProtectionOff = false }) { Text("Keep on") } },
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}
