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
import com.lumenlab.app.camera.Bracket
import com.lumenlab.app.camera.FocusStack
import com.lumenlab.app.camera.HdrFuser
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

private enum class HdrPhase { Ready, Capturing, Merging, Done }

private val COUNT_CHOICES = listOf(3, 5)

private class HdrStep(val label: String, val ev: Float)

private val HDR_STEPS = listOf(HdrStep("Mild", 1f), HdrStep("Normal", 1.5f), HdrStep("Strong", 2f))

/** HDR: photos at different exposures are taken and blended, so both shadows and highlights keep detail. */
@Composable
fun HdrScreen(
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
            HdrContent(limits, settings, onOpenSettings, tabs, Modifier.padding(padding))
        }
    }
}

@Composable
private fun HdrContent(
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

    var wanted by rememberSaveable { mutableStateOf(3) }
    var stepIndex by rememberSaveable { mutableStateOf(1) }
    val canHdr = limits.manualAvailable
    var phase by remember { mutableStateOf(HdrPhase.Ready) }
    var taken by remember { mutableStateOf(0) }
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
            phase = HdrPhase.Capturing
            taken = 0
            result = null
            message = null
            val reading = live[0]
            val baseNs = (reading?.exposureNs ?: 16_000_000L).coerceIn(limits.minExposureNs, limits.maxExposureNs)
            val baseIso = (reading?.iso ?: 400).coerceIn(limits.isoMin, limits.isoMax)
            val plan = Bracket.plan(
                baseNs, baseIso, Bracket.evSteps(wanted, HDR_STEPS[stepIndex].ev),
                limits.minExposureNs, limits.maxExposureNs, limits.isoMin, limits.isoMax,
            )
            val jpegs = ArrayList<ByteArray>()
            try {
                for (shot in plan) {
                    // Everything is fixed by hand except the brightness, so the photos differ only in exposure.
                    val s = ManualSettings(manual = true, exposureNs = shot.exposureNs, iso = shot.iso, focusDiopters = 0f, autoFocus = true, lock = true)
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
            if (jpegs.size < wanted) {
                message = "Not every photo was taken. Keep the phone still and try again."
                phase = HdrPhase.Ready
                return@launch
            }

            phase = HdrPhase.Merging
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
                    val refLuma = HdrFuser.normalise(SuperRes.luma(refPx))
                    val fuser = HdrFuser(w, h)
                    // Pass 1: find how each photo moved and how good each pixel is. Only the JPEG bytes stay in memory.
                    val shifts = arrayOfNulls<SuperRes.Shift>(jpegs.size)
                    val indices = IntArray(jpegs.size) { -1 }
                    for (i in jpegs.indices) {
                        var px: IntArray? = null
                        var shift = SuperRes.Shift(0f, 0f, 0f)
                        if (i == refIndex) {
                            px = refPx
                        } else {
                            val bmp = DoubleExposure.decodeUpright(jpegs[i], cfg.sensorOrientation, 2000)
                            if (bmp != null && bmp.width == w && bmp.height == h) {
                                val p = IntArray(w * h)
                                bmp.getPixels(p, 0, w, 0, 0, w, h)
                                shift = SuperRes.estimateShift(refLuma, HdrFuser.normalise(SuperRes.luma(p)), w, h)
                                // The photos differ in brightness, so only the movement is checked.
                                if (SuperRes.usable(shift, w, maxError = 60f)) {
                                    px = FocusStack.warp(p, w, h, shift.dx, shift.dy)
                                }
                            }
                            bmp?.recycle()
                        }
                        if (px != null) {
                            shifts[i] = shift
                            indices[i] = fuser.addWeights(px)
                        }
                        progressLabel = "Judging photo ${i + 1} of ${jpegs.size}"
                        progress = 0.4f * (i + 1) / jpegs.size
                    }
                    val used = indices.count { it >= 0 }
                    if (used < 2) return@withContext null
                    // Pass 2: blend.
                    for (i in jpegs.indices) {
                        if (indices[i] < 0) continue
                        val px: IntArray = if (i == refIndex) refPx else {
                            val bmp = DoubleExposure.decodeUpright(jpegs[i], cfg.sensorOrientation, 2000) ?: continue
                            val p = IntArray(w * h)
                            bmp.getPixels(p, 0, w, 0, 0, w, h)
                            bmp.recycle()
                            val sh = shifts[i]!!
                            FocusStack.warp(p, w, h, sh.dx, sh.dy)
                        }
                        fuser.accumulate(indices[i], px)
                        progressLabel = "Blending photo ${i + 1} of ${jpegs.size}"
                        progress = 0.4f + 0.55f * (i + 1) / jpegs.size
                    }
                    val merged = fuser.result()
                    progressLabel = "Saving"
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(merged, 0, w, 0, 0, w, h)
                    val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it) }.toByteArray()
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val location = CaptureStore.saveStacked(context, jpeg, "LUMEN_HDR_$stamp.jpg", "Lumenlab/HDR")
                    val preview = Bitmap.createScaledBitmap(bitmap, 720, (720f * bitmap.height / bitmap.width).roundToInt(), true)
                    bitmap.recycle()
                    val skippedText = if (used < jpegs.size) " · ${jpegs.size - used} skipped" else ""
                    Triple(location, preview, "$used photos blended$skippedText · $w × $h")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CrashLog.record(context, e)
                null
            }
            jpegs.clear()
            if (saved == null) {
                message = "The photos could not be blended. Put the phone on a support and try again."
                phase = HdrPhase.Ready
                return@launch
            }
            lastLocation = saved.first?.location
            result = saved.second
            message = if (saved.first != null) "Saved to the gallery ✓ · ${saved.third}." else "The picture could not be saved."
            phase = HdrPhase.Done
        }
    }

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text("HDR", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when (phase) {
                        HdrPhase.Ready -> if (canHdr) "Put the phone on a support. Photos are taken darker and brighter, then blended so shadows and highlights keep detail"
                        else "This camera cannot set exposure by hand, so HDR is not available"
                        HdrPhase.Capturing -> "Keep still. Photo ${minOf(taken + 1, wanted)} of $wanted"
                        HdrPhase.Merging -> "Blending the photos"
                        HdrPhase.Done -> "Done"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onOpenSettings, enabled = phase != HdrPhase.Capturing) {
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
            HdrPhase.Ready, HdrPhase.Capturing -> {
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
                        if (phase == HdrPhase.Capturing) {
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
                if (phase == HdrPhase.Ready) {
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
                        Text("Strength", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        HDR_STEPS.forEachIndexed { i, s ->
                            FilterChip(selected = stepIndex == i, onClick = { stepIndex = i }, label = { Text(s.label) })
                        }
                    }
                }
            }
            HdrPhase.Merging -> {
                Column(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text("$progressLabel · ${(progress * 100).roundToInt()}%", color = colors.onSurfaceVariant)
                }
            }
            HdrPhase.Done -> {
                val shown = result
                Column(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "HDR result",
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
                            onClick = { result = null; message = null; phase = HdrPhase.Ready },
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
            HdrShutter(
                enabled = phase != HdrPhase.Capturing && phase != HdrPhase.Merging && ready && canHdr,
                capturing = phase == HdrPhase.Capturing,
            ) {
                when (phase) {
                    HdrPhase.Capturing -> Unit
                    HdrPhase.Ready, HdrPhase.Done -> start()
                    HdrPhase.Merging -> Unit
                }
            }
        }
    }
}

@Composable
private fun HdrShutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
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
