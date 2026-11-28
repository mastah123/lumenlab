package com.lumenlab.app.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Averages RAW sensor frames (16-bit Bayer values) without touching the colour pattern, so the result can be written
 * back as a DNG. With four or more frames the lowest and highest value of every pixel are dropped first, which
 * removes satellites, planes, cosmic-ray hits and one-off hot pixels.
 */
class RawStack(val width: Int, val height: Int) {
    private val sum = IntArray(width * height)
    private val low = ShortArray(width * height) { -1 }  // 0xFFFF
    private val high = ShortArray(width * height)
    var count = 0
        private set

    /** Adds one frame. [rowStride] is the number of 16-bit values from one row to the next (at least the width). */
    fun add(frame: ShortBuffer, rowStride: Int = width) {
        for (y in 0 until height) {
            val rowIn = y * rowStride
            val rowOut = y * width
            for (x in 0 until width) {
                val s = frame.get(rowIn + x)
                val v = s.toInt() and 0xFFFF
                val i = rowOut + x
                sum[i] += v
                if (v < (low[i].toInt() and 0xFFFF)) low[i] = s
                if (v > (high[i].toInt() and 0xFFFF)) high[i] = s
            }
        }
        count++
    }

    /** The averaged frame as 16-bit values, row after row. */
    fun result(): ShortArray {
        val out = ShortArray(width * height)
        if (count == 0) return out
        val trim = count >= 4
        val n = if (trim) count - 2 else count
        for (i in out.indices) {
            var total = sum[i].toLong()
            if (trim) total -= (low[i].toInt() and 0xFFFF) + (high[i].toInt() and 0xFFFF)
            out[i] = ((total + n / 2) / n).coerceIn(0L, 65535L).toInt().toShort()
        }
        return out
    }

    companion object {
        /** Memory the stack needs: 4 bytes of sum, 2 of lowest and 2 of highest per pixel, plus the result. */
        fun bytesNeeded(width: Int, height: Int): Long = width.toLong() * height * 10

        fun fits(width: Int, height: Int, availableBytes: Long): Boolean = bytesNeeded(width, height) <= availableBytes

        /** Little-endian bytes, tightly packed, the layout the DNG writer takes. */
        fun toBytes(values: ShortArray): ByteBuffer {
            val buf = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buf.asShortBuffer().put(values)
            return buf
        }

        /** The EXIF orientation value for a sensor that has to be turned by [rotation] degrees to look upright. */
        fun exifOrientation(rotation: Int): Int = when (((rotation % 360) + 360) % 360) {
            90 -> 6
            180 -> 3
            270 -> 8
            else -> 1
        }

        /** Seconds the shoot takes, counting a small gap between frames. */
        fun shootSeconds(frames: Int, exposureNs: Long): Double = frames * (exposureNs / 1e9 + 0.5)
    }
}
