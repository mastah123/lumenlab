package com.lumenlab.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** What a RAW burst produced: the DNG file bytes, or a reason it failed. */
class RawBurstResult(val dng: ByteArray?, val frames: Int, val width: Int, val height: Int, val message: String?)

/**
 * Takes several RAW frames with fixed settings, averages them and returns one DNG. It opens the camera itself, so the
 * preview must be stopped first. Needs the CAMERA permission.
 */
object RawBurst {
    fun rawSize(context: Context, cameraId: String): Size? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return null
        return chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
    }

    @SuppressLint("MissingPermission")
    suspend fun run(
        context: Context,
        cameraId: String,
        frames: Int,
        exposureNs: Long,
        iso: Int,
        sensorOrientation: Int,
        isStopped: () -> Boolean,
        onFrame: (Int) -> Unit,
    ): RawBurstResult {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return RawBurstResult(null, 0, 0, 0, "Could not read the camera's characteristics.")
        val size = rawSize(context, cameraId)
            ?: return RawBurstResult(null, 0, 0, 0, "This camera lists no RAW output size.")
        val available = Runtime.getRuntime().maxMemory() - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
        if (!RawStack.fits(size.width, size.height, available - 64_000_000L)) {
            return RawBurstResult(null, 0, size.width, size.height, "This camera's RAW frames are too large to stack in the memory this app has.")
        }

        val thread = HandlerThread("raw-burst").also { it.start() }
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        try {
            var waiting: CompletableDeferred<Image>? = null
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
            r.setOnImageAvailableListener({ rd ->
                val img = rd.acquireNextImage()
                val w = waiting
                if (img != null && (w == null || !w.complete(img))) img.close()
            }, handler)
            reader = r
            val dev = ExposureProbe.openCamera(manager, cameraId, handler)
            device = dev
            val sess = try {
                ExposureProbe.createSession(dev, listOf(r.surface), handler)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return RawBurstResult(null, 0, 0, 0, "The camera refused a RAW stream (${e.message ?: "configuration failed"}).")
            }
            session = sess
            val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_FRAME_DURATION, exposureNs)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }.build()

            val stack = RawStack(size.width, size.height)
            var firstResult: TotalCaptureResult? = null
            val timeoutMs = exposureNs / 1_000_000L + 15_000L
            for (n in 0 until frames) {
                if (isStopped()) break
                val imageReady = CompletableDeferred<Image>()
                waiting = imageReady
                val result = withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine<TotalCaptureResult?> { cont ->
                        sess.capture(request, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                                if (cont.isActive) cont.resume(res)
                            }
                            override fun onCaptureFailed(s: CameraCaptureSession, req: CaptureRequest, f: CaptureFailure) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }, handler)
                    }
                } ?: break
                val image = withTimeoutOrNull(8_000L) { imageReady.await() } ?: break
                try {
                    val plane = image.planes.firstOrNull() ?: break
                    val shorts = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                    stack.add(shorts, plane.rowStride / 2)
                } finally {
                    image.close()
                }
                if (firstResult == null) firstResult = result
                onFrame(stack.count)
            }
            val meta = firstResult
            if (stack.count < 1 || meta == null) {
                return RawBurstResult(null, 0, size.width, size.height, "No RAW frames were captured.")
            }
            val values = stack.result()
            val out = ByteArrayOutputStream()
            val dng = DngCreator(chars, meta)
            try {
                dng.setOrientation(RawStack.exifOrientation(sensorOrientation))
                dng.writeByteBuffer(out, Size(size.width, size.height), RawStack.toBytes(values), 0L)
            } finally {
                dng.close()
            }
            return RawBurstResult(out.toByteArray(), stack.count, size.width, size.height, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLog.record(context, e)
            return RawBurstResult(null, 0, size.width, size.height, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            thread.quitSafely()
        }
    }
}
