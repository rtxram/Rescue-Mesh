package com.example.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.domain.model.LocationSnapshot
import com.example.domain.model.MeshConstants
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Offline-first GNSS location provider.
 * Uses Android's LocationManager (GPS_PROVIDER) directly so coordinates remain available
 * without cellular or internet connectivity, plus FusedLocationProviderClient when present.
 * Explicitly flags whether a coordinate fix is fresh or cached.
 */
class OfflineLocationProvider(private val context: Context) {

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    suspend fun acquireSnapshot(timeoutMillis: Long = 4_500L): LocationSnapshot? {
        if (!hasLocationPermission()) return null

        val freshGps = withTimeoutOrNull(timeoutMillis) {
            requestSingleGnssFix()
        }
        if (freshGps != null) {
            return freshGps.toSnapshot(isFresh = true, providerLabel = "GNSS (${freshGps.provider ?: "gps"})")
        }

        val fused = withTimeoutOrNull(2_000L) {
            requestFusedCurrentLocation()
        }
        if (fused != null) {
            val age = System.currentTimeMillis() - fused.time
            return fused.toSnapshot(
                isFresh = age <= MeshConstants.FRESH_LOCATION_THRESHOLD_MILLIS,
                providerLabel = "Fused"
            )
        }

        // Fall back to best last-known fix, clearly marked cached if older than threshold
        val bestCached = getBestLastKnownLocation() ?: return null
        val age = System.currentTimeMillis() - bestCached.time
        return bestCached.toSnapshot(
            isFresh = age <= MeshConstants.FRESH_LOCATION_THRESHOLD_MILLIS,
            providerLabel = "${bestCached.provider ?: "cached"} (cached)"
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleGnssFix(): Location? = suspendCancellableCoroutine { cont ->
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { lm.removeUpdates(this) }
                if (cont.isActive) cont.resume(location)
            }
        }
        runCatching {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        }.onFailure {
            if (cont.isActive) cont.resume(null)
        }

        cont.invokeOnCancellation {
            runCatching { lm.removeUpdates(listener) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFusedCurrentLocation(): Location? = suspendCancellableCoroutine { cont ->
        val cts = CancellationTokenSource()
        runCatching {
            val client = LocationServices.getFusedLocationProviderClient(context)
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }.onFailure {
            if (cont.isActive) cont.resume(null)
        }
        cont.invokeOnCancellation { cts.cancel() }
    }

    @SuppressLint("MissingPermission")
    fun getLastKnownOrFallback(): LocationSnapshot? {
        if (!hasLocationPermission()) return null
        val bestCached = getBestLastKnownLocation() ?: return null
        val age = System.currentTimeMillis() - bestCached.time
        return bestCached.toSnapshot(
            isFresh = age <= MeshConstants.FRESH_LOCATION_THRESHOLD_MILLIS,
            providerLabel = bestCached.provider ?: "cached"
        )
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun Location.toSnapshot(isFresh: Boolean, providerLabel: String): LocationSnapshot =
        LocationSnapshot(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else 50f,
            timestampMillis = if (time > 0) time else System.currentTimeMillis(),
            isFresh = isFresh,
            provider = providerLabel
        )
}
