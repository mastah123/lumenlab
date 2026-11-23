package com.lumenlab.app.camera

import kotlin.math.abs

/** The longest exposure before stars start to smear into short trails, worked out from the lens and sensor. */
object StarExposure {
    class Suggestion(val seconds: Double, val method: String)

    /** Size of one pixel in micrometres, or null when the sensor size is unknown. */
    fun pixelPitchUm(sensorWidthMm: Float?, sensorWidthPx: Int): Double? =
        if (sensorWidthMm == null || sensorWidthMm <= 0f || sensorWidthPx <= 0) null
        else sensorWidthMm * 1000.0 / sensorWidthPx

    /** NPF rule: (35 × aperture + 30 × pixel pitch in µm) ÷ focal length in mm. */
    fun npfSeconds(focalMm: Double, aperture: Double, pitchUm: Double): Double =
        (35.0 * aperture + 30.0 * pitchUm) / focalMm

    /** The classic 500 rule, using the 35 mm equivalent focal length (a full frame sensor is 36 mm wide). */
    fun fiveHundredSeconds(focalMm: Double, sensorWidthMm: Float): Double =
        500.0 / (focalMm * 36.0 / sensorWidthMm)

    fun suggest(camera: CameraInfo): Suggestion? {
        val focal = camera.focalLengthsMm.firstOrNull()?.toDouble()?.takeIf { it > 0.0 } ?: return null
        val aperture = camera.apertures.firstOrNull()?.toDouble()?.takeIf { it > 0.0 }
        val width = camera.physicalWidthMm?.takeIf { it > 0f } ?: return null
        val pitch = pixelPitchUm(width, camera.sensorWidth)
        return if (aperture != null && pitch != null) Suggestion(npfSeconds(focal, aperture, pitch), "NPF rule")
        else Suggestion(fiveHundredSeconds(focal, width), "500 rule")
    }

    /** A time in seconds as nanoseconds, kept inside what the phone can do. */
    fun clampNs(seconds: Double, limits: ControlLimits): Long =
        (seconds * 1e9).toLong().coerceIn(limits.minExposureNs, limits.maxExposureNs)
}

/**
 * How sharp a small grayscale picture is, for focusing on stars or distant lights.
 * It is the average of the strongest 2% of edge responses in the middle of the picture, so noise
 * in dark areas counts for little and a small bright point that gets tighter raises the number.
 */
fun focusSharpness(luma: IntArray, width: Int, height: Int): Double {
    if (width < 5 || height < 5 || luma.size < width * height) return 0.0
    val x0 = width / 4
    val x1 = width - width / 4
    val y0 = height / 4
    val y1 = height - height / 4
    val responses = ArrayList<Int>((x1 - x0) * (y1 - y0))
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            val c = luma[y * width + x]
            val r = 4 * c - luma[y * width + x - 1] - luma[y * width + x + 1] -
                luma[(y - 1) * width + x] - luma[(y + 1) * width + x]
            responses += abs(r)
        }
    }
    if (responses.isEmpty()) return 0.0
    responses.sortDescending()
    val top = maxOf(1, responses.size / 50)
    return responses.take(top).average()
}
