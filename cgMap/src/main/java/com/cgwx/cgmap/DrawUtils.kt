package com.cgwx.cgmap

import android.location.Location
import com.orhanobut.logger.Logger
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat

/**
 * Drawing utility class for distance measurement and area calculation on map
 */
object DrawUtil {
    private var spots = mutableListOf<Point>()
    private var spotsForPolygon = mutableListOf<Point>()
    private var spotSourceAdded = false
    private var lineSourceAdded = false
    private var polygonGenerated = false
    private var tempLineSourceAdded = false
    private var mapLibreMap: MapLibreMap? = null
    private lateinit var properties: DrawProperties
    private var measureDistanceFlag = false

    /**
     * Start distance measurement
     * @param m MapLibreMap Map instance
     * @param p DrawProperties Drawing properties configuration
     */
    fun measureDistance(m: MapLibreMap, p: DrawProperties) {
        measureDistanceFlag = true
        addSpot(m, p)
    }

    /**
     * Complete distance measurement and return total distance
     * @return Double Total measured distance in meters
     */
    fun measureDone(): Double {
        spotSourceAdded = false
        lineSourceAdded = false
        polygonGenerated = false
        tempLineSourceAdded = false
        measureDistanceFlag = false

        updateLine()
        mapLibreMap?.getStyle { style ->
            style.getLayer("spot_layer")?.setProperties(PropertyFactory.visibility("none"))
            style.getLayer("line_layer")?.setProperties(PropertyFactory.visibility("none"))
            style.getLayer("temp_line_layer")?.setProperties(PropertyFactory.visibility("none"))
        }

        val distance = GeoCalculator.measurePerimeter2(spots)
        spots.clear()
        return distance.toDouble()
    }

    /**
     * Add measurement point
     * @param m MapLibreMap Map instance
     * @param p DrawProperties Drawing properties configuration
     */
    fun addSpot(m: MapLibreMap, p: DrawProperties) {
        mapLibreMap = m
        properties = p
        val centerLatLng = mapLibreMap?.cameraPosition?.target
        val centerPoint = Point.fromLngLat(centerLatLng!!.longitude, centerLatLng.latitude)
        spots.add(centerPoint)

        if (spots.size == 1 && !spotSourceAdded) {
            addSpotLayer()
            spotSourceAdded = true
        }

        if (spots.size == 2 && !lineSourceAdded) {
            addLine()
            lineSourceAdded = true
        }

        updateSpot()
        if (spots.size > 1) {
            updateLine()
        }
    }

    /**
     * Undo the last measurement point
     */
    fun recallSpot() {
        if (spots.isNotEmpty()) {
            spots.removeLast()
            val tempDistance = ArrayList(spots)
            tempDistance.add(getScreenCenterLatLng())
            spotsForPolygon = ArrayList(tempDistance)
            if (spotsForPolygon.isNotEmpty()) {
                spotsForPolygon.add(spotsForPolygon.first())
            }
            updateTempPolygon()
            updateTempLine()
            updateLine()
            updateSpot()
        }
    }

    /**
     * Clear all measurement points
     */
    fun redoSpot() {
        spots.clear()
        spotsForPolygon.clear()
        measureDistanceFlag = false
        updateSpot()
        updateLine()
        updateTempLine()
        updateTempPolygon()
    }

    /**
     * Update temporary line
     */
    private fun updateTempLine() {
        val center = getScreenCenterLatLng()
        mapLibreMap?.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>("temp_line_source")
            source?.setGeoJson(buildTempLine(center))
        }
    }

    /**
     * Build GeoJSON data for temporary line
     * @param center Point Screen center coordinates
     * @return FeatureCollection GeoJSON data for temporary line
     */
    private fun buildTempLine(center: Point): FeatureCollection {
        return if (spots.isNotEmpty()) {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(
                    LineString.fromLngLats(listOf(spots.last(), center))
                ).apply {
                    addNumberProperty("id", 0)
                }
            )
        } else {
            FeatureCollection.fromFeatures(emptyList())
        }
    }

    /**
     * Complete measurement and return CGFeature object
     * @return CGFeature Feature object containing measurement results
     */
    fun doneSpot(): CGFeature {
        spotSourceAdded = false
        lineSourceAdded = false
        polygonGenerated = false
        tempLineSourceAdded = false
        measureDistanceFlag = false

        spots = ArrayList(spotsForPolygon)
        updateLine()
        mapLibreMap?.getStyle { style ->
            style.getLayer("spot_layer")?.setProperties(PropertyFactory.visibility("none"))
            style.getLayer("line_layer")?.setProperties(PropertyFactory.visibility("none"))
            style.getLayer("temp_line_layer")?.setProperties(PropertyFactory.visibility("none"))
        }

        val distance = GeoCalculator.measurePerimeter(spots)
        val area = GeoCalculator.measureArea(spots)
        spots.clear()
        spotsForPolygon.clear()
        return CGFeature(Polygon.fromLngLats(listOf(spots)), area, distance)
    }

    /**
     * Update temporary shape
     */
    fun updateTemp() {
        if (spots.isNotEmpty() && !polygonGenerated) {
            spotsForPolygon = ArrayList(spots)
            val center = getScreenCenterLatLng()

            if (spots.size >= 2) {
                if (spotsForPolygon.size > spots.size) {
                    spotsForPolygon = spotsForPolygon.subList(0, spots.size).toMutableList()
                }
                spotsForPolygon.add(center)
                spotsForPolygon.add(spots.first())
            }

            if (spots.size == 1 && !tempLineSourceAdded) {
                addTempLine()
                addTempPolygon()
                tempLineSourceAdded = true
            } else {
                if (!polygonGenerated) {
                    updateTempLine()
                    updateTempPolygon()
                }
            }
        }
    }

    /**
     * Add temporary line layer
     */
    private fun addTempLine() {
        val center = getScreenCenterLatLng()
        mapLibreMap?.getStyle { style ->
            val tempLineSource = GeoJsonSource("temp_line_source", buildTempLine(center))
            style.addSource(tempLineSource)

            val tempLineLayer = LineLayer("temp_line_layer", "temp_line_source").apply {
                setProperties(
                    PropertyFactory.lineColor(properties.tempLineColor),
                    PropertyFactory.lineWidth(properties.tempLineWidth)
                )
            }
            style.addLayerBelow(tempLineLayer, "spot_layer")
        }
    }

    /**
     * Add temporary polygon layer
     */
    private fun addTempPolygon() {
        Logger.d(measureDistanceFlag)
        if (!measureDistanceFlag) {
            mapLibreMap?.getStyle { style ->
                val tempPolygonSource = GeoJsonSource("temp_polygon_source", buildTempPolygon())
                style.addSource(tempPolygonSource)

                val tempPolygonLayer = FillLayer("temp_polygon_layer", "temp_polygon_source").apply {
                    setProperties(
                        PropertyFactory.fillColor(properties.fillColor),
                        PropertyFactory.fillOpacity(properties.fillOpacity),
                        PropertyFactory.fillOutlineColor(properties.fillOutlineColor)
                    )
                }
                style.addLayerBelow(tempPolygonLayer, "spot_layer")
            }
        }
    }

    /**
     * Add point layer
     */
    private fun addSpotLayer() {
        mapLibreMap?.getStyle { style ->
            val spotSource = GeoJsonSource("spot_source", buildSpots())
            style.addSource(spotSource)
            val circleLayer = CircleLayer("spot_layer", "spot_source").apply {
                setProperties(
                    PropertyFactory.circleRadius(properties.circleRadius),
                    PropertyFactory.circleColor(properties.circleColor),
                    PropertyFactory.circleStrokeColor(properties.circleStrokeColor),
                    PropertyFactory.circleStrokeWidth(properties.circleStrokeWidth)
                )
            }
            style.addLayer(circleLayer)
        }
    }

    /**
     * Update point layer
     */
    private fun updateSpot() {
        mapLibreMap?.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>("spot_source")
            source?.setGeoJson(buildSpots())
        }
    }

    /**
     * Add line layer
     */
    private fun addLine() {
        mapLibreMap?.getStyle { style ->
            val lineSource = GeoJsonSource("line_source", buildLine())
            style.addSource(lineSource)

            val lineLayer = LineLayer("line_layer", "line_source").apply {
                setProperties(
                    PropertyFactory.lineColor(properties.lineColor),
                    PropertyFactory.lineWidth(properties.lineWidth)
                )
            }
            style.addLayerBelow(lineLayer, "spot_layer")
        }
    }

    /**
     * Update line layer
     */
    private fun updateLine() {
        mapLibreMap?.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>("line_source")
            source?.setGeoJson(buildLine())
        }
    }

    /**
     * Build GeoJSON data for points
     * @return FeatureCollection GeoJSON data for points
     */
    private fun buildSpots(): FeatureCollection {
        val features = spots.map {
            Feature.fromGeometry(it).apply {
                addStringProperty("id", "point_${it.longitude()}_${it.latitude()}")
            }
        }
        return FeatureCollection.fromFeatures(features)
    }

    /**
     * Build GeoJSON data for line
     * @return FeatureCollection GeoJSON data for line
     */
    private fun buildLine(): FeatureCollection {
        return FeatureCollection.fromFeature(
            Feature.fromGeometry(LineString.fromLngLats(spots)).apply {
                addNumberProperty("id", 0)
            }
        )
    }

    /**
     * Update temporary polygon
     */
    private fun updateTempPolygon() {
        if (!measureDistanceFlag) {
            mapLibreMap?.getStyle { style ->
                val source = style.getSourceAs<GeoJsonSource>("temp_polygon_source")
                source?.setGeoJson(buildTempPolygon())
            }
        }
    }

    /**
     * Build GeoJSON data for temporary polygon
     * @return FeatureCollection GeoJSON data for temporary polygon
     */
    private fun buildTempPolygon(): FeatureCollection {
        return if (spots.size < 2) {
            FeatureCollection.fromFeatures(emptyList())
        } else {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(Polygon.fromLngLats(listOf(spotsForPolygon))).apply {
                    addNumberProperty("id", 0)
                }
            )
        }
    }

    /**
     * Get screen center coordinates
     * @return Point Screen center coordinates
     */
    private fun getScreenCenterLatLng(): Point {
        val centerLatLng = mapLibreMap?.cameraPosition?.target
        return Point.fromLngLat(centerLatLng!!.longitude, centerLatLng.latitude)
    }
}

/**
 * Geographic calculation utility class for distance and area calculations
 */
object GeoCalculator {

    /**
     * Calculate polygon perimeter (including connection between first and last points)
     * @param points List<Point> List of polygon vertices
     * @return String Formatted perimeter value
     */
    fun measurePerimeter(points: List<Point>): String {
        if (points.size < 2) return "0.0"

        var totalDistance = 0.0
        for (i in 0 until points.size - 1) {
            totalDistance += calculateDistance(points[i], points[i + 1])
        }
        totalDistance += calculateDistance(points.last(), points.first())
        return formatDecimal(totalDistance)
    }

    /**
     * Calculate polygon perimeter (excluding connection between first and last points)
     * @param points List<Point> List of polygon vertices
     * @return String Formatted perimeter value
     */
    fun measurePerimeter2(points: List<Point>): String {
        if (points.size < 2) return "0.0"

        var totalDistance = 0.0
        for (i in 0 until points.size - 1) {
            totalDistance += calculateDistance(points[i], points[i + 1])
        }
        return formatDecimal(totalDistance)
    }

    /**
     * Calculate polygon area
     * @param points List<Point> List of polygon vertices
     * @return String Formatted area value
     */
    fun measureArea(points: List<Point>): String {
        if (points.size < 3) return "0.0"

        val n = points.size
        var area = 0.0

        for (i in 0 until n - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            area += Math.toRadians(p2.longitude() - p1.longitude()) *
                    (2 + Math.sin(Math.toRadians(p1.latitude())) + Math.sin(Math.toRadians(p2.latitude())))
        }

        area = Math.abs(area * EARTH_RADIUS * EARTH_RADIUS / 2.0)
        return formatDecimal(area)
    }

    /**
     * Format decimal number
     * @param value Double Number to format
     * @return String Formatted string
     */
    private fun formatDecimal(value: Double): String {
        return BigDecimal(value)
            .setScale(6, RoundingMode.HALF_UP)
            .toPlainString()
    }

    /**
     * Calculate distance between two points
     * @param p1 Point First point
     * @param p2 Point Second point
     * @return Double Distance between points in meters
     */
    private fun calculateDistance(p1: Point, p2: Point): Double {
        val location1 = Location("").apply {
            latitude = p1.latitude()
            longitude = p1.longitude()
        }
        val location2 = Location("").apply {
            latitude = p2.latitude()
            longitude = p2.longitude()
        }
        return location1.distanceTo(location2).toDouble()
    }

    private const val EARTH_RADIUS = 6371000.0
}