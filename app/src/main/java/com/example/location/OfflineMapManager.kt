package com.example.location

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.domain.model.LocationSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

enum class MapTileStyle(val label: String, val lyrsCode: String, val folderName: String) {
    GMAPS_ROADMAP("Google Roadmap", "m", "gmaps_m"),
    GMAPS_HYBRID("Google Hybrid", "y", "gmaps_y")
}

data class OfflineMapCacheStatus(
    val isDownloading: Boolean = false,
    val downloadedTiles: Int = 0,
    val totalTiles: Int = 0,
    val cachedTileCount: Int = 0,
    val cachedSizeMb: Float = 0f,
    val lastCachedCenterLat: Double? = null,
    val lastCachedCenterLon: Double? = null,
    val style: MapTileStyle = MapTileStyle.GMAPS_ROADMAP,
    val message: String = "No local Google Map area cached yet"
)

data class TileCoord(val zoom: Int, val x: Int, val y: Int)

/**
 * Downloads and caches local Google Maps raster tiles (Roadmap & Hybrid, z=13..16, with automatic
 * OpenStreetMap fallback if a tile server throttles) into app-private storage so the recipient-side
 * interactive Google Maps Viewer works seamlessly offline during disasters.
 */
class OfflineMapManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gmapsRoot = File(context.filesDir, "local_gmaps").apply { mkdirs() }
    private val legacyOsmRoot = File(context.filesDir, "osm_tiles").apply { mkdirs() }
    private val metaPrefs = context.getSharedPreferences("rescuemesh_gmaps_meta", Context.MODE_PRIVATE)
    private var downloadJob: Job? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow(loadInitialStatus())
    val status: StateFlow<OfflineMapCacheStatus> = _status.asStateFlow()

    private fun loadInitialStatus(): OfflineMapCacheStatus {
        val (count, bytes) = measureCache()
        val hasLat = metaPrefs.contains("center_lat")
        val lat = if (hasLat) metaPrefs.getFloat("center_lat", 0f).toDouble() else null
        val lon = if (hasLat) metaPrefs.getFloat("center_lon", 0f).toDouble() else null
        val style = runCatching {
            MapTileStyle.valueOf(metaPrefs.getString("style", MapTileStyle.GMAPS_ROADMAP.name) ?: "")
        }.getOrDefault(MapTileStyle.GMAPS_ROADMAP)
        val mb = bytes / (1024f * 1024f)
        return OfflineMapCacheStatus(
            cachedTileCount = count,
            cachedSizeMb = mb,
            lastCachedCenterLat = lat,
            lastCachedCenterLon = lon,
            style = style,
            message = if (count > 0) "Local ${style.label}: $count tiles (%.2f MB)".format(mb)
            else "Pre-cache local Google Maps while online for offline SOS navigation"
        )
    }

    fun fallbackDefaultLocation(): LocationSnapshot {
        val cur = _status.value
        val lat = cur.lastCachedCenterLat ?: 21.2514
        val lon = cur.lastCachedCenterLon ?: 81.6296
        return LocationSnapshot(
            latitude = lat,
            longitude = lon,
            accuracyMeters = 50f,
            timestampMillis = System.currentTimeMillis(),
            isFresh = false,
            provider = "CACHED_AREA"
        )
    }

    fun startDownloadRegion(
        centerLat: Double,
        centerLon: Double,
        radiusKm: Double = 3.5,
        style: MapTileStyle = MapTileStyle.GMAPS_ROADMAP
    ) {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            val coords = buildTileListForRegion(
                centerLat = centerLat,
                centerLon = centerLon,
                radiusKm = radiusKm,
                zooms = listOf(13, 14, 15, 16)
            )
            _status.value = _status.value.copy(
                isDownloading = true,
                downloadedTiles = 0,
                totalTiles = coords.size,
                style = style,
                message = "Downloading ${coords.size} ${style.label} tiles..."
            )
            var done = 0
            var failures = 0
            for (tile in coords) {
                val target = gmapTileFile(style, tile.zoom, tile.x, tile.y)
                if (!target.exists() || target.length() == 0L) {
                    val ok = fetchAndSaveTile(tile, style, target)
                    if (!ok) failures++
                }
                done++
                if (done % 6 == 0 || done == coords.size) {
                    val (count, bytes) = measureCache()
                    _status.value = _status.value.copy(
                        isDownloading = true,
                        downloadedTiles = done,
                        totalTiles = coords.size,
                        cachedTileCount = count,
                        cachedSizeMb = bytes / (1024f * 1024f),
                        style = style,
                        message = "Cached $done / ${coords.size} local G-Map tiles..."
                    )
                }
            }
            metaPrefs.edit()
                .putFloat("center_lat", centerLat.toFloat())
                .putFloat("center_lon", centerLon.toFloat())
                .putString("style", style.name)
                .apply()

            val (count, bytes) = measureCache()
            val mb = bytes / (1024f * 1024f)
            _status.value = OfflineMapCacheStatus(
                isDownloading = false,
                downloadedTiles = done,
                totalTiles = coords.size,
                cachedTileCount = count,
                cachedSizeMb = mb,
                lastCachedCenterLat = centerLat,
                lastCachedCenterLon = centerLon,
                style = style,
                message = if (failures == 0) {
                    "Local ${style.label} ready: $count tiles (%.2f MB)".format(mb)
                } else {
                    "Saved $count local map tiles ($failures failed — check internet)"
                }
            )
        }
    }

    fun clearCache() {
        downloadJob?.cancel()
        gmapsRoot.deleteRecursively()
        gmapsRoot.mkdirs()
        legacyOsmRoot.deleteRecursively()
        legacyOsmRoot.mkdirs()
        metaPrefs.edit().clear().apply()
        _status.value = OfflineMapCacheStatus(message = "Local Google Map cache cleared")
    }

    fun loadTileBitmap(
        zoom: Int,
        x: Int,
        y: Int,
        preferredStyle: MapTileStyle = _status.value.style
    ): Bitmap? {
        val candidates = listOf(
            gmapTileFile(preferredStyle, zoom, x, y),
            gmapTileFile(
                if (preferredStyle == MapTileStyle.GMAPS_ROADMAP) MapTileStyle.GMAPS_HYBRID
                else MapTileStyle.GMAPS_ROADMAP,
                zoom,
                x,
                y
            ),
            legacyTileFile(zoom, x, y)
        )
        for (file in candidates) {
            if (file.exists() && file.length() > 0L) {
                val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                if (bmp != null) return bmp
            }
        }
        return null
    }

    private fun gmapTileFile(style: MapTileStyle, zoom: Int, x: Int, y: Int): File {
        val dir = File(gmapsRoot, "${style.folderName}/$zoom/$x").apply { mkdirs() }
        return File(dir, "$y.png")
    }

    private fun legacyTileFile(zoom: Int, x: Int, y: Int): File =
        File(legacyOsmRoot, "$zoom/$x/$y.png")

    private fun fetchAndSaveTile(coord: TileCoord, style: MapTileStyle, dest: File): Boolean {
        val googleUrls = listOf(
            "https://mt1.google.com/vt/lyrs=${style.lyrsCode}&x=${coord.x}&y=${coord.y}&z=${coord.zoom}",
            "https://mt0.google.com/vt/lyrs=${style.lyrsCode}&x=${coord.x}&y=${coord.y}&z=${coord.zoom}"
        )
        for (url in googleUrls) {
            if (downloadUrlToFile(url, dest, "Mozilla/5.0 (Linux; Android 14) RescueMesh/2.0")) {
                return true
            }
        }
        // Fallback to OpenStreetMap standard raster tiles if Google tile mirror throttles
        val osmUrl = "https://tile.openstreetmap.org/${coord.zoom}/${coord.x}/${coord.y}.png"
        return downloadUrlToFile(osmUrl, dest, "RescueMeshAndroid/2.0 (offline-emergency-mesh)")
    }

    private fun downloadUrlToFile(url: String, dest: File, userAgent: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val bytes = resp.body?.bytes() ?: return false
                if (bytes.isEmpty()) return false
                dest.writeBytes(bytes)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun measureCache(): Pair<Int, Long> {
        var count = 0
        var totalBytes = 0L
        listOf(gmapsRoot, legacyOsmRoot).forEach { root ->
            root.walkTopDown().forEach { file ->
                if (file.isFile && file.extension == "png") {
                    count++
                    totalBytes += file.length()
                }
            }
        }
        return count to totalBytes
    }

    companion object {
        fun latLonToTile(lat: Double, lon: Double, zoom: Int): TileCoord {
            val n = 1 shl zoom
            val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val y = floor((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
            return TileCoord(zoom, x, y)
        }

        fun latLonToFractionalTile(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
            val n = (1 shl zoom).toDouble()
            val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            val x = (lon + 180.0) / 360.0 * n
            val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
            return x to y
        }

        fun buildTileListForRegion(
            centerLat: Double,
            centerLon: Double,
            radiusKm: Double,
            zooms: List<Int>
        ): List<TileCoord> {
            val deltaLat = radiusKm / 111.0
            val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.2)
            val deltaLon = radiusKm / (111.32 * cosLat)
            val out = mutableListOf<TileCoord>()
            for (z in zooms) {
                val topLeft = latLonToTile(centerLat + deltaLat, centerLon - deltaLon, z)
                val bottomRight = latLonToTile(centerLat - deltaLat, centerLon + deltaLon, z)
                val xMin = minOf(topLeft.x, bottomRight.x)
                val xMax = maxOf(topLeft.x, bottomRight.x)
                val yMin = minOf(topLeft.y, bottomRight.y)
                val yMax = maxOf(topLeft.y, bottomRight.y)
                for (x in xMin..xMax) {
                    for (y in yMin..yMax) {
                        out.add(TileCoord(z, x, y))
                    }
                }
            }
            return out.take(520)
        }
    }
}
