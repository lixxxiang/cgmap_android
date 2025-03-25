package com.cgwx.cgmap

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.orhanobut.logger.Logger
import kotlinx.coroutines.*
import java.io.*

/**
 * Converts XYZ tile format to MBTiles format
 * Handles the conversion process with progress tracking and error handling
 */
class MBTilesConverter {
    private val _conversionProgress = MutableLiveData<ConversionProgress>()
    val conversionProgress: LiveData<ConversionProgress> = _conversionProgress

    private var conversionJob: Job? = null
    private val conversionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var db: SQLiteDatabase? = null

    /**
     * Data class to track conversion progress
     * @property total Total number of tiles to process
     * @property current Current number of processed tiles
     * @property currentZoom Current zoom level being processed
     * @property currentX Current X coordinate being processed
     * @property currentY Current Y coordinate being processed
     * @property status Current status of the conversion process
     */
    data class ConversionProgress(
        val total: Int,
        val current: Int,
        val currentZoom: Int,
        val currentX: Int,
        val currentY: Int,
        val status: Status = Status.RUNNING
    ) {
        /**
         * Enum representing possible conversion statuses
         */
        enum class Status {
            RUNNING,
            COMPLETED,
            ERROR,
            CANCELLED
        }
    }

    /**
     * Start the conversion process from XYZ to MBTiles format
     * @param xyzDirPath Path to the directory containing XYZ tiles
     * @param outputPath Path where the MBTiles file will be created
     */
    fun startConversion(xyzDirPath: String, outputPath: String) {
        cancelConversion()

        conversionJob = conversionScope.launch {
            try {
                convertXYZtoMBTiles(xyzDirPath, outputPath)
                _conversionProgress.postValue(
                    _conversionProgress.value?.copy(status = ConversionProgress.Status.COMPLETED)
                        ?: ConversionProgress(0, 0, 0, 0, 0, ConversionProgress.Status.COMPLETED)
                )
            } catch (e: CancellationException) {
                Logger.d("Conversion cancelled")
                _conversionProgress.postValue(
                    _conversionProgress.value?.copy(status = ConversionProgress.Status.CANCELLED)
                        ?: ConversionProgress(0, 0, 0, 0, 0, ConversionProgress.Status.CANCELLED)
                )
                cleanupOnCancel(outputPath)
                throw e
            } catch (e: Exception) {
                Logger.e("Conversion failed: ${e.message}")
                _conversionProgress.postValue(
                    _conversionProgress.value?.copy(status = ConversionProgress.Status.ERROR)
                        ?: ConversionProgress(0, 0, 0, 0, 0, ConversionProgress.Status.ERROR)
                )
                e.printStackTrace()
            } finally {
                closeDatabase()
            }
        }
    }

    /**
     * Cancel the ongoing conversion process
     */
    fun cancelConversion() {
        conversionJob?.cancel()
        closeDatabase()
    }

    /**
     * Close the SQLite database connection
     */
    private fun closeDatabase() {
        try {
            db?.endTransaction()
            db?.close()
            db = null
        } catch (e: Exception) {
            Logger.e("Failed to close database: ${e.message}")
        }
    }

    /**
     * Clean up resources when conversion is cancelled
     * @param outputPath Path to the output file to be deleted
     */
    private fun cleanupOnCancel(outputPath: String) {
        try {
            closeDatabase()
            File(outputPath).delete()
        } catch (e: Exception) {
            Logger.e("Failed to clean up files: ${e.message}")
        }
    }

    /**
     * Convert XYZ tile format to MBTiles format
     * @param xyzDirPath Path to the directory containing XYZ tiles
     * @param outputPath Path where the MBTiles file will be created
     */
    private suspend fun convertXYZtoMBTiles(xyzDirPath: String, outputPath: String) = withContext(Dispatchers.IO) {
        try {
            File(outputPath).parentFile?.mkdirs()
            File(outputPath).delete()

            db = SQLiteDatabase.openOrCreateDatabase(outputPath, null)

            db?.execSQL("""
                CREATE TABLE IF NOT EXISTS tiles (
                    zoom_level INTEGER,
                    tile_column INTEGER,
                    tile_row INTEGER,
                    tile_data BLOB,
                    PRIMARY KEY (zoom_level, tile_column, tile_row)
                )
            """)

            db?.execSQL("""
                CREATE TABLE IF NOT EXISTS metadata (
                    name TEXT,
                    value TEXT,
                    PRIMARY KEY (name)
                )
            """)

            val metadataValues = arrayOf(
                ContentValues().apply {
                    put("name", "name")
                    put("value", "tiles")
                },
                ContentValues().apply {
                    put("name", "type")
                    put("value", "baselayer")
                },
                ContentValues().apply {
                    put("name", "version")
                    put("value", "1.0")
                },
                ContentValues().apply {
                    put("name", "format")
                    put("value", "png")
                }
            )

            db?.beginTransaction()
            try {
                metadataValues.forEach { values ->
                    db?.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db?.setTransactionSuccessful()
            } finally {
                db?.endTransaction()
            }

            val xyzDir = File(xyzDirPath)
            val zoomLevels = xyzDir.listFiles()?.filter { it.isDirectory }?.map { it.name.toInt() } ?: emptyList()

            var totalTiles = 0
            var processedTiles = 0

            zoomLevels.forEach { zoom ->
                val zoomDir = File(xyzDir, zoom.toString())
                zoomDir.listFiles()?.forEach { xDir ->
                    totalTiles += xDir.listFiles()?.size ?: 0
                }
            }

            Logger.d("Total tiles: $totalTiles")

            val batchSize = 50

            zoomLevels.forEach { zoom ->
                ensureActive()
                val zoomDir = File(xyzDir, zoom.toString())

                zoomDir.listFiles()?.forEach { xDir ->
                    ensureActive()
                    val x = xDir.name.toInt()

                    db?.beginTransaction()
                    try {
                        var batchCount = 0

                        xDir.listFiles()?.forEach { yFile ->
                            ensureActive()
                            val y = yFile.name.replace(".png", "").toInt()

                            try {
                                val tileData = yFile.inputStream().use { it.readBytes() }

                                if (!isPngValid(tileData)) {
                                    Logger.w("Skipping invalid PNG file: ${yFile.absolutePath}")
                                    return@forEach
                                }

                                val values = ContentValues().apply {
                                    put("zoom_level", zoom)
                                    put("tile_column", x)
                                    put("tile_row", y)
                                    put("tile_data", tileData)
                                }

                                db?.insertWithOnConflict("tiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)

                                batchCount++
                                processedTiles++

                                if (batchCount >= batchSize) {
                                    db?.setTransactionSuccessful()
                                    db?.endTransaction()
                                    db?.beginTransaction()
                                    batchCount = 0
                                    System.gc()
                                }

                                _conversionProgress.postValue(
                                    ConversionProgress(
                                        total = totalTiles,
                                        current = processedTiles,
                                        currentZoom = zoom,
                                        currentX = x,
                                        currentY = y,
                                        status = ConversionProgress.Status.RUNNING
                                    )
                                )
                            } catch (e: Exception) {
                                Logger.e("Error processing tile: ${yFile.absolutePath}, ${e.message}")
                            }
                        }

                        db?.setTransactionSuccessful()
                    } finally {
                        db?.endTransaction()
                    }
                }
            }

            db?.setTransactionSuccessful()
        } finally {
            db?.endTransaction()
        }
    }

    /**
     * Validate if the provided byte array contains a valid PNG image
     * @param data Byte array containing the image data
     * @return Boolean indicating if the data is a valid PNG image
     */
    private fun isPngValid(data: ByteArray): Boolean {
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size) != null
        } catch (e: Exception) {
            false
        }
    }
}