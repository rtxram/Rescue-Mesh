package com.example.location

import com.example.domain.model.LocationSnapshot
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class ProximityMetrics(
    val distanceKm: Double,
    val distanceLabel: String,
    val etaMinutes: Int,
    val etaLabel: String,
    val bearingDegrees: Int,
    val compassCardinal: String
)

object GeoUtils {

    private const val EARTH_RADIUS_KM = 6371.0
    private const val WALKING_SPEED_KMH = 4.2

    fun calculateProximity(
        from: LocationSnapshot?,
        to: LocationSnapshot?
    ): ProximityMetrics? {
        if (from == null || to == null) return null
        val distKm = distanceKm(from.latitude, from.longitude, to.latitude, to.longitude)
        val bearing = bearingDegrees(from.latitude, from.longitude, to.latitude, to.longitude)
        val etaMin = ((distKm / WALKING_SPEED_KMH) * 60.0).roundToInt().coerceAtLeast(1)
        val distLabel = if (distKm < 1.0) {
            "%.0f m".format(distKm * 1000.0)
        } else {
            "%.1f km".format(distKm)
        }
        return ProximityMetrics(
            distanceKm = distKm,
            distanceLabel = distLabel,
            etaMinutes = etaMin,
            etaLabel = "ETA ~$etaMin mins",
            bearingDegrees = bearing,
            compassCardinal = cardinalFromDegrees(bearing)
        )
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val deg = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        return deg.roundToInt() % 360
    }

    fun cardinalFromDegrees(deg: Int): String {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((deg % 360) + 22.5) / 45.0).toInt() % 8
        return dirs[idx]
    }

    fun cardinalFromBearing(deg: Double): String = cardinalFromDegrees(deg.roundToInt())

    fun formatDistance(distKm: Double): String = if (distKm < 1.0) {
        "%.0f m".format(distKm * 1000.0)
    } else {
        "%.1f km".format(distKm)
    }

    fun formatCoords(lat: Double, lon: Double): String {
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "W"
        return "%.4f°%s %.4f°%s".format(kotlin.math.abs(lat), ns, kotlin.math.abs(lon), ew)
    }
}
