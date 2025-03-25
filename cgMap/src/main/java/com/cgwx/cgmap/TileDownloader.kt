package com.cgwx.cgmap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.orhanobut.logger.Logger
import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.pow

class TileDownloader(private val context: Context) {
    private val _downloadProgress = MutableLiveData<DownloadProgress>()
    val downloadProgress: LiveData<DownloadProgress> = _downloadProgress

    private var downloadJob: Job? = null
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(50, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply {
            maxRequestsPerHost = 10
            maxRequests = 50
        })
        .build()

    data class DownloadProgress(
        val total: Int,
        val current: Int,
        val currentZoom: Int,
        val currentX: Int,
        val currentY: Int,
        val failedTiles: Int,
        val retryingTiles: Int,
        val status: Status
    ) {
        enum class Status {
            RUNNING,
            COMPLETED,
            ERROR,
            CANCELLED
        }
    }

    data class TileInfo(
        val zoom: Int,
        val x: Int,
        val y: Int,
        val url: String,
        val file: File,
        var retryCount: Int = 0
    )

    private val dis = 20037508.342789244
    private val maxRetries = 3
    private var failedTilesCount = 0
    private var retryingTilesCount = 0
    private val retryDelays = listOf(1000L, 3000L, 5000L)

    fun download(coordinates: List<List<List<Double>>>, tileUrl: String, savePath: String, minZoom: Int, maxZoom: Int, tileScheme: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Logger.e("Missing storage permission")
            return
        }

        downloadJob?.cancel()
        failedTilesCount = 0
        retryingTilesCount = 0

        downloadJob = downloadScope.launch {
            try {
                var minLat = Double.MAX_VALUE
                var maxLat = Double.MIN_VALUE
                var minLng = Double.MAX_VALUE
                var maxLng = Double.MIN_VALUE

                coordinates.forEach { ring ->
                    ring.forEach { point ->
                        minLat = minOf(minLat, point[1])
                        maxLat = maxOf(maxLat, point[1])
                        minLng = minOf(minLng, point[0])
                        maxLng = maxOf(maxLng, point[0])
                    }
                }

                Logger.d("Bounding box: [$minLng,$minLat,$maxLng,$maxLat]")

                val saveDir = File(savePath).apply {
                    if (!exists() && !mkdirs()) {
                        throw IOException("Failed to create directory: $absolutePath")
                    }
                    if (!canWrite()) {
                        throw IOException("Directory is not writable: $absolutePath")
                    }
                }

                val tiles = mutableListOf<TileInfo>()
                var totalTiles = 0

                for (zoom in minZoom..maxZoom) {
                    collectTilesForZoom(zoom, minLat, maxLat, minLng, maxLng, tileUrl, tileScheme, saveDir, tiles)
                    totalTiles = tiles.size
                    Logger.d("Zoom level $zoom collection complete, current total tiles: $totalTiles")
                }

                Logger.d("Total tiles to download: $totalTiles")

                val batchSize = 100
                val failedTiles = mutableListOf<TileInfo>()
                var currentIndex = 0

                _downloadProgress.postValue(DownloadProgress(
                    total = totalTiles,
                    current = currentIndex,
                    currentZoom = 0,
                    currentX = 0,
                    currentY = 0,
                    failedTiles = failedTilesCount,
                    retryingTiles = retryingTilesCount,
                    status = DownloadProgress.Status.RUNNING
                ))

                while ((currentIndex < tiles.size || failedTiles.isNotEmpty()) && isActive) {
                    if (currentIndex >= tiles.size && failedTiles.isNotEmpty()) {
                        val retriableTiles = failedTiles.filter { it.retryCount < maxRetries }
                        if (retriableTiles.isEmpty()) {
                            Logger.d("All retry attempts completed, remaining ${failedTiles.size} tiles failed to download")
                            break
                        }
                        tiles.clear()
                        tiles.addAll(retriableTiles)
                        failedTiles.clear()
                        currentIndex = 0
                        delay(5000)
                    }

                    val endIndex = minOf(currentIndex + batchSize, tiles.size)
                    val batch = tiles.subList(currentIndex, endIndex)

                    try {
                        coroutineScope {
                            batch.map { tile ->
                                async {
                                    try {
                                        if (downloadTileWithRetry(tile)) {
                                            withContext(Dispatchers.Main) {
                                                _downloadProgress.value = DownloadProgress(
                                                    total = totalTiles,
                                                    current = currentIndex,
                                                    currentZoom = tile.zoom,
                                                    currentX = tile.x,
                                                    currentY = tile.y,
                                                    failedTiles = failedTilesCount,
                                                    retryingTiles = retryingTilesCount,
                                                    status = DownloadProgress.Status.RUNNING
                                                )
                                            }
                                        } else {
                                            failedTiles.add(tile)
                                            failedTilesCount++
                                        }
                                    } catch (e: Exception) {
                                        Logger.e("Download tile failed: ${tile.url}, Error: ${e.message}")
                                        failedTiles.add(tile)
                                        failedTilesCount++
                                    }
                                }
                            }.awaitAll()
                        }
                    } catch (e: CancellationException) {
                        Logger.d("Download task cancelled")
                        throw e
                    } catch (e: Exception) {
                        Logger.e("Batch download failed: ${e.message}")
                    }

                    currentIndex = endIndex
                    delay(50)
                }

                if (currentIndex >= totalTiles) {
                    _downloadProgress.postValue(DownloadProgress(
                        total = totalTiles,
                        current = currentIndex,
                        currentZoom = 0,
                        currentX = 0,
                        currentY = 0,
                        failedTiles = failedTilesCount,
                        retryingTiles = retryingTilesCount,
                        status = DownloadProgress.Status.COMPLETED
                    ))
                }
            } catch (e: CancellationException) {
                Logger.d("Download task cancelled")
                throw e
            } catch (e: Exception) {
                _downloadProgress.postValue(DownloadProgress(
                    total = 0,
                    current = 0,
                    currentZoom = 0,
                    currentX = 0,
                    currentY = 0,
                    failedTiles = 0,
                    retryingTiles = 0,
                    status = DownloadProgress.Status.ERROR
                ))
                Logger.e("Error during download: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun collectTilesForZoom(
        zoom: Int,
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        tileUrl: String,
        tileScheme: String,
        saveDir: File,
        tiles: MutableList<TileInfo>
    ) {
        val scale = 2.0.pow(zoom) / (dis * 2)

        val mercatorMinX = (minLng * dis / 180.0)
        val mercatorMaxX = (maxLng * dis / 180.0)
        val mercatorMinY = dis * Math.log(Math.tan((Math.PI * (90.0 + minLat) / 360.0))) / Math.PI
        val mercatorMaxY = dis * Math.log(Math.tan((Math.PI * (90.0 + maxLat) / 360.0))) / Math.PI

        val tileMinX = ((mercatorMinX + dis) * scale).toInt()
        val tileMaxX = ((mercatorMaxX + dis) * scale).toInt()
        val tileMinY = ((dis - mercatorMaxY) * scale).toInt()
        val tileMaxY = ((dis - mercatorMinY) * scale).toInt()

        val validMinX = maxOf(0, tileMinX)
        val validMaxX = minOf((1 shl zoom) - 1, tileMaxX)
        val validMinY = maxOf(0, tileMinY)
        val validMaxY = minOf((1 shl zoom) - 1, tileMaxY)

        Logger.d("Tile range for zoom level $zoom: X[$validMinX-$validMaxX], Y[$validMinY-$validMaxY]")

        for (x in validMinX..validMaxX) {
            for (y in validMinY..validMaxY) {
                val finalY = if (tileScheme == "tms") {
                    (1 shl zoom) - 1 - y
                } else {
                    y
                }

                val currentTileUrl = tileUrl
                    .replace("{z}", zoom.toString())
                    .replace("{x}", x.toString())
                    .replace("{y}", finalY.toString())

                val tileFile = File(File(File(saveDir, zoom.toString()), x.toString()), "$y.png")
                if (!tileFile.exists()) {
                    tiles.add(TileInfo(zoom, x, y, currentTileUrl, tileFile))
                }
            }
        }
    }

    private suspend fun downloadTileWithRetry(tile: TileInfo): Boolean {
        repeat(maxRetries) { attempt ->
            if (attempt > 0) {
                retryingTilesCount++
                delay(retryDelays[minOf(attempt - 1, retryDelays.size - 1)])
            }

            try {
                downloadTile(tile.url, tile.file)
                if (attempt > 0) {
                    retryingTilesCount--
                }
                return true
            } catch (e: Exception) {
                Logger.e("Download tile failed (attempt ${attempt + 1}/$maxRetries): ${tile.url}, Error: ${e.message}")
                tile.retryCount++
                if (attempt == maxRetries - 1) {
                    if (attempt > 0) {
                        retryingTilesCount--
                    }
                    return false
                }
            }
        }
        return false
    }

    private suspend fun downloadTile(url: String, file: File) {
        try {
            file.parentFile?.mkdirs()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.bytes()?.let { bytes ->
                            file.writeBytes(bytes)
                        }
                    } else {
                        throw IOException("HTTP ${response.code}")
                    }
                }
            }
        } catch (e: Exception) {
            throw e
        }
    }

    fun cancel() {
        downloadJob?.cancel()
        _downloadProgress.postValue(DownloadProgress(
            total = 0,
            current = 0,
            currentZoom = 0,
            currentX = 0,
            currentY = 0,
            failedTiles = 0,
            retryingTiles = 0,
            status = DownloadProgress.Status.CANCELLED
        ))
    }

    fun getTileCoordinates(lat: Double, lng: Double, zoom: Int, scheme: String): Triple<Int, Int, Int> {
        val scale = 2.0.pow(zoom) / (dis * 2)

        val mercatorX = (lng * dis / 180.0)
        val mercatorY = Math.log(Math.tan((Math.PI * (90.0 + lat) * Math.PI / 360.0))) / (Math.PI / 180.0) * dis / 180.0

        val tileX = ((mercatorX + dis) * scale).toInt()
        val tileY = ((dis - mercatorY) * scale).toInt()

        val finalY = if (scheme == "tms") {
            (1 shl zoom) - 1 - tileY
        } else {
            tileY
        }

        return Triple(zoom, tileX, finalY)
    }
}