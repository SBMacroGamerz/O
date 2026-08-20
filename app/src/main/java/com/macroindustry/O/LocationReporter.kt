package com.macroindustry.O

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * Uses the platform LocationManager directly (no Play Services dependency,
 * keeping this consistent with the rest of the no-PC / minimal-deps setup).
 * Reports fused GPS+network location periodically over the data channel.
 */
class LocationReporter(
    private val context: Context,
    private val onLocation: (String) -> Unit
) {
    companion object {
        private const val TAG = "LocationReporter"
        private const val MIN_INTERVAL_MS = 30_000L
        private const val MIN_DISTANCE_M = 25f
    }

    private var locationManager: LocationManager? = null
    private val listener = LocationListener { location -> reportLocation(location) }

    @SuppressLint("MissingPermission")
    fun start() {
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val lm = locationManager ?: return

        val providers = listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { lm.isProviderEnabled(it) },
            LocationManager.NETWORK_PROVIDER.takeIf { lm.isProviderEnabled(it) }
        )

        if (providers.isEmpty()) {
            Log.w(TAG, "No location providers enabled")
            return
        }

        for (provider in providers) {
            try {
                lm.requestLocationUpdates(
                    provider, MIN_INTERVAL_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper()
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Missing location permission: ${e.message}")
            }
        }

        // Send last known fix immediately rather than waiting for the first update.
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { reportLocation(it) }
    }

    private fun reportLocation(location: Location) {
        val json = JSONObject().apply {
            put("type", "location")
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("accuracy", location.accuracy)
            put("timestamp", location.time)
        }
        onLocation(json.toString())
    }

    fun stop() {
        locationManager?.removeUpdates(listener)
    }
}
