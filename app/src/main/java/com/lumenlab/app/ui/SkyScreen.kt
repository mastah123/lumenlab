package com.lumenlab.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lumenlab.app.camera.SessionPlan
import com.lumenlab.app.camera.SkyInfo
import com.lumenlab.app.camera.SkyLocation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class SkyEvent(val timeMs: Long, val label: String)

private val DAY_MS = 24 * 3_600_000L

/** The Moon's phase and the night's timings for the person's place, worked out on the phone with no network. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var place by remember { mutableStateOf(SkyLocation.load(context) ?: SkyLocation.lastKnown(context)) }
    var latText by remember { mutableStateOf(place?.lat?.let { "%.4f".format(Locale.US, it) } ?: "") }
    var lonText by remember { mutableStateOf(place?.lon?.let { "%.4f".format(Locale.US, it) } ?: "") }
    var note by remember { mutableStateOf<String?>(null) }

    fun usePhone() {
        val found = SkyLocation.lastKnown(context)
        if (found == null) {
            note = "The phone has no saved position yet. Open a maps app once, or type the position below."
        } else {
            SkyLocation.save(context, found)
            place = found
            latText = "%.4f".format(Locale.US, found.lat)
            lonText = "%.4f".format(Locale.US, found.lon)
            note = null
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) usePhone() else note = "Location was not allowed. Type the position below instead."
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Moon and sky") },
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
            val cardColors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
            val clock = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
            val day = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
            fun at(ms: Long) = clock.format(Date(ms))

            val now = remember { System.currentTimeMillis() }
            val phase = remember(now) { SkyInfo.phase(now) }
            val nextFull = remember(now) { SkyInfo.nextElongation(180.0, now) }
            val nextNew = remember(now) { SkyInfo.nextElongation(0.0, now) }

            Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${phase.emoji}  ${phase.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("${(phase.illumination * 100).toInt()}% lit · ${"%.1f".format(Locale.US, phase.ageDays)} days old")
                    Text("Next full moon ${day.format(Date(nextFull))} · next new moon ${day.format(Date(nextNew))}")
                    Text(SkyInfo.moonAdvice(phase.illumination), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }

            val p = place
            if (p != null) {
                val end = now + DAY_MS
                val events = remember(p, now) {
                    val list = ArrayList<SkyEvent>()
                    fun sun(threshold: Double, rise: String, set: String) {
                        SkyInfo.crossings(now, end, threshold) { SkyInfo.sunAltitude(it, p.lat, p.lon) }
                            .forEach { list += SkyEvent(it.timeMs, if (it.rising) rise else set) }
                    }
                    sun(SkyInfo.SUN_HORIZON, "Sunrise", "Sunset")
                    sun(SkyInfo.ASTRONOMICAL, "Dark ends (astronomical dawn)", "Dark begins (astronomical dusk)")
                    SkyInfo.crossings(now, end, 0.0) { SkyInfo.moonAltitude(it, p.lat, p.lon) - SkyInfo.moonHorizon(it) }
                        .forEach { list += SkyEvent(it.timeMs, if (it.rising) "Moonrise" else "Moonset") }
                    list.sortedBy { it.timeMs }
                }
                val dark = remember(p, now) { SkyInfo.astronomicalDark(now, end, p.lat, p.lon) }
                val moonless = remember(p, now) { SkyInfo.moonlessDark(now, end, p.lat, p.lon) }

                Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Next 24 hours", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (events.isEmpty()) Text("No sunrise, sunset, moonrise or moonset at this place in the next 24 hours.")
                        events.forEach { Text("${at(it.timeMs)}  ·  ${it.label}") }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Dark sky", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (dark.isEmpty()) {
                            Text("The Sun does not go 18° below the horizon in the next 24 hours here, so the sky is never fully dark.")
                        } else {
                            dark.forEach { Text("Dark: ${at(it.startMs)} to ${at(it.endMs)} (${SessionPlan.formatDuration(it.minutes * 60.0)})") }
                            if (moonless.isEmpty()) {
                                Text("The Moon is up for all of it.")
                            } else {
                                moonless.forEach {
                                    Text(
                                        "Dark and moonless: ${at(it.startMs)} to ${at(it.endMs)} (${SessionPlan.formatDuration(it.minutes * 60.0)})",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                        Text(
                            "Times are in this phone's time zone and are good to a few minutes.",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text("Add your position to see moonrise, moonset and the dark hours.", color = colors.onSurfaceVariant)
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your position", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        place?.let { "%.4f, %.4f".format(Locale.US, it.lat, it.lon) + if (it.fromPhone) " · from the phone" else " · typed in" }
                            ?: "Not set",
                    )
                    OutlinedButton(
                        onClick = { if (SkyLocation.hasPermission(context)) usePhone() else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Use the phone's position") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = latText, onValueChange = { latText = it }, label = { Text("Latitude") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = lonText, onValueChange = { lonText = it }, label = { Text("Longitude") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = {
                            val lat = latText.trim().toDoubleOrNull()
                            val lon = lonText.trim().toDoubleOrNull()
                            if (SkyLocation.valid(lat, lon)) {
                                val typed = SkyLocation.Place(lat!!, lon!!, false)
                                SkyLocation.save(context, typed)
                                place = typed
                                note = null
                            } else {
                                note = "Latitude is -90 to 90 and longitude is -180 to 180. North and east are positive."
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save typed position") }
                    note?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "Only the phone's coarse, already saved position is used, and it stays on the phone.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                }
            }
            Text("", modifier = Modifier.padding(bottom = 12.dp))
        }
    }
}
