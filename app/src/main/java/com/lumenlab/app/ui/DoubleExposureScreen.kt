package com.lumenlab.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.lumenlab.app.camera.Blend
import com.lumenlab.app.camera.CaptureStore
import com.lumenlab.app.camera.ControlLimits
import com.lumenlab.app.camera.DoubleExposure
import com.lumenlab.app.camera.ManualSettings
import com.lumenlab.app.camera.PreviewController
import com.lumenlab.app.camera.RecentCapture
import com.lumenlab.app.camera.Thermal
import com.lumenlab.app.camera.previewConfig
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { First, Second, Blend }

private val STRENGTH_SNAPS = listOf(Snap(0.25f, "25%"), Snap(0.5f, "50%"), Snap(0.75f, "75%"))

/**
 * Double exposure: take a first photo, see it as a ghost over the live view while lining up a second,
 * then choose how the two blend. The second photo can come from the gallery instead.
 */
@Composable
fun DoubleExposureScreen(
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
            DoubleContent(limits, settings, onOpenSettings, tabs, Modifier.padding(padding))
        }
    }
}

@Composable
private fun DoubleContent(
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

    var step by remember { mutableStateOf(Step.First) }
    var firstBytes by remember { mutableStateOf<ByteArray?>(null) }
    var firstRotation by remember { mutableStateOf(0) }
    var secondBytes by remember { mutableStateOf<ByteArray?>(null) }
    var secondRotation by remember { mutableStateOf(0) }
    var ghost by remember { mutableStateOf<Bitmap?>(null) }
    var ghostAlpha by remember { mutableStateOf(0.45f) }
    var small1 by remember { mutableStateOf<Bitmap?>(null) }
    var small2 by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var blend by remember { mutableStateOf(Blend.Average) }
    var strength by remember { mutableStateOf(0.5f) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
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

    // Small copies of both photos are blended live while you adjust. The full-size blend happens on Save.
    LaunchedEffect(small1, small2, blend, strength) {
        val a = small1
        val b = small2
        if (a != null && b != null) {
            result = withContext(Dispatchers.Default) { DoubleExposure.blend(a, b, blend, strength) }
        }
    }

    suspend fun setFirst(bytes: ByteArray, rotation: Int) {
        firstBytes = bytes
        firstRotation = rotation
        ghost = withContext(Dispatchers.Default) { DoubleExposure.decodeUpright(bytes, rotation, 640) }
        step = Step.Second
    }

    suspend fun prepare() {
        val fb = firstBytes
        val sb = secondBytes
        if (fb == null || sb == null) return
        val pair = withContext(Dispatchers.Default) {
            val a = DoubleExposure.decodeUpright(fb, firstRotation, 720)
            val b = DoubleExposure.decodeUpright(sb, secondRotation, 720)
            if (a == null || b == null) null else a to DoubleExposure.fit(b, a.width, a.height)
        }
        if (pair == null) {
            message = "The photos could not be read."
            return
        }
        small1 = pair.first
        small2 = pair.second
        step = Step.Blend
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                    }
                    if (bytes == null) {
                        message = "That photo could not be opened."
                    } else {
                        val rotation = DoubleExposure.rotationOf(bytes)
                        if (step == Step.First) {
                            setFirst(bytes, rotation)
                        } else if (step == Step.Second) {
                            secondBytes = bytes
                            secondRotation = rotation
                            prepare()
                        }
                    }
                } finally {
                    busy = false
                }
            }
        }
    }

    fun takePhoto() {
        val cfg = config ?: return
        scope.launch {
            busy = true
            message = null
            try {
                val outcome = controller.capture(auto, cfg.sensorOrientation, false)
                if (outcome == null) {
                    message = "Capture failed. Try again."
                } else if (step == Step.First) {
                    setFirst(outcome.jpeg, cfg.sensorOrientation)
                } else {
                    secondBytes = outcome.jpeg
                    secondRotation = cfg.sensorOrientation
                    prepare()
                }
            } finally {
                busy = false
            }
        }
    }

    fun reset() {
        step = Step.First
        firstBytes = null
        secondBytes = null
        ghost = null
        small1 = null
        small2 = null
        result = null
        message = null
    }

    fun save() {
        val fb = firstBytes
        val sb = secondBytes
        if (fb == null || sb == null) return
        scope.launch {
            busy = true
            message = "Saving…"
            try {
                val saved = withContext(Dispatchers.Default) {
                    val a = DoubleExposure.decodeUpright(fb, firstRotation, 6000)
                    val b0 = DoubleExposure.decodeUpright(sb, secondRotation, 6000)
                    if (a == null || b0 == null) {
                        null
                    } else {
                        val b = DoubleExposure.fit(b0, a.width, a.height)
                        val out = DoubleExposure.blend(a, b, blend, strength)
                        val jpeg = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        CaptureStore.saveStacked(context, jpeg, "LUMEN_DOUBLE_$stamp.jpg", "Lumenlab/DOUBLE")
                    }
                }
                if (saved != null) {
                    thumb = result
                    lastLocation = saved.location
                    message = "Saved to the gallery ✓"
                } else {
                    message = "The double exposure could not be saved."
                }
            } finally {
                busy = false
            }
        }
    }

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text("Double exposure", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when (step) {
                        Step.First -> "Step 1 of 2 · take the first photo"
                        Step.Second -> "Step 2 of 2 · line up the second photo with the ghost"
                        Step.Blend -> "Choose how the two photos blend"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onOpenSettings, enabled = !busy) { Text("⚙ Settings") }
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

        if (step == Step.Blend) {
            // Result and blend controls. The camera is released while you adjust.
            val shown = result
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = "Double exposure",
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 12.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("Blending…", color = colors.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Blend.values().forEach { b ->
                    if (blend == b) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(b.label) }
                    else OutlinedButton(onClick = { blend = b }, modifier = Modifier.weight(1f)) { Text(b.label) }
                }
            }
            Text(
                blend.hint,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                Text("Strength ${(strength * 100).toInt()}%", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                SnapSlider(value = strength, onValueChange = { strength = it }, snaps = STRENGTH_SNAPS)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { secondBytes = null; small2 = null; result = null; step = Step.Second },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Retake 2nd") }
                OutlinedButton(onClick = { reset() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Start over") }
                Button(onClick = { save() }, enabled = !busy && result != null, modifier = Modifier.weight(1f)) { Text("Save") }
            }
            tabs()
        } else {
            // Live view, with the first photo as a ghost while lining up the second.
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
                    val g = ghost
                    if (step == Step.Second && g != null) {
                        Image(
                            bitmap = g.asImageBitmap(),
                            contentDescription = "First photo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                            alpha = ghostAlpha,
                        )
                    }
                }
            }
            if (step == Step.Second) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Ghost", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = ghostAlpha,
                        onValueChange = { ghostAlpha = it },
                        valueRange = 0f..0.9f,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
            }
            tabs()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { picker.launch("image/*") },
                    enabled = !busy,
                    modifier = Modifier.width(96.dp),
                ) { Text("Gallery") }
                DoubleShutter(enabled = ready && !busy) { takePhoto() }
                if (step == Step.Second) {
                    OutlinedButton(onClick = { reset() }, enabled = !busy, modifier = Modifier.width(96.dp)) { Text("Start over") }
                } else {
                    DoubleThumb(thumb, Modifier.width(96.dp)) { lastLocation?.let { RecentCapture.open(context, it) } }
                }
            }
        }
    }
}

@Composable
private fun DoubleThumb(bitmap: Bitmap?, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Box(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        Box(
            modifier = Modifier.size(56.dp).clip(shape).border(1.dp, colors.outline, shape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), "Last double exposure", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

@Composable
private fun DoubleShutter(enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(4.dp, if (enabled) colors.onBackground else colors.outline, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(if (enabled) colors.onBackground else colors.outline)
            .clickable(enabled = enabled, onClick = onClick),
    )
}
