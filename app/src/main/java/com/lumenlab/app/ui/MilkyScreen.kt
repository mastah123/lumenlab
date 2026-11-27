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
import androidx.compose.material3.Slider
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
import com.lumenlab.app.camera.MilkyBlend
import com.lumenlab.app.camera.MilkyWay
import com.lumenlab.app.camera.SkyLocation
import com.lumenlab.app.camera.Stacker
import com.lumenlab.app.camera.StarAlign
import com.lumenlab.app.camera.StarExposure
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

private enum class MilkyPhase { Ready, Capturing, Merging, Editing, Done }

private val COUNT_CHOICES = listOf(8, 12, 20)

private class MilkyExposure(val label: String, val seconds: Int?)

private val MILKY_EXPOSURES = listOf(MilkyExposure("Suggested", null), MilkyExposure("10 s", 10), MilkyExposure("15 s", 15), MilkyExposure("20 s", 20))

private val MILKY_ISOS = listOf(1600, 3200, 6400)

private val TIME_FORMAT get() = SimpleDateFormat("EEE HH:mm", Locale.getDefault())

/** The sky and ground pictures, and small copies for the sliders. */
private class MilkySession(
    val w: Int, val h: Int, val sky: IntArray, val ground: IntArray,
    val pw: Int, val ph: Int, val skyPreview: IntArray, val groundPreview: IntArray,
    val aligned: Boolean, val used: Int,
)

/** Adds photos one at a time: the ground as a plain average, the sky lined up on the stars. */
private class MilkyAccum {
    var w = 0
    var h = 0
    private var skySum: IntArray? = null
    private var groundSum: IntArray? = null
    private var ref: List<StarAlign.Pt>? = null
    private var alignable = true
    private var warped: IntArray? = null
    var count = 0
        private set
    var skyCount = 0
        private set
    var rejected = 0
        private set
    private var maxShiftX = 0f
    private var maxShiftY = 0f
    private var maxTheta = 0f
    val aligned: Boolean get() = ref != null && alignable

    private fun addTo(sum: IntArray, px: IntArray) {
        for (i in px.indices) {
            val p = px[i]
            sum[i * 3] += (p shr 16) and 0xFF
            sum[i * 3 + 1] += (p shr 8) and 0xFF
            sum[i * 3 + 2] += p and 0xFF
        }
    }

    fun add(px: IntArray, width: Int, height: Int) {
        if (skySum == null) {
            w = width
            h = height
            skySum = IntArray(w * h * 3)
            groundSum = IntArray(w * h * 3)
        }
        if (width != w || height != h) { rejected++; return }
        addTo(groundSum!!, px)
        count++
        val stars = Stacker.detectStarsFromLuma(Stacker.halfLuma(px, w, h), w / 2, h / 2)
        val first = ref
        if (first == null) {
            ref = stars
            alignable = stars.size >= 8
            addTo(skySum!!, px)
            skyCount++
        } else if (!alignable) {
            addTo(skySum!!, px)
            skyCount++
        } else {
            val fit = Stacker.align(first, stars, w / 2, h / 2, true)
            if (fit == null) { rejected++; return }
            val target = warped ?: IntArray(w * h).also { warped = it }
            StarAlign.warp(px, target, w, h, fit.theta, fit.tx * 2, fit.ty * 2, w / 2f, h / 2f)
            addTo(skySum!!, target)
            skyCount++
            maxShiftX = maxOf(maxShiftX, kotlin.math.abs(fit.tx * 2))
            maxShiftY = maxOf(maxShiftY, kotlin.math.abs(fit.ty * 2))
            maxTheta = StarAlign.widest(maxTheta, fit.theta)
        }
    }

    /** Both pictures cropped to the area every photo covers. Null when too few photos were added. */
    fun finish(): MilkySession? {
        val sSum = skySum ?: return null
        val gSum = groundSum ?: return null
        if (count < 2 || skyCount < 1) return null
        val mx = if (aligned) kotlin.math.ceil(maxShiftX.toDouble()).toInt() + (if (maxTheta != 0f) StarAlign.marginX(maxTheta, w, h) else 0) else 0
        val my = if (aligned) kotlin.math.ceil(maxShiftY.toDouble()).toInt() + (if (maxTheta != 0f) StarAlign.marginY(maxTheta, w, h) else 0) else 0
        val iw = w - 2 * mx
        val ih = h - 2 * my
        if (iw < 64 || ih < 64) return null
        val sky = IntArray(iw * ih)
        val ground = IntArray(iw * ih)
        for (y in 0 until ih) for (x in 0 until iw) {
            val src = ((y + my) * w + x + mx) * 3
            val o = y * iw + x
            sky[o] = (0xFF shl 24) or (minOf((sSum[src] + skyCount / 2) / skyCount, 255) shl 16) or
                (minOf((sSum[src + 1] + skyCount / 2) / skyCount, 255) shl 8) or minOf((sSum[src + 2] + skyCount / 2) / skyCount, 255)
            ground[o] = (0xFF shl 24) or (minOf((gSum[src] + count / 2) / count, 255) shl 16) or
                (minOf((gSum[src + 1] + count / 2) / count, 255) shl 8) or minOf((gSum[src + 2] + count / 2) / count, 255)
        }
        skySum = null
        groundSum = null
        val k = maxOf(1, iw / 720)
        val (sp, pw, ph) = MilkyBlend.downscale(sky, iw, ih, k)
        val (gp, _, _) = MilkyBlend.downscale(ground, iw, ih, k)
        return MilkySession(iw, ih, sky, ground, pw, ph, sp, gp, aligned, skyCount)
    }
}

/** Milky Way landscape: a sharp ground and a star-aligned sky from one burst, joined along a horizon you can move. */
@Composable
fun MilkyScreen(
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
            MilkyContent(limits, settings, onOpenSettings, tabs, Modifier.padding(padding))
        }
    }
}

@Composable
private fun MilkyContent(
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
    var expIndex by rememberSaveable { mutableStateOf(0) }
    var isoIndex by rememberSaveable { mutableStateOf(1) }
    var session by remember { mutableStateOf<MilkySession?>(null) }
    var horizon by remember { mutableStateOf(0.6f) }
    var feather by remember { mutableStateOf(0.03f) }
    var boost by remember { mutableStateOf(0.5f) }
    val now = remember { System.currentTimeMillis() }
    val place = remember { SkyLocation.load(context) }
    val coreWindow = remember(place) { place?.let { MilkyWay.nextWindow(now, it.lat, it.lon) } }
    val canNight = limits.manualAvailable
    var phase by remember { mutableStateOf(MilkyPhase.Ready) }
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
            phase = MilkyPhase.Capturing
            taken = 0
            stopEarly = false
            var failures = 0
            session = null
            result = null
            message = null
            val suggested = StarExposure.suggest(limits.camera)?.let { StarExposure.clampNs(it.seconds, limits) }
            val exposureNs = (MILKY_EXPOSURES[expIndex].seconds?.let { it * 1_000_000_000L } ?: suggested ?: 10_000_000_000L)
                .coerceIn(limits.minExposureNs, limits.maxExposureNs)
            val iso = LightPaint.iso(MILKY_ISOS[isoIndex], limits.isoMin, limits.isoMax)
            val accum = MilkyAccum()
            val queue = Channel<ByteArray>(Channel.UNLIMITED)
            // Each photo is added in the background while the next is taken, so only one photo is held at a time.
            val worker = launch(Dispatchers.Default) {
                try {
                    for (jpeg in queue) {
                        val bmp = DoubleExposure.decodeUpright(jpeg, cfg.sensorOrientation, 2000) ?: continue
                        val px = IntArray(bmp.width * bmp.height)
                        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                        val bw = bmp.width
                        val bh = bmp.height
                        bmp.recycle()
                        accum.add(px, bw, bh)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    CrashLog.record(context, e)
                }
            }
            try {
                // Focus is at infinity and everything is fixed by hand, so the photos match except for the turning sky.
                val s = ManualSettings(manual = true, exposureNs = exposureNs, iso = iso, focusDiopters = 0f, lock = true)
                controller.update(s)
                delay(600)
                while (taken < wanted) {
                    if (stopEarly && taken >= 3) break
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
            phase = MilkyPhase.Merging
            progress = 0.5f
            progressLabel = "Finishing the sky and the ground"
            worker.join()
            val built = try {
                withContext(Dispatchers.Default) { accum.finish() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CrashLog.record(context, e)
                null
            }
            if (built == null) {
                message = "Not enough photos could be used. Keep the phone on a support and try again."
                phase = MilkyPhase.Ready
                return@launch
            }
            session = built
            horizon = MilkyBlend.guessHorizon(built.groundPreview, built.pw, built.ph)
            message = if (built.aligned) "${built.used} photos. Move the horizon line to where the sky ends."
            else "Not enough stars to line up, so the sky is a plain average. Move the horizon line to where the sky ends."
            phase = MilkyPhase.Editing
        }
    }

    fun save() {
        val s = session ?: return
        scope.launch {
            phase = MilkyPhase.Merging
            progress = 0.5f
            progressLabel = "Saving"
            val saved = try {
                withContext(Dispatchers.Default) {
                    val sky = s.sky.copyOf()
                    MilkyBlend.tone(sky, boost, (horizon * s.h).toInt() * s.w)
                    val merged = MilkyBlend.blend(sky, s.ground, s.w, s.h, horizon, feather)
                    val bitmap = Bitmap.createBitmap(s.w, s.h, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(merged, 0, s.w, 0, 0, s.w, s.h)
                    val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }.toByteArray()
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val location = CaptureStore.saveStacked(context, jpeg, "LUMEN_MILKY_$stamp.jpg", "Lumenlab/MILKYWAY")
                    val preview = Bitmap.createScaledBitmap(bitmap, 720, (720f * bitmap.height / bitmap.width).roundToInt(), true)
                    bitmap.recycle()
                    location to preview
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CrashLog.record(context, e)
                null
            }
            if (saved == null) {
                message = "The picture could not be saved. Try again."
                phase = MilkyPhase.Editing
                return@launch
            }
            lastLocation = saved.first?.location
            result = saved.second
            session = null
            message = if (saved.first != null) "Saved to the gallery ✓ · ${s.w} × ${s.h}." else "The picture could not be saved."
            phase = MilkyPhase.Done
        }
    }

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text("Milky Way", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when (phase) {
                        MilkyPhase.Ready -> if (canNight) "Put the phone on a support facing the Milky Way. Photos are taken at the longest time before stars smear, then the sky and the ground are joined"
                        else "This camera cannot set exposure by hand, so Milky Way mode is not available"
                        MilkyPhase.Capturing -> "Keep still. Photo ${minOf(taken + 1, wanted)} of $wanted"
                        MilkyPhase.Merging -> "Working"
                        MilkyPhase.Editing -> "Set where the sky ends and how strong it looks"
                        MilkyPhase.Done -> "Done"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onOpenSettings, enabled = phase != MilkyPhase.Capturing) {
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
            MilkyPhase.Ready, MilkyPhase.Capturing -> {
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
                        if (phase == MilkyPhase.Capturing) {
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
                if (phase == MilkyPhase.Ready) {
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
                        Text("Exposure", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        MILKY_EXPOSURES.forEachIndexed { i, it ->
                            FilterChip(selected = expIndex == i, onClick = { expIndex = i }, label = { Text(it.label) })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ISO", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        MILKY_ISOS.forEachIndexed { i, it ->
                            FilterChip(selected = isoIndex == i, onClick = { isoIndex = i }, label = { Text("${it}") })
                        }
                    }
                    val win = coreWindow
                    Text(
                        when {
                            place == null -> "Set your position in MORE, Moon and sky, to see when the core of the Milky Way is up."
                            win == null -> "The core of the Milky Way is not up in the dark in the next 36 hours at your place."
                            else -> "Core up and dark: ${TIME_FORMAT.format(Date(win.startMs))} to ${TIME_FORMAT.format(Date(win.endMs))}, highest " +
                                "${MilkyWay.clampAltitude(win.peakAltitude)}° to the ${MilkyWay.compass(win.peakAzimuth)}" +
                                if (win.moonless) "; the Moon is down." else "; the Moon is up for part of it."
                        },
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            MilkyPhase.Merging -> {
                Column(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text("$progressLabel · ${(progress * 100).roundToInt()}%", color = colors.onSurfaceVariant)
                }
            }
            MilkyPhase.Editing -> {
                val sess = session
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (sess != null) {
                        val shown = remember(sess, horizon, feather, boost) {
                            val sky = sess.skyPreview.copyOf()
                            MilkyBlend.tone(sky, boost, (horizon * sess.ph).toInt() * sess.pw)
                            val px = MilkyBlend.blend(sky, sess.groundPreview, sess.pw, sess.ph, horizon, feather)
                            Bitmap.createBitmap(px, sess.pw, sess.ph, Bitmap.Config.ARGB_8888)
                        }
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "Milky Way preview",
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                            contentScale = ContentScale.Fit,
                        )
                        Text("Horizon", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Slider(value = horizon, onValueChange = { horizon = it }, valueRange = 0.2f..0.95f)
                        Text("Soft edge", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Slider(value = feather, onValueChange = { feather = it }, valueRange = 0f..0.1f)
                        Text("Sky boost", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Slider(value = boost, onValueChange = { boost = it }, valueRange = 0f..1f)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { session = null; message = null; phase = MilkyPhase.Ready },
                                modifier = Modifier.weight(1f),
                            ) { Text("Discard") }
                            Button(onClick = { save() }, modifier = Modifier.weight(1f)) { Text("Save") }
                        }
                    }
                }
            }
            MilkyPhase.Done -> {
                val shown = result
                Column(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "Milky Way result",
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
                            onClick = { result = null; message = null; phase = MilkyPhase.Ready },
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
            MilkyShutter(
                enabled = phase == MilkyPhase.Capturing || (phase != MilkyPhase.Merging && phase != MilkyPhase.Editing && ready && canNight),
                capturing = phase == MilkyPhase.Capturing,
            ) {
                when (phase) {
                    MilkyPhase.Capturing -> stopEarly = true
                    MilkyPhase.Ready, MilkyPhase.Done -> start()
                    MilkyPhase.Merging, MilkyPhase.Editing -> Unit
                }
            }
        }
    }
}

@Composable
private fun MilkyShutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
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
