package com.cgwx.cgmap

import android.content.Context
import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Polygon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Constants for map tile URLs and colors
 */
const val callout = "https://t0.tianditu.gov.cn/DataServer?T=cva_w&x={x}&y={y}&l={z}&tk=6358b0d8879ee8b38e028872e841344b"
const val amap = "https://webst01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}"
const val china2023 = "https://tile.charmingglobe.com/tile/china2023_2/tms/{z}/{x}/{y}?token=Bearer%20bc5bb178739745ebac0643579295202b"
const val primaryYellow = "#FFD700"
const val primaryYellowDark = "#F5C03E"
const val hunan1 = "https://tile.charmingglobe.com/map/dataset/0201012024101913480244_ds_0201012024101913480244_L5/{z}/{x}/{y}?token=Bearer bc5bb178739745ebac0643579295202b&format=image/webp"
const val hunan2 = "https://tile.charmingglobe.com/map/dataset/0201012024102514167082_ds_0201012024102514167082_L5/{z}/{x}/{y}?token=Bearer bc5bb178739745ebac0643579295202b&format=image/webp"

/**
 * Data class representing a raster layer configuration
 * @property layerId Unique identifier for the layer
 * @property sourceId Unique identifier for the source
 * @property tileUrl URL template for tile requests
 * @property scheme Tile URL scheme (default: "xyz")
 * @property callout Whether to show callouts for this layer
 */
data class CGRasterLayer(
    val layerId: String = "",
    val sourceId: String = "",
    val tileUrl: String = "",
    val scheme: String = "xyz",
    val callout: Boolean = true,
)

/**
 * Data class representing an overlay layer with visibility and order properties
 * @property rasterLayer The underlying raster layer configuration
 * @property isVisible Whether the layer is currently visible
 * @property order Display order of the layer
 */
data class CGOverlayLayer(
    val rasterLayer: CGRasterLayer,
    var isVisible: Boolean = true,
    var order: Int = 0
)

/**
 * Enum representing different types of vertices in a polygon
 */
enum class VertexType {
    VERTEX,
    MIDPOINT,
    NONE
}

/**
 * Sample GeoJSON data for testing
 */
val geojson = """{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "geometry": {
        "type": "Polygon",
        "coordinates": [
          [
            [125.39339978575325, 43.977401542508904],
            [125.39359978575325, 43.977401542508904],
            [125.39359978575325, 43.977201542508904],
            [125.39339978575325, 43.977201542508904],
            [125.39339978575325, 43.977401542508904]
          ]
        ]
      },
      "properties": {
        "id": "polygon-1"
      }
    },
    {
      "type": "Feature",
      "geometry": {
        "type": "Polygon",
        "coordinates": [
          [
            [125.39440978575325, 43.978401542508904],
            [125.39460978575325, 43.978401542508904],
            [125.39460978575325, 43.978201542508904],
            [125.39440978575325, 43.978201542508904],
            [125.39440978575325, 43.978401542508904]
          ]
        ]
      },
      "properties": {
        "id": "polygon-2"
      }
    }
  ]
}"""

/**
 * Additional sample GeoJSON data for testing
 */
val geojson2 = """
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "geometry": {
        "type": "Polygon",
        "coordinates": [
          [
            [125.39389978575325, 43.977901542508904],
            [125.39409978575325, 43.977901542508904],
            [125.39409978575325, 43.977701542508904],
            [125.39389978575325, 43.977701542508904],
            [125.39389978575325, 43.977901542508904]
          ]
        ]
      },
      "properties": {
        "id": "polygon-3"
      }
    },
    {
      "type": "Feature",
      "geometry": {
        "type": "Polygon",
        "coordinates": [
          [
            [125.39480978575325, 43.978901542508904],
            [125.39500978575325, 43.978901542508904],
            [125.39500978575325, 43.978701542508904],
            [125.39480978575325, 43.978701542508904],
            [125.39480978575325, 43.978901542508904]
          ]
        ]
      },
      "properties": {
        "id": "polygon-4"
      }
    },
    {
      "type": "Feature",
      "geometry": {
        "type": "Polygon",
        "coordinates": [
          [
            [125.39560978575325, 43.979401542508904],
            [125.39580978575325, 43.979401542508904],
            [125.39580978575325, 43.979201542508904],
            [125.39560978575325, 43.979201542508904],
            [125.39560978575325, 43.979401542508904]
          ]
        ]
      },
      "properties": {
        "id": "polygon-5"
      }
    }
  ]
}
"""

/**
 * Class for handling GeoJSON features with additional properties
 */
class CGFeature {
    private var feature: Feature

    /**
     * Constructor creating an empty feature with a timestamp ID
     */
    constructor() {
        this.feature = Feature.fromGeometry(null).apply {
            addStringProperty("id", getCurrentTimestamp())
        }
    }

    /**
     * Constructor creating a feature with polygon geometry and measurements
     * @param polygon The polygon geometry
     * @param area The area measurement
     * @param distance The distance measurement
     */
    constructor(polygon: Polygon, area: String, distance: String) {
        this.feature = Feature.fromGeometry(polygon).apply {
            addStringProperty("id", getCurrentTimestamp())
            addStringProperty("area", area)
            addStringProperty("distance", distance)
        }
    }

    /**
     * Convert the feature to GeoJSON string
     * @return GeoJSON string representation
     */
    fun toGeoJSON(): String = feature.toJson()

    /**
     * Add additional properties to the feature
     * @param params Map of property names and values
     * @return The updated feature
     */
    fun addExtra(params: Map<String, Any?>): CGFeature {
        val updatedFeature = Feature.fromGeometry(feature.geometry())

        feature.properties()?.keySet()?.forEach { key ->
            val value = feature.getProperty(key)
            when {
                value.isJsonNull -> updatedFeature.addStringProperty(key, "null")
                value.isJsonPrimitive -> {
                    val primitive = value.asJsonPrimitive
                    when {
                        primitive.isString -> updatedFeature.addStringProperty(key, primitive.asString)
                        primitive.isNumber -> updatedFeature.addNumberProperty(key, primitive.asNumber)
                        primitive.isBoolean -> updatedFeature.addBooleanProperty(key, primitive.asBoolean)
                    }
                }
            }
        }

        params.forEach { (key, value) ->
            when (value) {
                is String -> updatedFeature.addStringProperty(key, value)
                is Number -> updatedFeature.addNumberProperty(key, value)
                is Boolean -> updatedFeature.addBooleanProperty(key, value)
                null -> updatedFeature.addStringProperty(key, "null")
                else -> updatedFeature.addStringProperty(key, value.toString())
            }
        }

        this.feature = updatedFeature

        return this
    }

    /**
     * Generate a timestamp string in the format "yyyyMMddHHmmss"
     * @return Formatted timestamp string
     */
    private fun getCurrentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
        return sdf.format(Date())
    }
}

/**
 * Data class for drawing properties of map elements
 * @property circleRadius Radius of circles
 * @property circleColor Color of circle fills
 * @property circleStrokeColor Color of circle outlines
 * @property circleStrokeWidth Width of circle outlines
 * @property lineColor Color of lines
 * @property lineWidth Width of lines
 * @property tempLineColor Color of temporary lines
 * @property tempLineWidth Width of temporary lines
 * @property fillColor Color of polygon fills
 * @property fillOpacity Opacity of polygon fills
 * @property fillOutlineColor Color of polygon outlines
 */
data class DrawProperties(
    val circleRadius: Float = 8f,
    val circleColor: String = "#FFFFFF",
    val circleStrokeColor: String = primaryYellowDark,
    val circleStrokeWidth: Float = 2f,
    val lineColor: String = primaryYellowDark,
    val lineWidth: Float = 2f,
    val tempLineColor: String = "#FFFFFF",
    val tempLineWidth: Float = 1f,
    val fillColor: String = primaryYellow,
    val fillOpacity: Float = 0.4f,
    val fillOutlineColor: String = "#FFFFFF",
)

/**
 * Data class for fill properties of map elements
 * @property lineColor Color of fill outlines
 * @property lineWidth Width of fill outlines
 * @property fillColor Color of fills
 * @property fillOpacity Opacity of fills
 */
data class FillProperties(
    val lineColor: String = primaryYellowDark,
    val lineWidth: Float = 2f,
    val fillColor: String = primaryYellow,
    val fillOpacity: Float = 0.4f,
)

/**
 * Data class for vertex properties of map elements
 * @property circleRadius Radius of vertex circles
 * @property circleColor Color of vertex circle fills
 * @property circleStrokeColor Color of vertex circle outlines
 * @property circleStrokeWidth Width of vertex circle outlines
 * @property midCircleRadius Radius of midpoint circles
 * @property midCircleColor Color of midpoint circle fills
 * @property midCircleStrokeColor Color of midpoint circle outlines
 * @property midCircleStrokeWidth Width of midpoint circle outlines
 * @property lineColor Color of lines
 * @property lineWidth Width of lines
 * @property fillColor Color of fills
 * @property fillOpacity Opacity of fills
 * @property fillOutlineColor Color of fill outlines
 */
data class VertexProperties(
    val circleRadius: Float = 10f,
    val circleColor: String = "#FFFFFF",
    val circleStrokeColor: String = primaryYellowDark,
    val circleStrokeWidth: Float = 2f,
    val midCircleRadius: Float = 6f,
    val midCircleColor: String = "#FFFFFF",
    val midCircleStrokeColor: String = "#FFFFFF",
    val midCircleStrokeWidth: Float = 1f,
    val lineColor: String = primaryYellowDark,
    val lineWidth: Float = 2f,
    val fillColor: String = primaryYellow,
    val fillOpacity: Float = 0.4f,
    val fillOutlineColor: String = "#FFFFFF",
)

/**
 * Enum for distance units
 */
enum class DistanceUnit {
    METER, KILOMETER
}

/**
 * Enum for area units
 */
enum class AreaUnit {
    METER, KILOMETER
}

/**
 * Enum for movement types
 */
enum class MovementType {
    STATIONARY,
    WALKING,
    DRIVING
}

/**
 * Determine movement type based on speed
 * @param speed Speed in meters per second
 * @return MovementType based on speed thresholds
 */
fun getMovementTypeBySpeed(speed: Float): MovementType {
    return when {
        speed < 0.5 -> MovementType.STATIONARY
        speed < 3.0 -> MovementType.WALKING
        else -> MovementType.DRIVING
    }
}

/**
 * Get update interval based on movement type
 * @param type MovementType to determine interval
 * @return Update interval in milliseconds
 */
fun getIntervalByType(type: MovementType): Long {
    return when (type) {
        MovementType.STATIONARY -> 5000L
        MovementType.WALKING -> 1000L
        MovementType.DRIVING -> 10000L
    }
}

/**
 * Convert GCJ-02 coordinates to WGS-84 coordinates
 * @param gcjLat Latitude in GCJ-02 coordinate system
 * @param gcjLon Longitude in GCJ-02 coordinate system
 * @return Map containing WGS-84 coordinates
 */
fun gcj02ToWgs84(gcjLat: Double, gcjLon: Double): Map<String, Double> {
    if (isOutOfChina(gcjLat, gcjLon)) {
        return mapOf("lat" to gcjLat, "lon" to gcjLon)
    }

    var dLat = transformLat(gcjLon - 105.0, gcjLat - 35.0)
    var dLon = transformLon(gcjLon - 105.0, gcjLat - 35.0)
    val radLat = gcjLat / 180.0 * PI
    var magic = sin(radLat)
    magic = 1 - ee * magic * magic
    val sqrtMagic = sqrt(magic)

    dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * PI)
    dLon = (dLon * 180.0) / (a / sqrtMagic * cos(radLat) * PI)

    val wgsLat = gcjLat - dLat
    val wgsLon = gcjLon - dLon
    return mapOf("lat" to wgsLat, "lon" to wgsLon)
}

/**
 * Constants for coordinate transformation
 */
private const val a = 6378245.0
private const val ee = 0.006693421622965943

/**
 * Check if coordinates are outside of China
 * @param lat Latitude to check
 * @param lon Longitude to check
 * @return Boolean indicating if coordinates are outside China
 */
fun isOutOfChina(lat: Double, lon: Double): Boolean {
    return !(lon in 72.004..137.8347 && lat in 0.8293..55.8271)
}

/**
 * Transform latitude for GCJ-02 to WGS-84 conversion
 * @param x X coordinate offset
 * @param y Y coordinate offset
 * @return Transformed latitude value
 */
fun transformLat(x: Double, y: Double): Double {
    var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
    result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
    result += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
    result += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
    return result
}

/**
 * Transform longitude for GCJ-02 to WGS-84 conversion
 * @param x X coordinate offset
 * @param y Y coordinate offset
 * @return Transformed longitude value
 */
fun transformLon(x: Double, y: Double): Double {
    var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
    result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
    result += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
    result += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
    return result
}

/**
 * Get the default path for storing map tiles
 * @param context Android Context
 * @return String path to the tiles directory
 */
fun getDefaultTilePath(context: Context): String {
    return context.getExternalFilesDir("tiles")?.absolutePath ?: throw IllegalStateException("无法获取外部存储目录")
}

/**
 * Class for handling geographical coordinates
 */
class CGLatLng {
    val latLng: LatLng

    /**
     * Constructor with latitude and longitude
     * @param lat Latitude value
     * @param lon Longitude value
     */
    constructor(lat: Double, lon: Double) {
        latLng = LatLng(lat, lon)
    }

    /**
     * Constructor with existing LatLng object
     * @param latLng Existing LatLng object
     */
    constructor(latLng: LatLng) {
        this.latLng = latLng
    }

    /**
     * Get latitude value
     */
    val latitude: Double get() = latLng.latitude

    /**
     * Get longitude value
     */
    val longitude: Double get() = latLng.longitude

    /**
     * Convert coordinates to formatted string
     * @return Formatted string representation
     */
    fun toFormattedString(): String {
        return "Lat: $latitude, Lon: $longitude"
    }

    /**
     * Convert to MapLibre LatLng object
     * @return MapLibre LatLng object
     */
    fun toLatLng(): LatLng {
        return LatLng(latitude, longitude)
    }

    /**
     * Convert to string representation
     * @return String representation of coordinates
     */
    override fun toString(): String {
        return "CGLatLng(latitude=$latitude, longitude=$longitude)"
    }
}