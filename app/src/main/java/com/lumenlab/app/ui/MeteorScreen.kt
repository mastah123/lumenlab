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
import androidx.compose.runtime.mutableStateListOf
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
import com.lumenlab.app.camera.Notifier
import com.lumenlab.app.camera.Stacker
import com.lumenlab.app.camera.StarAlign
import com.lumenlab.app.camera.StarExposure
import com.lumenlab.app.camera.StreakDetector
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

private enum class MeteorPhase { Ready, Capturing, Merging, Done }

private class WatchLength(val label: String, val seconds: Int)

private val WATCH_LENGTHS = listOf(WatchLength("10 min", 600), WatchLength("30 min", 1800), WatchLength("1 h", 3600), WatchLength("2 h", 7200), WatchLength("Until I stop", 0))

private class WatchExposure(val label: String, val seconds: Int?)

private val WATCH_EXPOSURES = listOf(WatchExposure("Suggested", null), WatchExposure("10 s", 10), WatchExposure("15 s", 15), WatchExposure("20 s", 20))

private val WATCH_ISOS = listOf(1600, 3200, 6400)

private val CLOCK get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/** Joins the photos that caught a streak into one picture: the stars lined up, every streak kept. */
private class CatchComposite {
    private var accum: LightPaint.Accumulator? = null
    private var ref: List<StarAlign.Pt>? = null
    private var alignable = true
    private var warped: IntArray? = null
    var w = 0
        private set
    var h = 0
        private set
    var count = 0
        private set

    fun add(px: IntArray, width: Int, height: Int) {
        if (accum == null) {
            w = width
            h = height
            accum = LightPaint.Accumulator(w, h)
        }
        if (width != w || height != h) return
        val stars = Stacker.detectStarsFromLuma(Stacker.halfLuma(px, w, h), w / 2, h / 2)
        val first = ref
        if (first == null) {
            ref = stars
            alignable = stars.size >= 8
            accum!!.add(px)
        } else if (!alignable) {
            accum!!.add(px)
        } else {
            val fit = Stacker.align(first, stars, w / 2, h / 2, true) ?: return
            val target = warped ?: IntArray(w * h).also { warped = it }
            StarAlign.warp(px, target, w, h, fit.theta, fit.tx * 2, fit.ty * 2, w / 2f, h / 2f)
            accum!!.add(target)
        }
        count++
    }

    fun result(): IntArray? = accum?.result()
}

/** Meteor and satellite watch: photos are taken for a long time; only the ones with a streak in them are kept. */
@Composable
fun MeteorScreen(
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
            MeteorContent(limits, settings, onOpenSettings, tabs, Modifier.padding(padding))
        }
    }
}

@Composable
private fun MeteorContent(
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
    var expIndex by rememberSaveable { mutableStateOf(0) }
    var isoIndex by rememberSaveable { mutableStateOf(1) }
    var caught by remember { mutableStateOf(0) }
    var elapsedSeconds by remember { mutableStateOf(0) }
    var liveTrail by remember { mutableStateOf<Bitmap?>(null) }
    val catchLog = remember { mutableStateListOf<String>() }
    val canMeteor = limits.manualAvailable
    var phase by remember { mutableStateOf(MeteorPhase.Ready) }
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
            phase = MeteorPhase.Capturing
            taken = 0
            stopEarly = false
            var failures = 0
            result = null
            message = null
            val suggested = StarExposure.suggest(limits.camera)?.let { StarExposure.clampNs(it.seconds, limits) }
            val exposureNs = (WATCH_EXPOSURES[expIndex].seconds?.let { it * 1_000_000_000L } ?: suggested ?: 10_000_000_000L)
                .coerceIn(limits.minExposureNs, limits.maxExposureNs)
            val iso = LightPaint.iso(WATCH_ISOS[isoIndex], limits.isoMin, limits.isoMax)
            val seconds = WATCH_LENGTHS[lengthIndex].seconds
            caught = 0
            elapsedSeconds = 0
            liveTrail = null
            catchLog.clear()
            val composite = CatchComposite()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val queue = Channel<ByteArray>(4)
            // Each photo is checked in the background while the next one is taken. Only photos with a streak are saved.
            val worker = launch(Dispatchers.Default) {
                var previous: BooleanArray? = null
                try {
                    for (jpeg in queue) {
                        val small = DoubleExposure.decodeUpright(jpeg, cfg.sensorOrientation, 1000) ?: continue
                        val sw = small.width
                        val sh = small.height
                        val px = IntArray(sw * sh)
                        small.getPixels(px, 0, sw, 0, 0, sw, sh)
                        val mask = StreakDetector.mask(SuperRes.luma(px), sw, sh)
                        val streaks = if (previous != null) StreakDetector.detect(mask, previous, sw, sh) else emptyList()
                        previous = StreakDetector.dilate(mask, sw, sh, 3)
                        if (streaks.isNotEmpty()) {
                            val n = caught + 1
                            val saved = CaptureStore.saveStacked(context, jpeg, "LUMEN_METEOR_${stamp}_$n.jpg", "Lumenlab/METEOR")
                            val longest = streaks.maxByOrNull { it.length }!!
                            catchLog.add(0, "${CLOCK.format(Date())} · streak at ${longest.angleDeg.roundToInt()}°")
                            if (saved != null) {
                                val big = DoubleExposure.decodeUpright(jpeg, cfg.sensorOrientation, 2000)
                                if (big != null) {
                                    val bp = IntArray(big.width * big.height)
                                    big.getPixels(bp, 0, big.width, 0, 0, big.width, big.height)
                                    composite.add(bp, big.width, big.height)
                                    big.recycle()
                                }
                            }
                            liveTrail = small
                            caught = n
                        } else {
                            small.recycle()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    CrashLog.record(context, e)
                }
            }
            val startedAt = SystemClock.elapsedRealtime()
            try {
                // Focus is at infinity and everything is fixed by hand, so the only change between photos is the sky.
                val s = ManualSettings(manual = true, exposureNs = exposureNs, iso = iso, focusDiopters = 0f, lock = true)
                controller.update(s)
                delay(600)
                while (seconds == 0 || SystemClock.elapsedRealtime() - startedAt < seconds * 1000L) {
                    if (stopEarly) break
                    val outcome = controller.capture(s, cfg.sensorOrientation, false)
                    if (outcome != null) {
                        queue.send(outcome.jpeg)
                        taken++
                        elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                    } else if (++failures > 3) break
                }
            } finally {
                controller.update(auto)
                queue.close()
            }
            phase = MeteorPhase.Merging
            progress = 0.5f
            progressLabel = "Finishing"
            worker.join()
            var location: String? = null
            var preview: Bitmap? = null
            if (composite.count >= 2) {
                val merged = composite.result()
                if (merged != null) {
                    withContext(Dispatchers.Default) {
                        val bitmap = Bitmap.createBitmap(composite.w, composite.h, Bitmap.Config.ARGB_8888)
                        bitmap.setPixels(merged, 0, composite.w, 0, 0, composite.w, composite.h)
                        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it) }.toByteArray()
                        location = CaptureStore.saveStacked(context, jpeg, "LUMEN_METEOR_ALL_$stamp.jpg", "Lumenlab/METEOR")?.location
                        preview = Bitmap.createScaledBitmap(bitmap, 720, (720f * bitmap.height / bitmap.width).roundToInt(), true)
                        bitmap.recycle()
                    }
                }
            }
            liveTrail = null
            lastLocation = location
            result = preview
            message = when {
                taken == 0 -> "No photos were taken. Try again."
                caught == 0 -> "No streaks found in $taken photos."
                composite.count >= 2 -> "$caught photos with streaks saved to the gallery ✓, and one picture with all of them."
                else -> "$caught photo with a streak saved to the gallery ✓."
            }
            Notifier.finished(
                context, settings.notifyDone, startedAt, "Meteor watch finished",
                "$caught photo${if (caught == 1) "" else "s"} with streaks in $taken.",
            )
            phase = MeteorPhase.Done
        }
    }

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text("Meteor watch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when (phase) {
                        MeteorPhase.Ready -> if (canMeteor) "Put the phone on a support, plugged in if you can, facing a clear sky. Only photos with a meteor, satellite or aeroplane streak are kept"
                        else "This camera cannot set exposure by hand, so meteor watch is not available"
                        MeteorPhase.Capturing -> "Watching. Keep the phone still. Press the shutter to stop"
                        MeteorPhase.Merging -> "Finishing"
                        MeteorPhase.Done -> "Done"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onOpenSettings, enabled = phase != MeteorPhase.Capturing) {
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
            MeteorPhase.Ready, MeteorPhase.Capturing -> {
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
                        if (phase == MeteorPhase.Capturing && trail != null) {
                            Image(
                                bitmap = trail.asImageBitmap(),
                                contentDescription = "Last streak caught",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                        if (phase == MeteorPhase.Capturing) {
                            Column(
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.6f)).padding(12.dp),
                            ) {
                                Text(
                                    "$taken photos checked · $caught with streaks · ${elapsedSeconds / 60} min",
                                    color = colors.onBackground, style = MaterialTheme.typography.bodyMedium,
                                )
                                val total = WATCH_LENGTHS[lengthIndex].seconds
                                if (total > 0) {
                                    LinearProgressIndicator(progress = (elapsedSeconds.toFloat() / total).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                }
                                catchLog.take(3).forEach { Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                if (phase == MeteorPhase.Ready) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Watch for", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        WATCH_LENGTHS.forEachIndexed { i, it ->
                            FilterChip(selected = lengthIndex == i, onClick = { lengthIndex = i }, label = { Text(it.label) })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Each photo", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        WATCH_EXPOSURES.forEachIndexed { i, it ->
                            FilterChip(selected = expIndex == i, onClick = { expIndex = i }, label = { Text(it.label) })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ISO", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        WATCH_ISOS.forEachIndexed { i, it ->
                            FilterChip(selected = isoIndex == i, onClick = { isoIndex = i }, label = { Text("${it}") })
                        }
                    }
                }
            }
            MeteorPhase.Merging -> {
                Column(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text("$progressLabel · ${(progress * 100).roundToInt()}%", color = colors.onSurfaceVariant)
                }
            }
            MeteorPhase.Done -> {
                val shown = result
                Column(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "Meteor watch result",
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
                            onClick = { result = null; message = null; phase = MeteorPhase.Ready },
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
            MeteorShutter(
                enabled = phase == MeteorPhase.Capturing || (phase != MeteorPhase.Merging && ready && canMeteor),
                capturing = phase == MeteorPhase.Capturing,
            ) {
                when (phase) {
                    MeteorPhase.Capturing -> stopEarly = true
                    MeteorPhase.Ready, MeteorPhase.Done -> start()
                    MeteorPhase.Merging -> Unit
                }
            }
        }
    }
}

@Composable
private fun MeteorShutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
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
