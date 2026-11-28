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
import com.lumenlab.app.camera.DoubleExposure
import com.lumenlab.app.camera.ManualSettings
import com.lumenlab.app.camera.PreviewController
import com.lumenlab.app.camera.RecentCapture
import com.lumenlab.app.camera.LapseEncoder
import com.lumenlab.app.camera.LapsePlan
import com.lumenlab.app.camera.Notifier
import com.lumenlab.app.camera.VideoStore
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

private enum class LapsePhase { Ready, Shooting, Encoding, Done }

private class Choice(val label: String, val value: Int)

private val INTERVALS = listOf(Choice("2 s", 2), Choice("5 s", 5), Choice("10 s", 10), Choice("30 s", 30), Choice("60 s", 60))
private val DURATIONS = listOf(
    Choice("10 min", 600), Choice("30 min", 1800), Choice("1 h", 3600), Choice("2 h", 7200), Choice("4 h", 14400), Choice("Until stopped", 0),
)
private val FPS = listOf(Choice("24", 24), Choice("30", 30))
private val SIZES = listOf(Choice("720p", 1280), Choice("1080p", 1920))

/** Timelapse: a photo every few seconds, joined into a video when the shoot ends. */
@Composable
fun LapseScreen(
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
            LapseContent(limits, settings, onOpenSettings, tabs, Modifier.padding(padding))
        }
    }
}

@Composable
private fun LapseRow(label: String, choices: List<Choice>, selected: Int, onPick: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScrollCompat(),
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
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))

@Composable
private fun LapseContent(
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

    var intervalIndex by rememberSaveable { mutableStateOf(2) }
    var durationIndex by rememberSaveable { mutableStateOf(2) }
    var fpsIndex by rememberSaveable { mutableStateOf(1) }
    var sizeIndex by rememberSaveable { mutableStateOf(1) }
    var fixedExposure by rememberSaveable { mutableStateOf(false) }
    var deflicker by rememberSaveable { mutableStateOf(true) }
    var phase by remember { mutableStateOf(LapsePhase.Ready) }
    var taken by remember { mutableStateOf(0) }
    var wanted by remember { mutableStateOf(0) }
    var stopEarly by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    var lastVideo by remember { mutableStateOf<String?>(null) }
    var lastFrame by remember { mutableStateOf<Bitmap?>(null) }

    val thermal by rememberThermal()
    val level = Thermal.level(thermal, settings.tempPreset)
    val auto = ManualSettings(manual = false, exposureNs = 0L, iso = 0, focusDiopters = 0f)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> if (phase != LapsePhase.Shooting) { resumed = false; ready = false; controller.stop() }
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
            phase = LapsePhase.Shooting
            taken = 0
            stopEarly = false
            message = null
            lastFrame = null
            val interval = INTERVALS[intervalIndex].value
            val seconds = DURATIONS[durationIndex].value
            val fps = FPS[fpsIndex].value
            val longSide = SIZES[sizeIndex].value
            wanted = if (seconds == 0) LapsePlan.MAX_FRAMES else LapsePlan.frames(seconds, interval)
            val dir = File(context.cacheDir, "lapse")
            withContext(Dispatchers.IO) { dir.deleteRecursively(); dir.mkdirs() }
            val files = ArrayList<File>()
            var width = 0
            var height = 0
            var failures = 0
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val reading = live[0]
                val shoot = if (fixedExposure && reading?.exposureNs != null && reading.iso != null && limits.manualAvailable) {
                    ManualSettings(
                        manual = true,
                        exposureNs = reading.exposureNs.coerceIn(limits.minExposureNs, limits.maxExposureNs),
                        iso = reading.iso.coerceIn(limits.isoMin, limits.isoMax),
                        focusDiopters = 0f, autoFocus = true, lock = true,
                    )
                } else auto
                controller.update(shoot)
                delay(400)
                while (files.size < wanted && !stopEarly) {
                    val tick = SystemClock.elapsedRealtime()
                    val outcome = controller.capture(shoot, cfg.sensorOrientation, false)
                    if (outcome != null) {
                        val frame = withContext(Dispatchers.Default) {
                            val bmp = DoubleExposure.decodeUpright(outcome.jpeg, cfg.sensorOrientation, longSide) ?: return@withContext null
                            if (width == 0) {
                                val size = LapsePlan.videoSize(bmp.width, bmp.height, longSide)
                                width = size.first
                                height = size.second
                            }
                            val fitted = if (bmp.width == width && bmp.height == height) bmp else Bitmap.createScaledBitmap(bmp, width, height, true)
                            val file = File(dir, "f%05d.jpg".format(files.size))
                            file.outputStream().use { fitted.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                            if (fitted !== bmp) bmp.recycle()
                            Pair(file, fitted)
                        }
                        if (frame != null) {
                            files.add(frame.first)
                            lastFrame = frame.second
                            taken = files.size
                        }
                    } else if (++failures > 3) break
                    val wait = interval * 1000L - (SystemClock.elapsedRealtime() - tick)
                    var left = wait
                    while (left > 0 && !stopEarly) {
                        delay(minOf(left, 250L))
                        left -= 250L
                    }
                }
            } finally {
                controller.update(auto)
            }
            if (files.size < 2) {
                message = "Not enough photos were taken for a video. Try again."
                phase = LapsePhase.Ready
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
                return@launch
            }
            phase = LapsePhase.Encoding
            progress = 0f
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val out = File(dir, "lapse.mp4")
            val location = try {
                withContext(Dispatchers.Default) {
                    val ok = LapseEncoder.encode(files, out, width, height, fps, deflicker) { progress = it }
                    if (ok) VideoStore.save(context, out, "LUMEN_LAPSE_$stamp.mp4") else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CrashLog.record(context, e)
                null
            }
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
            if (location == null) {
                message = "The video could not be made. Try again, or a lower size."
                phase = LapsePhase.Ready
                return@launch
            }
            lastVideo = location
            message = "Saved to Movies/Lumenlab ✓ · ${files.size} photos · ${LapsePlan.durationText(LapsePlan.clipSeconds(files.size, fps))} at $width × $height."
            Notifier.finished(context, settings.notifyDone, startedAt, "Timelapse finished", "Your video is ready.")
            phase = LapsePhase.Done
        }
    }

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text("Timelapse", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val clip = LapsePlan.clipSeconds(
                    if (DURATIONS[durationIndex].value == 0) taken.coerceAtLeast(1) else LapsePlan.frames(DURATIONS[durationIndex].value, INTERVALS[intervalIndex].value),
                    FPS[fpsIndex].value,
                )
                Text(
                    when (phase) {
                        LapsePhase.Ready ->
                            if (DURATIONS[durationIndex].value == 0) "Put the phone on a support. A video is made when you stop"
                            else "Put the phone on a support. About ${LapsePlan.durationText(clip)} of video, ${LapsePlan.speedUp(INTERVALS[intervalIndex].value, FPS[fpsIndex].value)}× faster than real life"
                        LapsePhase.Shooting -> "Keep the phone still and plugged in if you can. Press the shutter to finish early"
                        LapsePhase.Encoding -> "Making the video"
                        LapsePhase.Done -> "Done"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onOpenSettings, enabled = phase == LapsePhase.Ready || phase == LapsePhase.Done) {
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
            LapsePhase.Ready, LapsePhase.Shooting -> {
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
                        if (phase == LapsePhase.Shooting) {
                            Column(
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.6f)).padding(12.dp),
                            ) {
                                val total = if (DURATIONS[durationIndex].value == 0) "" else " of $wanted"
                                Text("Photos $taken$total", color = colors.onBackground, style = MaterialTheme.typography.bodyMedium)
                                if (DURATIONS[durationIndex].value != 0) {
                                    LinearProgressIndicator(progress = (taken.toFloat() / wanted).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
                if (phase == LapsePhase.Ready) {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        LapseRow("Photo every", INTERVALS, intervalIndex) { intervalIndex = it }
                        LapseRow("Shoot for", DURATIONS, durationIndex) { durationIndex = it }
                        LapseRow("Video fps", FPS, fpsIndex) { fpsIndex = it }
                        LapseRow("Video size", SIZES, sizeIndex) { sizeIndex = it }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Options", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                            FilterChip(
                                selected = fixedExposure && limits.manualAvailable,
                                onClick = { if (limits.manualAvailable) fixedExposure = !fixedExposure },
                                label = { Text("Lock exposure") },
                            )
                            FilterChip(selected = deflicker, onClick = { deflicker = !deflicker }, label = { Text("Smooth flicker") })
                        }
                    }
                }
            }
            LapsePhase.Encoding -> {
                Column(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text("Making the video · ${(progress * 100).roundToInt()}%", color = colors.onSurfaceVariant)
                }
            }
            LapsePhase.Done -> {
                val shown = lastFrame
                Column(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "Last frame of the timelapse",
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { lastVideo?.let { VideoStore.open(context, it) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Play") }
                        Button(
                            onClick = { lastFrame = null; message = null; phase = LapsePhase.Ready },
                            modifier = Modifier.weight(1f),
                        ) { Text("New timelapse") }
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
            LapseShutter(
                enabled = phase == LapsePhase.Shooting || (phase != LapsePhase.Encoding && ready),
                capturing = phase == LapsePhase.Shooting,
            ) {
                when (phase) {
                    LapsePhase.Shooting -> stopEarly = true
                    LapsePhase.Ready, LapsePhase.Done -> start()
                    LapsePhase.Encoding -> Unit
                }
            }
        }
    }
}

@Composable
private fun LapseShutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
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
