package com.lumenlab.app.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/** Where the person is, for the Moon and sky screen: the phone's last known position, or one typed in. Never sent anywhere. */
object SkyLocation {
    class Place(val lat: Double, val lon: Double, val fromPhone: Boolean)

    private const val PREFS = "sky_location"

    fun valid(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0

    fun load(context: Context): Place? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("lat") || !p.contains("lon")) return null
        val lat = p.getString("lat", null)?.toDoubleOrNull()
        val lon = p.getString("lon", null)?.toDoubleOrNull()
        return if (valid(lat, lon)) Place(lat!!, lon!!, p.getBoolean("phone", false)) else null
    }

    fun save(context: Context, place: Place) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lat", place.lat.toString())
            .putString("lon", place.lon.toString())
            .putBoolean("phone", place.fromPhone)
            .apply()
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The newest position the phone already knows. It does not start the GPS. Null when there is none or no permission. */
    fun lastKnown(context: Context): Place? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
                ?.let { Place(it.latitude, it.longitude, true) }
        } catch (e: SecurityException) {
            null
        }
    }
}
