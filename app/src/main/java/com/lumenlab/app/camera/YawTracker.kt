package com.lumenlab.app.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI

/**
 * Follows how far the phone has turned sideways while held upright, in radians since [start].
 * Used by the panorama to know when to take the next photo and where it goes.
 */
class YawTracker(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var last: Float? = null

    val available: Boolean get() = sensor != null

    /** Total turn since start, adding up small steps so it does not jump when the compass wraps around. */
    @Volatile
    var yaw = 0f
        private set

    fun start() {
        last = null
        yaw = 0f
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // For a phone held upright, the camera points along the screen's back, so the axes are swapped.
        SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val azimuth = orientation[0]
        val previous = last
        if (previous != null) {
            var delta = azimuth - previous
            while (delta > PI) delta -= (2 * PI).toFloat()
            while (delta < -PI) delta += (2 * PI).toFloat()
            yaw += delta
        }
        last = azimuth
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        fun isAvailable(context: Context): Boolean {
            val m = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            return m.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null ||
                m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
        }
    }
}
