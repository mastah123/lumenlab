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

private enum class FocusPhase { Ready, Capturing, Merging, Done }

private val FRAME_CHOICES = listOf(8, 12, 16)

private class FocusRange(val label: String, val endFraction: Float)

private val FOCUS_RANGES = listOf(FocusRange("Close", 0.25f), FocusRange("Wide", 0.05f), FocusRange("Full", 0f))

private fun distanceText(diopters: Float): String = when {
    diopters <= 0.01f -> "infinity"
    100f / diopters < 100f -> "%.0f cm".format(Locale.US, 100f / diopters)
    else -> "%.1f m".format(Locale.US, 1f / diopters)
}

/** Super resolution: hold the phone, press the shutter, and a burst of photos is merged into one finer picture. */
@Composable
fun FocusStackScreen(
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
            FocusContent(limits, settings, onOpenSettings, tabs, Modifier.padding(padding))
        }
    }
}

@Composable
private fun FocusContent(
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
    var rangeIndex by rememberSaveable { mutableStateOf(0) }
    val canFocus = limits.manualFocus && limits.minFocusDiopters > 0f
    var phase by remember { mutableStateOf(FocusPhase.Ready) }
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
            phase = FocusPhase.Capturing
            taken = 0
            stopEarly = false
            result = null
            message = null
            val reading = live[0]
            val exposureNs = (reading?.exposureNs ?: 16_000_000L).coerceIn(limits.minExposureNs, limits.maxExposureNs)
            val iso = (reading?.iso ?: 400).coerceIn(limits.isoMin, limits.isoMax)
            val steps = FocusStack.focusSteps(limits.minFocusDiopters, FOCUS_RANGES[rangeIndex].endFraction, wanted)
            val jpegs = ArrayList<ByteArray>()
            try {
                for (d in steps) {
                    if (stopEarly && jpegs.size >= 3) break
                    // Exposure and focus are set by hand, so every photo matches except where it is focused.
                    val s = ManualSettings(manual = true, exposureNs = exposureNs, iso = iso, focusDiopters = d, lock = true)
                    controller.update(s)
                    delay(450)
                    val outcome = controller.capture(s, cfg.sensorOrientation, false)
                    if (outcome != null) {
                        jpegs.add(outcome.jpeg)
                        taken = jpegs.size
                    }
                }
            } finally {
                controller.update(auto)
            }
            if (jpegs.size < 3) {
                message = "Not enough photos were taken. Keep the phone still and try again."
                phase = FocusPhase.Ready
                return@launch
            }

            phase = FocusPhase.Merging
            progress = 0f
            progressLabel = "Lining up photos"
            val saved = try {
                withContext(Dispatchers.Default) {
                    val refIndex = jpegs.size / 2
                    val refBmp = DoubleExposure.decodeUpright(jpegs[refIndex], cfg.sensorOrientation, 2000)
                        ?: return@withContext null
                    val w = refBmp.width
                    val h = refBmp.height
                    val refPx = IntArray(w * h)
                    refBmp.getPixels(refPx, 0, w, 0, 0, w, h)
                    refBmp.recycle()
                    val refLuma = SuperRes.luma(refPx)
                    val acc = FocusStack.Accumulator(w, h)
                    var skipped = 0
                    for (i in jpegs.indices) {
                        var aligned: IntArray? = null
                        if (i == refIndex) {
                            aligned = refPx
                        } else {
                            val bmp = DoubleExposure.decodeUpright(jpegs[i], cfg.sensorOrientation, 2000)
                            if (bmp != null && bmp.width == w && bmp.height == h) {
                                val px = IntArray(w * h)
                                bmp.getPixels(px, 0, w, 0, 0, w, h)
                                val shift = SuperRes.estimateShift(refLuma, SuperRes.luma(px), w, h)
                                // Photos focused at other distances differ a lot, so only the movement is checked.
                                if (SuperRes.usable(shift, w, maxError = 60f)) {
                                    aligned = FocusStack.warp(px, w, h, shift.dx, shift.dy)
                                }
                            }
                            bmp?.recycle()
                        }
                        if (aligned != null) acc.add(aligned) else skipped++
                        progressLabel = "Merging photo ${i + 1} of ${jpegs.size}"
                        progress = 0.9f * (i + 1) / jpegs.size
                    }
                    if (acc.count < 2) return@withContext null
                    val merged = acc.result()
                    progressLabel = "Saving"
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(merged, 0, w, 0, 0, w, h)
                    val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it) }.toByteArray()
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val location = CaptureStore.saveStacked(context, jpeg, "LUMEN_FOCUS_$stamp.jpg", "Lumenlab/FOCUS")
                    val preview = Bitmap.createScaledBitmap(bitmap, 720, (720f * bitmap.height / bitmap.width).roundToInt(), true)
                    bitmap.recycle()
                    val skippedText = if (skipped > 0) " · $skipped skipped" else ""
                    Triple(location, preview, "${acc.count} photos merged$skippedText · $w × $h")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CrashLog.record(context, e)
                null
            }
            jpegs.clear()
            if (saved == null) {
                message = "The photos could not be merged. Put the phone on a support and try again."
                phase = FocusPhase.Ready
                return@launch
            }
            lastLocation = saved.first?.location
            result = saved.second
            val slow = if (exposureNs > 66_000_000L) " Low light made the exposure long, which can blur." else ""
            message = if (saved.first != null) "Saved to the gallery ✓ · ${saved.third}.$slow" else "The picture could not be saved."
            phase = FocusPhase.Done
        }
    }

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text("Focus stacking", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when (phase) {
                        FocusPhase.Ready -> if (canFocus) "Put the phone on a support and aim at something close. The focus moves from near to far while photos are taken"
                        else "This camera cannot move focus by hand, so focus stacking is not available"
                        FocusPhase.Capturing -> "Keep still. Photo ${taken + 1} of $wanted"
                        FocusPhase.Merging -> "Joining the sharp parts"
                        FocusPhase.Done -> "Done"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onOpenSettings, enabled = phase != FocusPhase.Capturing) {
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
            FocusPhase.Ready, FocusPhase.Capturing -> {
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
                        if (phase == FocusPhase.Capturing) {
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
                if (phase == FocusPhase.Ready) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Photos", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        FRAME_CHOICES.forEach { n ->
                            FilterChip(selected = wanted == n, onClick = { wanted = n }, label = { Text("$n") })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Range", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        FOCUS_RANGES.forEachIndexed { i, r ->
                            FilterChip(selected = rangeIndex == i, onClick = { rangeIndex = i }, label = { Text(r.label) })
                        }
                    }
                    if (canFocus) {
                        Text(
                            "Focus from ${distanceText(limits.minFocusDiopters)} to " +
                                distanceText(limits.minFocusDiopters * FOCUS_RANGES[rangeIndex].endFraction),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            FocusPhase.Merging -> {
                Column(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text("$progressLabel · ${(progress * 100).roundToInt()}%", color = colors.onSurfaceVariant)
                }
            }
            FocusPhase.Done -> {
                val shown = result
                Column(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "Focus stacking result",
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
                            onClick = { result = null; message = null; phase = FocusPhase.Ready },
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
            FocusShutter(
                enabled = phase == FocusPhase.Capturing || (phase != FocusPhase.Merging && ready && canFocus),
                capturing = phase == FocusPhase.Capturing,
            ) {
                when (phase) {
                    FocusPhase.Capturing -> stopEarly = true
                    FocusPhase.Ready, FocusPhase.Done -> start()
                    FocusPhase.Merging -> Unit
                }
            }
        }
    }
}

@Composable
private fun FocusShutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
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
