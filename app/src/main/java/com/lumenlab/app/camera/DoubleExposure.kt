package com.lumenlab.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How two photos are combined into one double exposure. */
enum class Blend(val label: String, val hint: String) {
    Average("Blend", "Both photos mixed evenly. The slider sets how much of the second shows."),
    Lighten("Lighten", "The brighter parts of both photos show. Good for a bright subject on a dark scene."),
    Silhouette("Silhouette", "The second photo shows through the dark parts of the first, like a portrait filled with a forest."),
}

object DoubleExposure {

    /** Degrees to turn a photo so it is upright, from its EXIF data. Photos from this app carry it too. */
    fun rotationOf(bytes: ByteArray): Int {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrNull()
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    /** Decodes a photo, shrinks it so its long side is not much above [maxLongSide], and turns it upright. */
    fun decodeUpright(bytes: ByteArray, rotation: Int, maxLongSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull() ?: return null
        if (rotation == 0) return bitmap
        val turned = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true,
        )
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    /** Crops from the centre and scales a photo to exactly [w] by [h], so two different photos line up. */
    fun fit(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val targetAspect = w.toFloat() / h
        val sourceAspect = src.width.toFloat() / src.height
        val cropW: Int
        val cropH: Int
        if (sourceAspect > targetAspect) {
            cropH = src.height
            cropW = (src.height * targetAspect).roundToInt().coerceIn(1, src.width)
        } else {
            cropW = src.width
            cropH = (src.width / targetAspect).roundToInt().coerceIn(1, src.height)
        }
        val cropped = Bitmap.createBitmap(src, (src.width - cropW) / 2, (src.height - cropH) / 2, cropW, cropH)
        val scaled = Bitmap.createScaledBitmap(cropped, w, h, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        return scaled
    }

    /** Combines two photos of the same size. [strength] runs from 0 to 1, with 0.5 as the even setting. */
    fun blend(a: Bitmap, b: Bitmap, mode: Blend, strength: Float): Bitmap {
        val w = a.width
        val h = a.height
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h)
        b.getPixels(pb, 0, w, 0, 0, w, h)
        val s = strength.coerceIn(0f, 1f)
        for (i in pa.indices) {
            val x = pa[i]
            val y = pb[i]
            val xr = (x shr 16) and 255
            val xg = (x shr 8) and 255
            val xb = x and 255
            val yr = (y shr 16) and 255
            val yg = (y shr 8) and 255
            val yb = y and 255
            var r = 0
            var g = 0
            var bl = 0
            when (mode) {
                Blend.Average -> {
                    r = (xr * (1f - s) + yr * s).toInt()
                    g = (xg * (1f - s) + yg * s).toInt()
                    bl = (xb * (1f - s) + yb * s).toInt()
                }
                Blend.Lighten -> {
                    val k = s * 2f
                    r = max(xr, min(255, (yr * k).toInt()))
                    g = max(xg, min(255, (yg * k).toInt()))
                    bl = max(xb, min(255, (yb * k).toInt()))
                }
                Blend.Silhouette -> {
                    val luma = (77 * xr + 150 * xg + 29 * xb) shr 8
                    val dark = 1f - luma / 255f
                    val m = (dark * dark * s * 2f).coerceIn(0f, 1f)
                    r = (xr * (1f - m) + yr * m).toInt()
                    g = (xg * (1f - m) + yg * m).toInt()
                    bl = (xb * (1f - m) + yb * m).toInt()
                }
            }
            pa[i] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pa, 0, w, 0, 0, w, h)
        return out
    }
}
