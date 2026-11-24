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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.lumenlab.app.camera.Panorama
import com.lumenlab.app.camera.PreviewController
import com.lumenlab.app.camera.RecentCapture
import com.lumenlab.app.camera.Thermal
import com.lumenlab.app.camera.YawTracker
import com.lumenlab.app.camera.previewConfig
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PanoPhase { Ready, Capturing, Stitching, Done }

private const val MAX_PANO_FRAMES = 16

/** Panorama: press the shutter, turn slowly, and photos are taken by themselves at the right spacing. */
@Composable
fun PanoramaScreen(
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
            PanoContent(limits, settings, onOpenSettings, tabs, Modifier.padding(padding))
        }
    }
}

@Composable
private fun PanoContent(
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
    val controller = remember { PreviewController(context) { } }
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var resumed by remember { mutableStateOf(true) }
    var ready by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }

    val hFov = remember(limits) { Panorama.horizontalFov(limits.camera) }
    val step = hFov * 0.6f
    val sensorOk = remember { YawTracker.isAvailable(context) }
    val frames = remember { mutableListOf<Panorama.Frame>() }

    var phase by remember { mutableStateOf(PanoPhase.Ready) }
    var frameCount by remember { mutableStateOf(0) }
    var angleDeg by remember { mutableStateOf(0) }
    var nextFraction by remember { mutableStateOf(0f) }
    var finishRequested by remember { mutableStateOf(false) }
    var manualAdd by remember { mutableStateOf(0) }
    var progressLabel by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var lastLocation by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

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
        job = scope.launch {
            phase = PanoPhase.Capturing
            frames.clear()
            frameCount = 0
            angleDeg = 0
            nextFraction = 0f
            finishRequested = false
            manualAdd = 0
            result = null
            message = null
            val tracker = YawTracker(context)
            tracker.start()
            try {
                // Lock exposure and white balance, so every frame matches.
                controller.update(auto.copy(lock = true))
                delay(400)
                val startYaw = tracker.yaw
                var lastYaw = startYaw
                var first = true
                val startedAt = SystemClock.elapsedRealtime()
                while (!finishRequested && frames.size < MAX_PANO_FRAMES && SystemClock.elapsedRealtime() - startedAt < 90_000L) {
                    val yaw = tracker.yaw
                    val moved = abs(yaw - lastYaw)
                    nextFraction = if (first) 1f else (moved / step).coerceIn(0f, 1f)
                    angleDeg = Math.toDegrees(abs(yaw - startYaw).toDouble()).roundToInt()
                    val wanted = first || (sensorOk && moved >= step) || (!sensorOk && manualAdd > 0)
                    if (wanted && ready) {
                        if (!sensorOk && !first) manualAdd--
                        val outcome = controller.capture(auto.copy(lock = true), cfg.sensorOrientation, false)
                        if (outcome != null) {
                            val frameYaw = if (sensorOk) yaw else frames.size * step
                            frames.add(Panorama.Frame(outcome.jpeg, cfg.sensorOrientation, frameYaw))
                            lastYaw = yaw
                            first = false
                            frameCount = frames.size
                        }
                    }
                    delay(40)
                }
            } finally {
                tracker.stop()
                controller.update(auto)
            }

            if (frames.size < 2) {
                message = "Not enough photos. Press the shutter, then turn slowly."
                phase = PanoPhase.Ready
                return@launch
            }
            phase = PanoPhase.Stitching
            progress = 0f
            progressLabel = "Starting"
            val stitched = try {
                Panorama.stitch(frames.toList(), hFov, sensorOk) { label, fraction ->
                    progressLabel = label
                    progress = fraction
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CrashLog.record(context, e)
                null
            }
            val bitmap = stitched?.bitmap
            if (bitmap == null) {
                message = stitched?.message ?: "Stitching failed. Share the error from Settings, Diagnostics."
                phase = PanoPhase.Ready
                return@launch
            }
            progressLabel = "Saving"
            val saved = withContext(Dispatchers.Default) {
                val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                CaptureStore.saveStacked(context, jpeg, "LUMEN_PANO_$stamp.jpg", "Lumenlab/PANORAMA")
            }
            result = withContext(Dispatchers.Default) {
                val height = 480
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * height.toFloat() / bitmap.height).roundToInt(), height, true)
            }
            lastLocation = saved?.location
            message = if (saved != null) "Saved to the gallery ✓" else "The panorama could not be saved."
            phase = PanoPhase.Done
        }
    }

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text("Panorama", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when (phase) {
                        PanoPhase.Ready -> if (sensorOk) "Hold the phone upright, press the shutter, then turn slowly"
                        else "No rotation sensor. Press the shutter, turn a little, then tap Add photo"
                        PanoPhase.Capturing -> "Turn slowly. Photos are taken for you. Press the shutter to finish"
                        PanoPhase.Stitching -> "Joining the photos"
                        PanoPhase.Done -> "Swipe sideways to see the whole panorama"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onOpenSettings, enabled = phase != PanoPhase.Capturing) { Icon(Icons.Filled.Settings, "Settings") }
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
            PanoPhase.Ready, PanoPhase.Capturing -> {
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
                        if (phase == PanoPhase.Capturing) {
                            Column(
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.6f)).padding(12.dp),
                            ) {
                                Text("Photos $frameCount · turned $angleDeg°", color = colors.onBackground,
                                    style = MaterialTheme.typography.bodyMedium)
                                if (sensorOk) {
                                    LinearProgressIndicator(progress = nextFraction, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
            PanoPhase.Stitching -> {
                Column(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text("$progressLabel · ${(progress * 100).roundToInt()}%", color = colors.onSurfaceVariant)
                }
            }
            PanoPhase.Done -> {
                val shown = result
                Column(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (shown != null) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            Image(
                                bitmap = shown.asImageBitmap(),
                                contentDescription = "Panorama",
                                modifier = Modifier.height(260.dp),
                                contentScale = ContentScale.FillHeight,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { lastLocation?.let { RecentCapture.open(context, it) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Open") }
                        Button(
                            onClick = { result = null; message = null; phase = PanoPhase.Ready },
                            modifier = Modifier.weight(1f),
                        ) { Text("New panorama") }
                    }
                }
            }
        }

        tabs()

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(96.dp))
            PanoShutter(
                enabled = phase == PanoPhase.Capturing || (phase != PanoPhase.Stitching && ready),
                capturing = phase == PanoPhase.Capturing,
            ) {
                when (phase) {
                    PanoPhase.Capturing -> finishRequested = true
                    PanoPhase.Ready, PanoPhase.Done -> start()
                    PanoPhase.Stitching -> Unit
                }
            }
            if (phase == PanoPhase.Capturing && !sensorOk) {
                OutlinedButton(onClick = { manualAdd++ }, modifier = Modifier.width(96.dp)) { Text("Add photo") }
            } else {
                Spacer(Modifier.width(96.dp))
            }
        }
    }
}

@Composable
private fun PanoShutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
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
