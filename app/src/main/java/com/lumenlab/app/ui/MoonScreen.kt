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
import com.lumenlab.app.camera.MoonModel
import com.lumenlab.app.camera.NightMerge
import com.lumenlab.app.camera.FocusStack
import com.lumenlab.app.camera.LiveReading
import com.lumenlab.app.camera.SuperRes
import com.lumenlab.app.camera.Thermal
import com.lumenlab.app.camera.previewConfig
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MoonPhase { Ready, Capturing, Merging, Done }

private val COUNT_CHOICES = listOf(8, 12, 16)

private class MoonEv(val label: String, val ev: Float)

private val MOON_EVS = listOf(MoonEv("Darker", -1f), MoonEv("Normal", 0f), MoonEv("Brighter", 1f))

/** Moon: short exposures of the Moon are tracked, the sharpest kept and averaged, then stretched and sharpened. */
@Composable
fun MoonScreen(
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
            MoonContent(limits, settings, onOpenSettings, tabs, Modifier.padding(padding))
        }
    }
}

@Composable
private fun MoonContent(
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
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
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

    var wanted by rememberSaveable { mutableStateOf(12) }
    var phaseIndex by rememberSaveable { mutableStateOf(0) }
    var evIndex by rememberSaveable { mutableStateOf(1) }
    val canMoon = limits.manualAvailable
    var phase by remember { mutableStateOf(MoonPhase.Ready) }
    var taken by remember { mutableStateOf(0) }
    var stopEarly by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressLabel by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var lastLocation by remember { mutableStateOf<String?>(null) }

    val thermal by rememberThermal()
    val level = Thermal.level(thermal, settings.tempPreset)
    val auto = ManualSettings(manual = false, exposureNs = 0L, iso = 0, focusDiopters = 0f)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { resumed = false; ready = false; controller.stop() }
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
            phase = MoonPhase.Capturing
            taken = 0
            stopEarly = false
            var failures = 0
            result = null
            message = null
            val (exposureNs, iso) = MoonModel.exposure(
                MoonModel.PHASES[phaseIndex], MOON_EVS[evIndex].ev,
                limits.minExposureNs, limits.maxExposureNs, limits.isoMin, limits.isoMax,
            )
            val jpegs = ArrayList<ByteArray>()
            try {
                // Focus is set to infinity and everything is fixed, so the Moon looks the same in every photo.
                val s = ManualSettings(manual = true, exposureNs = exposureNs, iso = iso, focusDiopters = 0f, lock = true)
                controller.update(s)
                delay(600)
                while (jpegs.size < wanted) {
                    if (stopEarly && jpegs.size >= 3) break
                    val outcome = controller.capture(s, cfg.sensorOrientation, false)
                    if (outcome != null) {
                        jpegs.add(outcome.jpeg)
                        taken = jpegs.size
                    } else if (++failures > 3) break
                }
            } finally {
                controller.update(auto)
            }
            if (jpegs.size < 3) {
                message = "Not enough photos were taken. Try again."
                phase = MoonPhase.Ready
                return@launch
            }

            phase = MoonPhase.Merging
            progress = 0f
            progressLabel = "Finding the Moon"
            val saved = try {
                withContext(Dispatchers.Default) {
                    // Each photo is cropped around the Moon, so the stored pictures stay small.
                    val crops = ArrayList<IntArray>()
                    val scores = ArrayList<Double>()
                    var side = 0
                    var missed = 0
                    for (i in jpegs.indices) {
                        val bmp = DoubleExposure.decodeUpright(jpegs[i], cfg.sensorOrientation, 4000)
                        if (bmp == null) { missed++; continue }
                        val w = bmp.width
                        val h = bmp.height
                        val px = IntArray(w * h)
                        bmp.getPixels(px, 0, w, 0, 0, w, h)
                        bmp.recycle()
                        val luma = SuperRes.luma(px)
                        val found = MoonModel.find(luma, w, h)
                        if (found == null) { missed++; continue }
                        if (side == 0) side = MoonModel.cropSide(found, w, h)
                        val win = MoonModel.window(found.cx, found.cy, side, w, h)
                        val crop = IntArray(side * side)
                        for (y in 0 until side) System.arraycopy(px, (win.y0 + y) * w + win.x0, crop, y * side, side)
                        // Move the Moon to the middle of the crop, to a fraction of a pixel.
                        val centred = FocusStack.warp(crop, side, side, win.dx, win.dy)
                        scores.add(SuperRes.sharpness(SuperRes.luma(centred), side, side))
                        crops.add(centred)
                        progressLabel = "Finding the Moon · photo ${i + 1} of ${jpegs.size}"
                        progress = 0.7f * (i + 1) / jpegs.size
                    }
                    if (crops.size < 3 || side == 0) return@withContext null
                    val keep = MoonModel.luckyFrames(scores)
                    val acc = NightMerge.Accumulator(side, side, 40f)
                    acc.setReference(crops[keep[0]])
                    for (k in 1 until keep.size) acc.add(crops[keep[k]])
                    progressLabel = "Finishing"
                    progress = 0.9f
                    val merged = acc.result()
                    MoonModel.finish(merged, side, side, 0.8f)
                    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(merged, 0, side, 0, 0, side, side)
                    val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val location = CaptureStore.saveStacked(context, jpeg, "LUMEN_MOON_$stamp.jpg", "Lumenlab/MOON")
                    val preview = Bitmap.createScaledBitmap(bitmap, 720, 720, true)
                    bitmap.recycle()
                    val missedText = if (missed > 0) " · Moon not found in $missed" else ""
                    Triple(location, preview, "best ${keep.size} of ${crops.size} photos$missedText · $side × $side")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CrashLog.record(context, e)
                null
            }
            jpegs.clear()
            if (saved == null) {
                message = "The photos could not be merged. Make sure the Moon is in view and try again."
                phase = MoonPhase.Ready
                return@launch
            }
            lastLocation = saved.first?.location
            result = saved.second
            message = if (saved.first != null) "Saved to the gallery ✓ · ${saved.third}." else "The picture could not be saved."
            phase = MoonPhase.Done
        }
    }

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text("Moon", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when (phase) {
                        MoonPhase.Ready -> if (canMoon) "Aim at the Moon, lean the phone on something and choose its phase. The best photos are tracked, merged and sharpened"
                        else "This camera cannot set exposure by hand, so Moon mode is not available"
                        MoonPhase.Capturing -> "Keep still. Photo ${minOf(taken + 1, wanted)} of $wanted"
                        MoonPhase.Merging -> "Merging the best photos"
                        MoonPhase.Done -> "Done"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onOpenSettings, enabled = phase != MoonPhase.Capturing) {
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
            MoonPhase.Ready, MoonPhase.Capturing -> {
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
                        if (phase == MoonPhase.Capturing) {
                            Column(
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.6f)).padding(12.dp),
                            ) {
                                Text("Photos $taken of $wanted", color = colors.onBackground, style = MaterialTheme.typography.bodyMedium)
                                LinearProgressIndicator(progress = taken.toFloat() / wanted, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            }
                        }
                    }
                }
                if (phase == MoonPhase.Ready) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Photos", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        COUNT_CHOICES.forEach { n ->
                            FilterChip(selected = wanted == n, onClick = { wanted = n }, label = { Text("$n") })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Phase", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        MoonModel.PHASES.forEachIndexed { i, p ->
                            FilterChip(selected = phaseIndex == i, onClick = { phaseIndex = i }, label = { Text(p.label) })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Brightness", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        MOON_EVS.forEachIndexed { i, e ->
                            FilterChip(selected = evIndex == i, onClick = { evIndex = i }, label = { Text(e.label) })
                        }
                    }
                }
            }
            MoonPhase.Merging -> {
                Column(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text("$progressLabel · ${(progress * 100).roundToInt()}%", color = colors.onSurfaceVariant)
                }
            }
            MoonPhase.Done -> {
                val shown = result
                Column(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "Moon result",
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { lastLocation?.let { RecentCapture.open(context, it) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Open") }
                        Button(
                            onClick = { result = null; message = null; phase = MoonPhase.Ready },
                            modifier = Modifier.weight(1f),
                        ) { Text("New photo") }
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
            MoonShutter(
                enabled = phase == MoonPhase.Capturing || (phase != MoonPhase.Merging && ready && canMoon),
                capturing = phase == MoonPhase.Capturing,
            ) {
                when (phase) {
                    MoonPhase.Capturing -> stopEarly = true
                    MoonPhase.Ready, MoonPhase.Done -> start()
                    MoonPhase.Merging -> Unit
                }
            }
        }
    }
}

@Composable
private fun MoonShutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
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
