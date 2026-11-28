package com.lumenlab.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lumenlab.app.camera.AppSettings
import com.lumenlab.app.camera.CaptureStore
import com.lumenlab.app.camera.ControlLimits
import com.lumenlab.app.camera.CrashLog
import com.lumenlab.app.camera.ManualSettings
import com.lumenlab.app.camera.PreviewController
import com.lumenlab.app.camera.RecentCapture
import com.lumenlab.app.camera.RawBurst
import com.lumenlab.app.camera.LapsePlan
import com.lumenlab.app.camera.RawStack
import com.lumenlab.app.camera.Notifier
import com.lumenlab.app.camera.FocusStack
import com.lumenlab.app.camera.LiveReading
import com.lumenlab.app.camera.SuperRes
import com.lumenlab.app.camera.Thermal
import com.lumenlab.app.camera.previewConfig
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class RawPhase { Ready, Shooting, Done }

private class RawChoice(val label: String, val value: Long)

private val RAW_EXPOSURES = listOf(RawChoice("1 s", 1), RawChoice("2 s", 2), RawChoice("4 s", 4), RawChoice("8 s", 8), RawChoice("15 s", 15))
private val RAW_ISOS = listOf(RawChoice("400", 400), RawChoice("800", 800), RawChoice("1600", 1600), RawChoice("3200", 3200))
private val RAW_FRAMES = listOf(RawChoice("4", 4), RawChoice("8", 8), RawChoice("16", 16), RawChoice("32", 32))

/** RAW stacking: several RAW frames are averaged and saved as one DNG, for editing in Lightroom or similar. */
@Composable
fun RawStackScreen(
    limits: ControlLimits?,
    settings: AppSettings,
    onOpenSettings: () -> Unit,
    tabs: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(containerColor = colors.background) { padding ->
        if (limits == null) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Text("No rear camera was found on this device.", modifier = Modifier.weight(1f).padding(20.dp))
                tabs()
            }
        } else {
            RawStackContent(limits, settings, onOpenSettings, tabs, Modifier.padding(padding))
        }
    }
}

@Composable
private fun RawRow(label: String, choices: List<RawChoice>, selected: Int, onPick: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        choices.forEachIndexed { i, c ->
            FilterChip(selected = selected == i, onClick = { onPick(i) }, label = { Text(c.label) })
        }
    }
}

@Composable
private fun RawStackContent(
    limits: ControlLimits,
    settings: AppSettings,
    onOpenSettings: () -> Unit,
    tabs: @Composable () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val cameraId = limits.camera.id
    val rawSupported = remember(cameraId) { limits.camera.raw && RawBurst.rawSize(context, cameraId) != null }

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permission.launch(Manifest.permission.CAMERA) }

    val config = remember(cameraId) { previewConfig(context, cameraId) }
    val live = remember { arrayOfNulls<LiveReading>(1) }
    val controller = remember { PreviewController(context) { reading -> live[0] = reading } }
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var resumed by remember { mutableStateOf(true) }
    var ready by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }

    var exposureIndex by rememberSaveable { mutableStateOf(2) }
    var isoIndex by rememberSaveable { mutableStateOf(1) }
    var framesIndex by rememberSaveable { mutableStateOf(1) }
    var phase by remember { mutableStateOf(RawPhase.Ready) }
    var taken by remember { mutableStateOf(0) }
    var stopEarly by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var lastLocation by remember { mutableStateOf<String?>(null) }

    val thermal by rememberThermal()
    val level = Thermal.level(thermal, settings.tempPreset)
    val auto = ManualSettings(manual = false, exposureNs = 0L, iso = 0, focusDiopters = 0f)
    val canShoot = rawSupported && limits.manualAvailable

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> if (phase != RawPhase.Shooting) { resumed = false; ready = false; controller.stop() }
                Lifecycle.Event.ON_RESUME -> resumed = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }

    val surfaceTexture = texture
    LaunchedEffect(surfaceTexture, hasPermission, resumed) {
        if (surfaceTexture != null && hasPermission && resumed && config != null) {
            previewError = controller.start(cameraId, surfaceTexture, config.size, config.jpegSize, auto)
            ready = previewError == null && controller.canCapture
        }
    }

    fun start() {
        val cfg = config ?: return
        scope.launch {
            phase = RawPhase.Shooting
            taken = 0
            stopEarly = false
            message = null
            val frames = RAW_FRAMES[framesIndex].value.toInt()
            val exposureNs = (RAW_EXPOSURES[exposureIndex].value * 1_000_000_000L).coerceIn(limits.minExposureNs, limits.maxExposureNs)
            val iso = RAW_ISOS[isoIndex].value.toInt().coerceIn(limits.isoMin, limits.isoMax)
            val startedAt = SystemClock.elapsedRealtime()
            // The RAW burst opens the camera itself, so the preview is closed first and brought back afterwards.
            ready = false
            controller.stop()
            delay(400)
            val result = RawBurst.run(context, cameraId, frames, exposureNs, iso, cfg.sensorOrientation, { stopEarly }) { taken = it }
            val dng = result.dng
            var saved: String? = null
            if (dng != null) {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val s = withContext(Dispatchers.IO) { CaptureStore.saveRawStack(context, dng, "LUMEN_RAWSTACK_$stamp.dng") }
                saved = s?.location
            }
            resumed = false
            delay(300)
            resumed = true
            if (dng == null) {
                message = result.message ?: "The RAW stack could not be made."
                phase = RawPhase.Ready
                return@launch
            }
            if (saved == null) {
                message = "The DNG could not be saved."
                phase = RawPhase.Ready
                return@launch
            }
            lastLocation = saved
            val mb = "%.0f".format(Locale.US, dng.size / 1_048_576.0)
            message = "Saved to Pictures/Lumenlab/RAWSTACK ✓ · ${result.frames} frames · ${result.width} × ${result.height} · $mb MB."
            Notifier.finished(context, settings.notifyDone, startedAt, "RAW stack finished", "Your DNG is ready.")
            phase = RawPhase.Done
        }
    }

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text("RAW stack", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val total = RawStack.shootSeconds(RAW_FRAMES[framesIndex].value.toInt(), RAW_EXPOSURES[exposureIndex].value * 1_000_000_000L)
                Text(
                    when {
                        phase == RawPhase.Shooting -> "Keep the phone still. Frame ${taken + 1} of ${RAW_FRAMES[framesIndex].value}. Press the shutter to finish early"
                        phase == RawPhase.Done -> "Done"
                        !rawSupported -> "This camera does not offer RAW. Run the RAW capture test in More to check"
                        !limits.manualAvailable -> "This camera cannot set exposure by hand, so RAW stacking is not available"
                        else -> "Put the phone on a support. Takes about ${LapsePlan.durationText(total)}. Frames are averaged and saved as one DNG"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onOpenSettings, enabled = phase != RawPhase.Shooting) {
                    Icon(Icons.Filled.Settings, "Settings")
                }
                TempChip(thermal, level) {}
            }
        }

        val note = when {
            !hasPermission -> "Camera permission is needed for the preview."
            previewError != null -> "Preview failed: $previewError"
            else -> message
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = if (previewError != null) colors.error else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        when (phase) {
            RawPhase.Ready, RawPhase.Shooting -> {
                if (config != null) {
                    Box(Modifier.weight(1f, fill = false).aspectRatio(config.aspect)) {
                        AndroidView(
                            factory = { ctx ->
                                TextureView(ctx).apply {
                                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { texture = st }
                                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                            controller.stop()
                                            texture = null
                                            return true
                                        }
                                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (phase == RawPhase.Shooting) {
                            Column(
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.6f)).padding(12.dp),
                            ) {
                                val all = RAW_FRAMES[framesIndex].value.toInt()
                                Text("Frames $taken of $all", color = colors.onBackground, style = MaterialTheme.typography.bodyMedium)
                                LinearProgressIndicator(progress = (taken.toFloat() / all).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                Text("The preview is paused while RAW frames are taken", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (phase == RawPhase.Ready) {
                    RawRow("Each frame", RAW_EXPOSURES, exposureIndex) { exposureIndex = it }
                    RawRow("ISO", RAW_ISOS, isoIndex) { isoIndex = it }
                    RawRow("Frames", RAW_FRAMES, framesIndex) { framesIndex = it }
                }
            }
            RawPhase.Done -> {
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "The DNG keeps the sensor's full data. Open it in an editor that reads RAW, such as Lightroom or Snapseed. " +
                            "It will look dark and flat until you edit it.",
                        color = colors.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { lastLocation?.let { RecentCapture.open(context, it) } }, modifier = Modifier.weight(1f)) { Text("Open") }
                        Button(onClick = { message = null; phase = RawPhase.Ready }, modifier = Modifier.weight(1f)) { Text("New stack") }
                    }
                }
            }
        }

        tabs()

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RawShutter(
                enabled = phase == RawPhase.Shooting || (ready && canShoot),
                capturing = phase == RawPhase.Shooting,
            ) {
                when (phase) {
                    RawPhase.Shooting -> stopEarly = true
                    RawPhase.Ready, RawPhase.Done -> start()
                }
            }
        }
    }
}

@Composable
private fun RawShutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(4.dp, if (enabled) colors.onBackground else colors.outline, CircleShape)
            .padding(7.dp)
            .clip(if (capturing) RoundedCornerShape(14.dp) else CircleShape)
            .background(if (enabled) colors.onBackground else colors.outline)
            .clickable(enabled = enabled, onClick = onClick),
    )
}
