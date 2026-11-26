package com.lumenlab.app.ui

import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumenlab.app.camera.AppSettings
import com.lumenlab.app.camera.ControlLimits
import com.lumenlab.app.camera.SessionPlan
import com.lumenlab.app.camera.StarExposure
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val TARGETS = listOf("30 min" to 1800, "1 h" to 3600, "2 h" to 7200, "3 h" to 10800, "4 h" to 14400)
private val FRAME_SECONDS = listOf(5, 10, 15, 20, 30, 60)

private fun freeMegabytes(): Double? = try {
    StatFs(Environment.getExternalStorageDirectory().path).availableBytes / 1_000_000.0
} catch (e: Exception) {
    null
}

private fun batteryPercent(context: Context): Int? {
    val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
    val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return if (level in 1..100) level else null
}

/** Works out a night's session: frames, time, storage and battery, and sends the frame count to the ASTRO tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    limits: ControlLimits?,
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val suggestion = remember(limits) { limits?.let { StarExposure.suggest(it.camera) } }
    val maxSeconds = limits?.let { it.maxExposureNs / 1e9 } ?: 30.0
    val minSeconds = limits?.let { it.minExposureNs / 1e9 } ?: 0.001
    val suggestedSeconds = suggestion?.seconds?.roundToInt()?.coerceAtLeast(1)
    val choices = remember(limits) {
        (listOfNotNull(suggestedSeconds) + FRAME_SECONDS).filter { it >= minSeconds && it <= maxSeconds }.distinct().sorted()
    }
    var targetIndex by rememberSaveable { mutableStateOf(1) }
    var frameSeconds by rememberSaveable { mutableStateOf(suggestedSeconds ?: 10) }
    val free = remember { freeMegabytes() }
    val battery = remember { batteryPercent(context) }
    val frameMb = remember(limits) {
        limits?.camera?.let { it.sensorWidth.toDouble() * it.sensorHeight / 1_000_000.0 * 0.4 } ?: 4.0
    }
    val plan = SessionPlan.plan(
        SessionPlan.Input(TARGETS[targetIndex].second, frameSeconds.toDouble(), frameMb, free, battery)
    )
    val clock = remember(plan.wallSeconds) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (plan.wallSeconds * 1000).toLong()))
    }
    val usable = plan.frames.coerceIn(1, 1000)

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Session planner") },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("Total light to collect", Icons.Filled.Schedule)
            ChipRow { TARGETS.forEachIndexed { i, t -> FilterChip(selected = targetIndex == i, onClick = { targetIndex = i }, label = { Text(t.first) }) } }
            Text("Length of each frame", fontWeight = FontWeight.SemiBold)
            ChipRow {
                choices.forEach { s ->
                    FilterChip(
                        selected = frameSeconds == s, onClick = { frameSeconds = s },
                        label = { Text(if (s == suggestedSeconds) "$s s ★" else "$s s") },
                    )
                }
            }
            if (suggestion != null) {
                Text(
                    "★ is the longest time before stars smear (${suggestion.method}).",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${plan.frames} frames of $frameSeconds s", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Takes about ${SessionPlan.formatDuration(plan.wallSeconds)}. If you start now it finishes around $clock.")
                    Text("About ${"%.1f".format(Locale.US, plan.noiseGain)}× less noise than one frame.")
                    Text(
                        "Frames need about ${SessionPlan.formatMb(plan.storageMb)}" +
                            (free?.let { " of ${SessionPlan.formatMb(it)} free" } ?: "") + " if all are kept.",
                    )
                    Text(
                        "Battery use is a guess of about ${"%.0f".format(Locale.US, plan.batteryUsedPercent)}%" +
                            (battery?.let { " (now $it%)" } ?: "") + ".",
                    )
                }
            }
            plan.warnings.forEach {
                Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = { onSettings(settings.copy(frameCount = usable)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use $usable frames on the ASTRO tab") }
            Text(
                "This sets the frame count. Set the exposure to $frameSeconds s with the exposure control on the ASTRO tab. " +
                    "Stack often so a crash does not lose the night.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
