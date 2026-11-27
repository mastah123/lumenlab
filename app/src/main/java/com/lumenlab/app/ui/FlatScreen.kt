package com.lumenlab.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.lumenlab.app.camera.FlatField
import com.lumenlab.app.camera.FlatFrames
import com.lumenlab.app.camera.MilkyBlend
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

private enum class FlatPhase { Ready, Capturing, Merging, Done }

/** Averages the flat photos at a quarter of their size, as they arrive. */
private class FlatBuilder {
    var fullWidth = 0
    var fullHeight = 0
    var lowWidth = 0
    var lowHeight = 0
    private var sum: IntArray? = null
    var count = 0
        private set

    fun add(jpeg: ByteArray): Boolean {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) }.getOrNull() ?: return false
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val (low, lw, lh) = MilkyBlend.downscale(px, w, h, FlatField.FACTOR)
        var s = sum
        if (s == null) {
            fullWidth = w
            fullHeight = h
            lowWidth = lw
            lowHeight = lh
            s = IntArray(lw * lh * 3)
            sum = s
        } else if (w != fullWidth || h != fullHeight) return false
        for (i in low.indices) {
            val p = low[i]
            s[i * 3] += (p shr 16) and 0xFF
            s[i * 3 + 1] += (p shr 8) and 0xFF
            s[i * 3 + 2] += p and 0xFF
        }
        count++
        return true
    }

    fun average(): IntArray? {
        val s = sum ?: return null
        if (count == 0) return null
        return IntArray(lowWidth * lowHeight) { i ->
            (0xFF shl 24) or (((s[i * 3] + count / 2) / count) shl 16) or (((s[i * 3 + 1] + count / 2) / count) shl 8) or ((s[i * 3 + 2] + count / 2) / count)
        }
    }
}

/** Flat frame: photos of an evenly lit surface, from which the vignetting and dust shadows of this camera are learned. */
@Composable
fun FlatScreen(
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
            FlatContent(limits, settings, onOpenSettings, tabs, Modifier.padding(padding))
        }
    }
}

@Composable
private fun FlatContent(
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

    var saved by remember { mutableStateOf(FlatFrames.describe(context)) }
    var phase by remember { mutableStateOf(FlatPhase.Ready) }
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
            phase = FlatPhase.Capturing
            taken = 0
            var failures = 0
            result = null
            message = null
            val builder = FlatBuilder()
            try {
                // The camera picks the brightness, then it is held, so the photos match each other.
                controller.update(auto.copy(lock = true))
                delay(900)
                while (builder.count < FlatFrames.FRAMES) {
                    val outcome = controller.capture(auto.copy(lock = true), cfg.sensorOrientation, false)
                    if (outcome != null) {
                        val ok = withContext(Dispatchers.Default) { builder.add(outcome.jpeg) }
                        if (ok) taken = builder.count
                    } else if (++failures > 3) break
                }
            } finally {
                controller.update(auto)
            }
            phase = FlatPhase.Merging
            progress = 0.8f
            progressLabel = "Making the flat frame"
            val low = withContext(Dispatchers.Default) { builder.average() }
            if (low == null || builder.count < 8) {
                message = "Not enough photos were taken. Try again."
                phase = FlatPhase.Ready
                return@launch
            }
            if (!FlatField.exposureOk(low, builder.lowWidth, builder.lowHeight)) {
                message = "The surface was too dark or too bright. Use an evenly lit white surface that the camera shows as mid grey."
                phase = FlatPhase.Ready
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                FlatFrames.save(context, low, builder.lowWidth, builder.lowHeight, builder.fullWidth, builder.fullHeight, cameraId.replace(Regex("[^A-Za-z0-9]"), ""))
            }
            if (!ok) {
                message = "The flat frame could not be saved."
                phase = FlatPhase.Ready
                return@launch
            }
            saved = FlatFrames.describe(context)
            val bmp = Bitmap.createBitmap(low, builder.lowWidth, builder.lowHeight, Bitmap.Config.ARGB_8888)
            result = bmp
            message = "Flat frame saved ✓ (${builder.fullWidth} × ${builder.fullHeight}). It is used automatically when you stack photos from this camera."
            phase = FlatPhase.Done
        }
    }

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text("Flat frame", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when (phase) {
                        FlatPhase.Ready -> "Cover the lens with a white t-shirt or paper facing a bright light, or aim at an evenly lit white screen. Use the same camera and zoom as your night photos"
                        FlatPhase.Capturing -> "Keep still. Photo ${minOf(taken + 1, FlatFrames.FRAMES)} of ${FlatFrames.FRAMES}"
                        FlatPhase.Merging -> "Making the flat frame"
                        FlatPhase.Done -> "Done"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onOpenSettings, enabled = phase != FlatPhase.Capturing) {
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
            FlatPhase.Ready, FlatPhase.Capturing -> {
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
                        if (phase == FlatPhase.Capturing) {
                            Column(
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.6f)).padding(12.dp),
                            ) {
                                Text("Photos $taken of ${FlatFrames.FRAMES}", color = colors.onBackground, style = MaterialTheme.typography.bodyMedium)
                                LinearProgressIndicator(progress = taken.toFloat() / FlatFrames.FRAMES, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            }
                        }
                    }
                }
                if (phase == FlatPhase.Ready) {
                    val have = saved
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (have != null) "A flat frame is saved: ${have.second} × ${have.third}. Taking a new one replaces it."
                            else "No flat frame yet.",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (have != null) {
                            TextButton(onClick = { FlatFrames.delete(context); saved = null }) { Text("Delete") }
                        }
                    }
                }
            }
            FlatPhase.Merging -> {
                Column(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text("$progressLabel · ${(progress * 100).roundToInt()}%", color = colors.onSurfaceVariant)
                }
            }
            FlatPhase.Done -> {
                val shown = result
                Column(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "Flat frame",
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { result = null; message = null; phase = FlatPhase.Ready },
                            modifier = Modifier.weight(1f),
                        ) { Text("New flat frame") }
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
            FlatShutter(
                enabled = phase != FlatPhase.Capturing && phase != FlatPhase.Merging && ready,
                capturing = phase == FlatPhase.Capturing,
            ) {
                when (phase) {
                    FlatPhase.Capturing -> Unit
                    FlatPhase.Ready, FlatPhase.Done -> start()
                    FlatPhase.Merging -> Unit
                }
            }
        }
    }
}

@Composable
private fun FlatShutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
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
