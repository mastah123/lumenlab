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
import com.lumenlab.app.camera.LightPaint
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PaintPhase { Ready, Capturing, Merging, Done }

private class FrameLength(val label: String, val ns: Long)

private val FRAME_LENGTHS = listOf(FrameLength("0.5 s", 500_000_000L), FrameLength("1 s", 1_000_000_000L), FrameLength("2 s", 2_000_000_000L))

private class PaintTime(val label: String, val seconds: Int)

private val PAINT_TIMES = listOf(PaintTime("30 s", 30), PaintTime("1 min", 60), PaintTime("2 min", 120), PaintTime("5 min", 300))

private val PAINT_ISOS = listOf(100, 200, 400, 800)

/** Light painting: photos are taken in a row and the brightest value of each pixel is kept, so moving lights leave trails. */
@Composable
fun PaintScreen(
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
            PaintContent(limits, settings, onOpenSettings, tabs, Modifier.padding(padding))
        }
    }
}

@Composable
private fun PaintContent(
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

    var lengthIndex by rememberSaveable { mutableStateOf(1) }
    var timeIndex by rememberSaveable { mutableStateOf(1) }
    var isoIndex by rememberSaveable { mutableStateOf(1) }
    var wanted by remember { mutableStateOf(1) }
    var liveTrail by remember { mutableStateOf<Bitmap?>(null) }
    val canPaint = limits.manualAvailable
    var phase by remember { mutableStateOf(PaintPhase.Ready) }
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
            phase = PaintPhase.Capturing
            taken = 0
            stopEarly = false
            var failures = 0
            result = null
            message = null
            val exposureNs = FRAME_LENGTHS[lengthIndex].ns.coerceIn(limits.minExposureNs, limits.maxExposureNs)
            val iso = LightPaint.iso(PAINT_ISOS[isoIndex], limits.isoMin, limits.isoMax)
            val seconds = PAINT_TIMES[timeIndex].seconds
            wanted = LightPaint.framesFor(seconds, exposureNs)
            liveTrail = null
            var width = 0
            var height = 0
            var accum: LightPaint.Accumulator? = null
            val queue = Channel<ByteArray>(Channel.UNLIMITED)
            // Each photo is merged in the background while the next one is being taken, so trails have few gaps.
            val worker = launch(Dispatchers.Default) {
                try {
                    for (jpeg in queue) {
                        val bmp = DoubleExposure.decodeUpright(jpeg, cfg.sensorOrientation, 2000) ?: continue
                        if (accum == null) {
                            width = bmp.width
                            height = bmp.height
                            accum = LightPaint.Accumulator(width, height)
                        }
                        if (bmp.width == width && bmp.height == height) {
                            val px = IntArray(width * height)
                            bmp.getPixels(px, 0, width, 0, 0, width, height)
                            val a = accum!!
                            a.add(px)
                            if (a.count % 3 == 1) {
                                val (small, pw, ph) = a.preview(720)
                                liveTrail = Bitmap.createBitmap(small, pw, ph, Bitmap.Config.ARGB_8888)
                            }
                        }
                        bmp.recycle()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    CrashLog.record(context, e)
                }
            }
            val startedAt = SystemClock.elapsedRealtime()
            try {
                // Everything is fixed by hand, so the only change between photos is the light that moves.
                val s = ManualSettings(manual = true, exposureNs = exposureNs, iso = iso, focusDiopters = 0f, autoFocus = true, lock = true)
                controller.update(s)
                delay(600)
                while (SystemClock.elapsedRealtime() - startedAt < seconds * 1000L) {
                    if (stopEarly) break
                    val outcome = controller.capture(s, cfg.sensorOrientation, false)
                    if (outcome != null) {
                        queue.send(outcome.jpeg)
                        taken++
                    } else if (++failures > 3) break
                }
            } finally {
                controller.update(auto)
                queue.close()
            }
            phase = PaintPhase.Merging
            progress = 0f
            progressLabel = "Finishing the trails"
            worker.join()
            val done = accum
            if (taken < 1 || done == null || done.count < 1) {
                message = "No photos were taken. Try again."
                phase = PaintPhase.Ready
                return@launch
            }
            val saved = try {
                withContext(Dispatchers.Default) {
                    progressLabel = "Saving"
                    val merged = done.result()
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(merged, 0, width, 0, 0, width, height)
                    val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it) }.toByteArray()
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val location = CaptureStore.saveStacked(context, jpeg, "LUMEN_PAINT_$stamp.jpg", "Lumenlab/PAINT")
                    val preview = Bitmap.createScaledBitmap(bitmap, 720, (720f * bitmap.height / bitmap.width).roundToInt(), true)
                    bitmap.recycle()
                    Triple(location, preview, "${done.count} photos · $width × $height")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CrashLog.record(context, e)
                null
            }
            liveTrail = null
            if (saved == null) {
                message = "The photos could not be saved. Try again."
                phase = PaintPhase.Ready
                return@launch
            }
            lastLocation = saved.first?.location
            result = saved.second
            message = if (saved.first != null) "Saved to the gallery ✓ · ${saved.third}." else "The picture could not be saved."
            phase = PaintPhase.Done
        }
    }

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text("Light painting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when (phase) {
                        PaintPhase.Ready -> if (canPaint) "Put the phone on a support in the dark. Move a light through the scene while photos are taken; the brightest parts are kept"
                        else "This camera cannot set exposure by hand, so light painting is not available"
                        PaintPhase.Capturing -> "Keep the phone still. Photo ${taken + 1}. Press the shutter to finish"
                        PaintPhase.Merging -> "Finishing the trails"
                        PaintPhase.Done -> "Done"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onOpenSettings, enabled = phase != PaintPhase.Capturing) {
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
            PaintPhase.Ready, PaintPhase.Capturing -> {
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
                        val trail = liveTrail
                        if (phase == PaintPhase.Capturing && trail != null) {
                            Image(
                                bitmap = trail.asImageBitmap(),
                                contentDescription = "Trails so far",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                        if (phase == PaintPhase.Capturing) {
                            Column(
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.6f)).padding(12.dp),
                            ) {
                                Text("Photos $taken of about $wanted", color = colors.onBackground, style = MaterialTheme.typography.bodyMedium)
                                LinearProgressIndicator(progress = (taken.toFloat() / wanted).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            }
                        }
                    }
                }
                if (phase == PaintPhase.Ready) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Time", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        PAINT_TIMES.forEachIndexed { i, it ->
                            FilterChip(selected = timeIndex == i, onClick = { timeIndex = i }, label = { Text(it.label) })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Each photo", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        FRAME_LENGTHS.forEachIndexed { i, it ->
                            FilterChip(selected = lengthIndex == i, onClick = { lengthIndex = i }, label = { Text(it.label) })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ISO", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        PAINT_ISOS.forEachIndexed { i, it ->
                            FilterChip(selected = isoIndex == i, onClick = { isoIndex = i }, label = { Text("${it}") })
                        }
                    }
                }
            }
            PaintPhase.Merging -> {
                Column(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text("$progressLabel · ${(progress * 100).roundToInt()}%", color = colors.onSurfaceVariant)
                }
            }
            PaintPhase.Done -> {
                val shown = result
                Column(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "Light painting result",
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
                            onClick = { result = null; message = null; phase = PaintPhase.Ready },
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
            PaintShutter(
                enabled = phase == PaintPhase.Capturing || (phase != PaintPhase.Merging && ready && canPaint),
                capturing = phase == PaintPhase.Capturing,
            ) {
                when (phase) {
                    PaintPhase.Capturing -> stopEarly = true
                    PaintPhase.Ready, PaintPhase.Done -> start()
                    PaintPhase.Merging -> Unit
                }
            }
        }
    }
}

@Composable
private fun PaintShutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
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
