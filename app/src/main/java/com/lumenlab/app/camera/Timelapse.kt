package com.lumenlab.app.camera

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Numbers for planning a timelapse and sizing its video. */
object LapsePlan {
    const val MAX_FRAMES = 3600

    /** How many photos a shoot of [durationSeconds] takes with one every [intervalSeconds]. */
    fun frames(durationSeconds: Int, intervalSeconds: Int): Int =
        (durationSeconds / max(intervalSeconds, 1)).coerceIn(1, MAX_FRAMES)

    fun clipSeconds(frames: Int, fps: Int): Double = frames.toDouble() / max(fps, 1)

    /** How many times faster than real life the clip runs. */
    fun speedUp(intervalSeconds: Int, fps: Int): Int = intervalSeconds * fps

    /**
     * The video size: [longSide] on the long side at most, keeping the shape of the picture, and each side a multiple of
     * 16 so every phone's encoder accepts it. Never larger than the photo.
     */
    fun videoSize(srcWidth: Int, srcHeight: Int, longSide: Int): Pair<Int, Int> {
        val scale = min(1.0, longSide.toDouble() / max(srcWidth, srcHeight))
        fun round16(v: Double) = max(16, (v / 16.0).roundToInt() * 16)
        var w = round16(srcWidth * scale)
        var h = round16(srcHeight * scale)
        if (max(w, h) > longSide) { w = max(16, w / 16 * 16); h = max(16, h / 16 * 16) }
        return w to h
    }

    /** Video bit rate in bits per second: enough for fast changing timelapse frames, within what phones accept. */
    fun bitrate(width: Int, height: Int, fps: Int): Int =
        (width.toLong() * height * fps * 0.2).toLong().coerceIn(2_000_000L, 20_000_000L).toInt()

    fun durationText(seconds: Double): String =
        if (seconds >= 60) "%d min %d s".format((seconds / 60).toInt(), (seconds % 60).toInt()) else "%.0f s".format(seconds)
}

/** Evens out brightness jumps between frames while keeping slow changes such as dusk. */
object Deflicker {
    /** The gain for each frame: the average brightness of its neighbours ([window] each side) over its own, kept within 0.7 to 1.4. */
    fun gains(means: List<Double>, window: Int = 4): List<Double> = means.indices.map { i ->
        val from = max(0, i - window)
        val to = min(means.size - 1, i + window)
        var sum = 0.0
        for (j in from..to) sum += means[j]
        val target = sum / (to - from + 1)
        if (means[i] <= 1.0) 1.0 else (target / means[i]).coerceIn(0.7, 1.4)
    }

    fun meanLuma(px: IntArray): Double {
        var sum = 0.0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            sum += ((p shr 16) and 0xFF) * 0.299 + ((p shr 8) and 0xFF) * 0.587 + (p and 0xFF) * 0.114
            i += 7
        }
        return sum / ((px.size + 6) / 7)
    }

    /** Multiplies every pixel's brightness by [gain]. Works in place. */
    fun apply(px: IntArray, gain: Double) {
        if (kotlin.math.abs(gain - 1.0) < 0.005) return
        val lut = IntArray(256) { (it * gain + 0.5).toInt().coerceIn(0, 255) }
        for (i in px.indices) {
            val p = px[i]
            px[i] = (0xFF shl 24) or (lut[(p shr 16) and 0xFF] shl 16) or (lut[(p shr 8) and 0xFF] shl 8) or lut[p and 0xFF]
        }
    }
}

/** Colour conversion for the video encoder, which wants planes of brightness and colour. */
object YuvConvert {
    class Planes(val y: ByteArray, val u: ByteArray, val v: ByteArray, val width: Int, val height: Int)

    /** ARGB pixels to BT.601 studio-range Y, U and V. The colour planes are half the width and height. */
    fun convert(px: IntArray, width: Int, height: Int): Planes {
        val y = ByteArray(width * height)
        val cw = width / 2
        val ch = height / 2
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        for (j in 0 until height) for (i in 0 until width) {
            val p = px[j * width + i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            y[j * width + i] = (16 + (65.481 * r + 128.553 * g + 24.966 * b) / 255.0 + 0.5).toInt().coerceIn(16, 235).toByte()
        }
        for (j in 0 until ch) for (i in 0 until cw) {
            var r = 0
            var g = 0
            var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val p = px[(2 * j + dy) * width + 2 * i + dx]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            val rr = r / 4.0
            val gg = g / 4.0
            val bb = b / 4.0
            u[j * cw + i] = (128 + (-37.797 * rr - 74.203 * gg + 112.0 * bb) / 255.0 + 0.5).toInt().coerceIn(16, 240).toByte()
            v[j * cw + i] = (128 + (112.0 * rr - 93.786 * gg - 18.214 * bb) / 255.0 + 0.5).toInt().coerceIn(16, 240).toByte()
        }
        return Planes(y, u, v, width, height)
    }

    /** Copies a plane of [srcWidth] by [srcHeight] values into an encoder buffer with its own row and pixel steps. */
    fun writePlane(src: ByteArray, srcWidth: Int, srcHeight: Int, dst: ByteBuffer, rowStride: Int, pixelStride: Int) {
        for (row in 0 until srcHeight) {
            val base = row * rowStride
            for (col in 0 until srcWidth) dst.put(base + col * pixelStride, src[row * srcWidth + col])
        }
    }
}
