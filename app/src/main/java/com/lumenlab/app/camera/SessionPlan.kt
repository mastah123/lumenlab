package com.lumenlab.app.camera

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

/** Plans a night's session: how many frames, how long it takes, and whether storage and battery will last. */
object SessionPlan {
    /** Time the camera needs between frames, in seconds. */
    const val OVERHEAD_SECONDS = 1.0

    /** A rough guess of how much battery the screen, camera and stacking use in an hour. */
    const val BATTERY_PER_HOUR = 20.0

    class Input(
        val targetSeconds: Int,
        val frameSeconds: Double,
        val frameMb: Double,
        val freeMb: Double?,
        val batteryPercent: Int?,
    )

    class Result(
        val frames: Int,
        val wallSeconds: Double,
        val storageMb: Double,
        /** How much less noise than one frame: the square root of the number of frames. */
        val noiseGain: Double,
        val batteryUsedPercent: Double,
        val warnings: List<String>,
    )

    fun frames(targetSeconds: Int, frameSeconds: Double): Int =
        if (frameSeconds <= 0.0) 1 else ceil(targetSeconds / frameSeconds).toInt().coerceAtLeast(1)

    fun plan(i: Input): Result {
        val frames = frames(i.targetSeconds, i.frameSeconds)
        val wall = frames * (i.frameSeconds + OVERHEAD_SECONDS)
        val storage = frames * i.frameMb
        val battery = wall / 3600.0 * BATTERY_PER_HOUR
        val warnings = ArrayList<String>()
        if (i.freeMb != null && storage > i.freeMb * 0.8) {
            warnings += "Frames need about ${formatMb(storage)} but only ${formatMb(i.freeMb)} is free. " +
                "Stack often and delete originals, or choose a shorter session."
        }
        if (i.batteryPercent != null && battery > i.batteryPercent - 15) {
            warnings += "The battery (${i.batteryPercent}%) may not last. Plug the phone in or choose a shorter session."
        }
        if (wall > 3600.0) warnings += "Long sessions heat the phone. Temperature protection may pause the burst."
        if (frames > 1000) warnings += "More than 1000 frames. Run it in parts."
        return Result(frames, wall, storage, sqrt(frames.toDouble()), battery, warnings)
    }

    fun formatMb(mb: Double): String =
        if (mb >= 1000) "%.1f GB".format(Locale.US, mb / 1000) else "%.0f MB".format(Locale.US, mb)

    fun formatDuration(seconds: Double): String {
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h h $m min"
            h > 0 -> "$h h"
            m > 0 -> "$m min"
            else -> "$total s"
        }
    }
}
