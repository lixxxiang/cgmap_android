package com.cgwx.cgmap

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.widget.FrameLayout
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.Logger
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import org.maplibre.android.style.expressions.Expression
import android.view.animation.LinearInterpolator
import kotlin.math.floor

/**
 * A custom MapLibre-based map view component that provides enhanced functionality for map display,
 * location tracking, polygon drawing, layer management, and tile downloading/conversion.
 *
 * @param context The context in which the view is created
 * @param attrs The attributes of the XML tag that is inflating the view
 */
class CGMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs), AzimuthListener, LocationListener {

    private var mapView: MapView? = null
    private var mapLibreMap: MapLibreMap? = null
    private var isStyleLoaded = false
    private val pendingLayers = mutableListOf<() -> Unit>()
    private var locationSensorManager: LocationSensorManager
    private var isDragging = false
    private var lastDragPoint: LatLng? = null
    private var currentPolygonId: String? = null
    private var currentSourceId: String? = null
    private var panEnabled = false
    private var vertexEnabled = false
    private var allowedSourceId: String? = null
    private var allowedLayerId: String? = null
    private var allowedPolygonIds: List<String>? = null
    private val polygonFeatures = mutableMapOf<String, MutableMap<String, Feature>>()
    private val _locationLiveData = MutableLiveData<CGLatLng>()
    val locationLiveData: LiveData<CGLatLng> get() = _locationLiveData
    private var dragProperties: FillProperties? = null
    private var oriPolygonProperties: FillProperties? = null
    private var vertexProperties: VertexProperties? = null
    private var isDraggingVertex = false
    private var currentVertexIndex: Int = -1
    private var currentVertexType: VertexType = VertexType.NONE
    private var vertexSourceId = "vertex-source"
    private var vertexLayerId = "vertex-layer"
    private var onFeatureUpdateListener: ((CGFeature) -> Unit)? = null
    private val tileDownloader = TileDownloader(context)
    private val mbtConverter = MBTilesConverter()
    private var pendingConversion: Pair<String, String>? = null
    private var isDownloading = false
    private var isConverting = false
    private var downloadObserver: ((TileDownloader.DownloadProgress) -> Unit)? = null
    private var conversionObserver: ((MBTilesConverter.ConversionProgress) -> Unit)? = null
    private val _target = MutableLiveData<CGLatLng>()
    val target: LiveData<CGLatLng> get() = _target
    private val overlayLayers = mutableListOf<CGOverlayLayer>()
    private var transitionAnimator: ValueAnimator? = null
    private var currentAzimuth: Double = 0.0

    init {
        Logger.addLogAdapter(AndroidLogAdapter())
        MapLibre.getInstance(context)
        mapView = MapView(context)
        addView(mapView)
        initializeMap()
        locationSensorManager = LocationSensorManager(context, this, this)
        locationSensorManager.registerSensors()
        downloadObserver = { progress ->
            Log.d("CGMapView", "Download progress: ${progress.status}")
            when (progress.status) {
                TileDownloader.DownloadProgress.Status.COMPLETED -> {
                    isDownloading = false
                    Logger.d(pendingConversion)
                    pendingConversion?.let { (xyzDir, outputPath) ->
                        isConverting = true
                        Logger.d("Starting conversion: $xyzDir -> $outputPath")
                        mbtConverter.startConversion(xyzDir, outputPath)
                        pendingConversion = null
                    }
                }

                TileDownloader.DownloadProgress.Status.ERROR,
                TileDownloader.DownloadProgress.Status.CANCELLED -> {
                    isDownloading = false
                    pendingConversion = null
                }

                TileDownloader.DownloadProgress.Status.RUNNING -> {
                    isDownloading = true
                }
            }
        }

        conversionObserver = { progress ->
            Log.d("CGMapView", "Conversion progress: ${progress.status}")
            when (progress.status) {
                MBTilesConverter.ConversionProgress.Status.COMPLETED -> {
                    isConverting = false
                }

                MBTilesConverter.ConversionProgress.Status.ERROR,
                MBTilesConverter.ConversionProgress.Status.CANCELLED -> {
                    isConverting = false
                }

                MBTilesConverter.ConversionProgress.Status.RUNNING -> {
                    isConverting = true
                }
            }
        }

        tileDownloader.downloadProgress.observeForever(downloadObserver!!)
        mbtConverter.conversionProgress.observeForever(conversionObserver!!)
    }

    /**
     * Initializes the map view and sets up the basic map configuration.
     * This includes setting up the map style, camera listeners, and long-press handlers.
     */
    private fun initializeMap() {
        mapView?.getMapAsync { map ->
            mapLibreMap = map

            map.setStyle("https://demotiles.maplibre.org/style.json") {
                isStyleLoaded = true
                pendingLayers.forEach { it.invoke() }
                pendingLayers.clear()
            }

            map.addOnCameraMoveListener {
                DrawUtil.updateTemp()
            }

            map.addOnMapLongClickListener { point ->
                _target.postValue(CGLatLng(point.latitude, point.longitude))
                false
            }
        }
    }

    /**
     * Adds an accuracy circle to the map to show the location accuracy radius.
     *
     * @param style The map style to add the circle to
     * @param sourceId The ID for the GeoJSON source
     * @param fillLayerId The ID for the fill layer
     * @param lineLayerId The ID for the line layer
     * @param position The center position of the circle
     * @param accuracy The accuracy radius in meters
     */
    private fun addAccuracyCircle(style: Style, sourceId: String, fillLayerId: String, lineLayerId: String, position: LatLng, accuracy: Double) {
        var geoJsonSource = style.getSourceAs<GeoJsonSource>(sourceId)
        if (geoJsonSource == null) {
            geoJsonSource = GeoJsonSource(sourceId, FeatureCollection.fromFeatures(emptyArray()))
            style.addSource(geoJsonSource)
        }

        val accuracyCircle = generateCirclePolygon(position, accuracy)
        geoJsonSource.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(accuracyCircle)))
        style.removeLayer(fillLayerId)
        style.removeLayer(lineLayerId)

        val fillLayer = FillLayer(fillLayerId, sourceId).withProperties(
            PropertyFactory.fillColor("#2196F3"),
            PropertyFactory.fillOpacity(0.1f)
        )
        style.addLayer(fillLayer)

        val lineLayer = LineLayer(lineLayerId, sourceId).withProperties(
            PropertyFactory.lineColor("#2196F3"),
            PropertyFactory.lineWidth(0.5f)
        )
        style.addLayerAbove(lineLayer, fillLayerId)
    }

    /**
     * Generates a polygon representing a circle on the map.
     *
     * @param center The center point of the circle
     * @param radiusMeters The radius of the circle in meters
     * @param numPoints The number of points to use to approximate the circle
     * @return A polygon representing the circle
     */
    private fun generateCirclePolygon(center: LatLng, radiusMeters: Double, numPoints: Int = 64): Polygon {
        val coordinates = mutableListOf<Point>()
        val earthRadius = 6431000.0

        for (i in 0 until numPoints) {
            val angle = i * (360.0 / numPoints)
            val radian = Math.toRadians(angle)

            val deltaLat = (radiusMeters / earthRadius) * Math.cos(radian)
            val deltaLng = (radiusMeters / (earthRadius * Math.cos(Math.toRadians(center.latitude)))) * Math.sin(radian)

            val newLat = center.latitude + Math.toDegrees(deltaLat)
            val newLng = center.longitude + Math.toDegrees(deltaLng)

            coordinates.add(Point.fromLngLat(newLng, newLat))
        }

        coordinates.add(coordinates[0])
        return Polygon.fromLngLats(listOf(coordinates))
    }

    /**
     * Brings the location-related layers to the front of the map.
     * This ensures that the location marker and accuracy circle are always visible.
     *
     * @param style The map style to modify
     */
    private fun bringLocationToFront(style: Style) {
        style.removeLayer("accuracy-line-layer")
        style.removeLayer("accuracy-fill-layer")
        style.removeLayer("marker-layer")

        style.addLayer(
            FillLayer("accuracy-fill-layer", "accuracy-source").withProperties(
                PropertyFactory.fillColor("#2196F3"),
                PropertyFactory.fillOpacity(0.1f)
            )
        )

        style.addLayer(
            LineLayer("accuracy-line-layer", "accuracy-source").withProperties(
                PropertyFactory.lineColor("#2196F3"),
                PropertyFactory.lineWidth(0.5f)
            )
        )

        style.addLayer(
            SymbolLayer("marker-layer", "marker-source").withProperties(
                PropertyFactory.iconImage("marker-icon"),
                PropertyFactory.iconSize(scaleFactor),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconRotate(currentAzimuth.toFloat())
            )
        )
    }

    /**
     * Adds a raster layer to the map.
     *
     * @param cgRasterLayer The raster layer configuration to add
     */
    fun addRasterLayer(cgRasterLayer: CGRasterLayer) {
        if (!isStyleLoaded) {
            pendingLayers.add { addRasterLayer(cgRasterLayer) }
            return
        }

        val style = mapLibreMap?.style ?: return

        if (style.getSource(cgRasterLayer.sourceId) == null) {
            val rasterSource = RasterSource(cgRasterLayer.sourceId, TileSet("2.0", cgRasterLayer.tileUrl).apply {
                scheme = cgRasterLayer.scheme
            })
            style.addSource(rasterSource)

            if (cgRasterLayer.callout) {
                val calloutSource = RasterSource("callout_source", TileSet("2.0", callout).apply {
                    scheme = "xyz"
                })
                style.addSource(calloutSource)
            }
        }

        if (style.getLayer(cgRasterLayer.layerId) == null) {
            val rasterLayer = RasterLayer(cgRasterLayer.layerId, cgRasterLayer.sourceId)
            style.addLayer(rasterLayer)
        }

        if (cgRasterLayer.callout) {
            if (style.getLayer("callout_layer") == null) {
                val calloutLayer = RasterLayer("callout_layer", "callout_source")
                style.addLayer(calloutLayer)
            }
        }
        bringLocationToFront(style)
    }

    /**
     * Removes a raster layer from the map.
     *
     * @param rasterLayer The raster layer to remove
     */
    fun removeRasterLayer(rasterLayer: CGRasterLayer) {
        val style = mapLibreMap?.style ?: return

        if (style.getLayer(rasterLayer.layerId) != null) {
            style.removeLayer(rasterLayer.layerId)
        }

        if (style.getSource(rasterLayer.sourceId) != null) {
            style.removeSource(rasterLayer.sourceId)
        }

        if (style.getLayer("callout_layer") != null) {
            style.removeLayer("callout_layer")
        }

        if (style.getSource("callout_source") != null) {
            style.removeSource("callout_source")
        }
    }

    /**
     * Makes a raster layer visible on the map.
     *
     * @param rasterLayer The raster layer to show
     */
    fun showRasterLayer(rasterLayer: CGRasterLayer) {
        val style = mapLibreMap?.style ?: return
        val layer = style.getLayer(rasterLayer.layerId) ?: return
        layer.setProperties(PropertyFactory.visibility(Property.VISIBLE))

        val callout = style.getLayer("callout_layer") ?: return
        callout.setProperties(PropertyFactory.visibility(Property.VISIBLE))
    }

    /**
     * Hides a raster layer on the map.
     *
     * @param rasterLayer The raster layer to hide
     */
    fun hideRasterLayer(rasterLayer: CGRasterLayer) {
        val style = mapLibreMap?.style ?: return
        val layer = style.getLayer(rasterLayer.layerId) ?: return
        layer.setProperties(PropertyFactory.visibility(Property.NONE))

        val callout = style.getLayer("callout_layer") ?: return
        callout.setProperties(PropertyFactory.visibility(Property.NONE))
    }

    var scaleFactor: Float = 0F

    /**
     * Adds a location marker to the map.
     *
     * @param style The map style to add the marker to
     * @param imageId The ID for the marker image
     * @param sourceId The ID for the GeoJSON source
     * @param layerId The ID for the symbol layer
     * @param position The position where to place the marker
     * @param imageRes The resource ID of the marker image
     */
    fun addLocationMarker(style: Style, imageId: String, sourceId: String, layerId: String, position: LatLng, imageRes: Int) {
        val bitmap: Bitmap = BitmapFactory.decodeResource(context.resources, imageRes)
        val displayMetrics = context.resources.displayMetrics
        val targetSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40F, displayMetrics)
        val originalSize = bitmap.width.coerceAtLeast(bitmap.height).toFloat()
        scaleFactor = targetSizePx / originalSize

        if (style.getImage(imageId) == null) {
            style.addImage(imageId, bitmap)
        }

        var geoJsonSource = style.getSourceAs<GeoJsonSource>(sourceId)
        if (geoJsonSource == null) {
            geoJsonSource = GeoJsonSource(sourceId, FeatureCollection.fromFeatures(emptyArray()))
            style.addSource(geoJsonSource)
        }

        geoJsonSource.setGeoJson(
            FeatureCollection.fromFeatures(
                arrayOf(Feature.fromGeometry(Point.fromLngLat(position.longitude, position.latitude)))
            )
        )
        style.removeLayer(layerId)

        val symbolLayer = SymbolLayer(layerId, sourceId).withProperties(
            PropertyFactory.iconImage(imageId),
            PropertyFactory.iconSize(scaleFactor),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAllowOverlap(true)
        )
        style.addLayer(symbolLayer)
    }

    fun onCreate(savedInstanceState: Bundle?) = mapView?.onCreate(savedInstanceState)
    fun onStart() = mapView?.onStart()
    fun onResume() {
        mapView?.onResume()
        locationSensorManager.registerSensors()
    }

    fun onPause() {
        mapView?.onPause()
        locationSensorManager.unregisterSensors()
    }

    fun onStop() = mapView?.onStop()

    fun onDestroy() {
        mapView?.onDestroy()
        locationSensorManager.stopLocationUpdates()

        downloadObserver?.let { observer ->
            tileDownloader.downloadProgress.removeObserver(observer)
        }
        conversionObserver?.let { observer ->
            mbtConverter.conversionProgress.removeObserver(observer)
        }

        stopTransitionAnimation()
    }

    fun onSaveInstanceState(outState: Bundle) = mapView?.onSaveInstanceState(outState)

    fun getLocation() {
        locationSensorManager.locate()
    }

    override fun onAzimuthChanged(azimuth: Double) {
        currentAzimuth = azimuth
        val style = mapLibreMap?.style ?: return
        val layer = style.getLayer("marker-layer") as? SymbolLayer ?: return

        layer.setProperties(
            PropertyFactory.iconRotate(azimuth.toFloat())
        )
    }

    var myLocation: CGLatLng = CGLatLng(0.0, 0.0)

    override fun onLocationChanged(latLng: Map<String, Any?>) {
        val style = mapLibreMap?.style ?: return
        val longitude = latLng["longitude"].toString().toDouble()
        val latitude = latLng["latitude"].toString().toDouble()
        val accuracy = latLng["accuracy"].toString().toDouble()
        myLocation = CGLatLng(latitude, longitude)
        _locationLiveData.postValue(myLocation)
        addAccuracyCircle(
            style = style,
            sourceId = "accuracy-source",
            fillLayerId = "accuracy-fill-layer",
            lineLayerId = "accuracy-line-layer",
            position = LatLng(latitude, longitude),
            accuracy = accuracy
        )

        addLocationMarker(
            style = style,
            imageId = "marker-icon",
            sourceId = "marker-source",
            layerId = "marker-layer",
            position = LatLng(latitude, longitude),
            imageRes = R.drawable.ic_puck
        )
        bringLocationToFront(style)
    }

    fun addSpot(properties: DrawProperties) = DrawUtil.addSpot(mapLibreMap!!, properties)
    fun recallSpot() = DrawUtil.recallSpot()
    fun redoSpot() = DrawUtil.redoSpot()
    fun doneSpot(): CGFeature = DrawUtil.doneSpot()
    fun measureAdd(properties: DrawProperties) = DrawUtil.measureDistance(mapLibreMap!!, properties)
    fun measureRecall() = DrawUtil.recallSpot()
    fun measureRedo() = DrawUtil.redoSpot()
    fun measureDone(): Double = DrawUtil.measureDone()

    fun pan(panFlag: Boolean, sourceId: String, layerId: String, properties: FillProperties, polygonIds: List<String>? = null, onUpdate: ((CGFeature) -> Unit)? = null) {
        panEnabled = panFlag
        allowedSourceId = if (panFlag) sourceId else null
        allowedLayerId = if (panFlag) layerId else null
        allowedPolygonIds = if (panFlag) polygonIds else null
        dragProperties = if (panFlag) properties else null
        onFeatureUpdateListener = onUpdate

        val style = mapLibreMap?.style ?: return
        if (panFlag) {
            setupDragListener()
            bringLocationToFront(style)
        }
    }

    fun vertex(vertexFlag: Boolean, sourceId: String, layerId: String, vertexProperties: VertexProperties, polygonIds: List<String>? = null, onUpdate: ((CGFeature) -> Unit)? = null) {
        vertexEnabled = vertexFlag
        allowedSourceId = if (vertexFlag) sourceId else null
        allowedLayerId = if (vertexFlag) layerId else null
        allowedPolygonIds = if (vertexFlag) polygonIds else null
        this.vertexProperties = if (vertexFlag) vertexProperties else null
        onFeatureUpdateListener = onUpdate

        val style = mapLibreMap?.style ?: return
        if (vertexFlag) {
            Logger.d("Setting up vertex mode: $sourceId, $polygonIds")
            setupVertexLayer()
            setupVertexDragListener()
            polygonIds?.firstOrNull()?.let { polygonId ->
                Logger.d("Updating vertex layer for polygon: $polygonId")
                currentPolygonId = polygonId
                updateVertexLayer(polygonId, sourceId)
            } ?: run {
                Logger.e("No polygon ID provided")
            }
        } else {
            Logger.d("Cleaning up vertex mode")
            style.removeLayer(vertexLayerId)
            style.removeSource(vertexSourceId)
            currentPolygonId = null
        }
    }

    fun tempPolygonAdd(fillProperties: FillProperties) {
        oriPolygonProperties = fillProperties
        addPolygon(geojson, "source-1", "layer-1")
        addPolygon(geojson2, "source-2", "layer-2")
    }

    fun addPolygon(geojson: String, sourceId: String, layerId: String) {
        val style = mapLibreMap?.style ?: return
        val featureCollection = FeatureCollection.fromJson(geojson)

        featureCollection.features()?.forEach { feature ->
            feature.addStringProperty("fill-color", oriPolygonProperties?.fillColor)
            feature.addStringProperty("line-color", oriPolygonProperties?.lineColor)
            feature.addNumberProperty("line-width", oriPolygonProperties?.lineWidth)
            feature.addNumberProperty("fill-opacity", oriPolygonProperties?.fillOpacity)
        }

        var geoJsonSource = style.getSourceAs<GeoJsonSource>(sourceId)
        if (geoJsonSource == null) {
            geoJsonSource = GeoJsonSource(sourceId, featureCollection)
            style.addSource(geoJsonSource)
        } else {
            geoJsonSource.setGeoJson(featureCollection)
        }

        val outlineLayerId = "$layerId-outline"
        if (style.getLayer(layerId) == null) {
            val fillLayer = FillLayer(layerId, sourceId)
                .withProperties(
                    PropertyFactory.fillColor(Expression.get("fill-color")),
                    PropertyFactory.fillOpacity(Expression.get("fill-opacity")),
                    PropertyFactory.visibility(Property.VISIBLE)
                )
            style.addLayer(fillLayer)
        }

        if (style.getLayer(outlineLayerId) == null) {
            val outlineLayer = LineLayer(outlineLayerId, sourceId)
                .withProperties(
                    PropertyFactory.lineColor(Expression.get("line-color")),
                    PropertyFactory.lineWidth(Expression.get("line-width")),
                    PropertyFactory.visibility(Property.VISIBLE)
                )
            style.addLayer(outlineLayer)
        }

        if (!polygonFeatures.containsKey(sourceId)) {
            polygonFeatures[sourceId] = mutableMapOf()
        }

        featureCollection.features()?.forEach { feature ->
            val polygonId = feature.getStringProperty("id")
            if (polygonId != null) {
                polygonFeatures[sourceId]?.put(polygonId, feature)
            }
        }
    }

    /**
     * Checks if a point is inside a polygon.
     *
     * @param point The point to check
     * @param polygonId The ID of the polygon
     * @param sourceId The ID of the source containing the polygon
     * @return true if the point is inside the polygon, false otherwise
     */
    private fun isPointInPolygon(point: LatLng, polygonId: String, sourceId: String): Boolean {
        val feature = polygonFeatures[sourceId]?.get(polygonId) ?: return false
        val geometry = feature.geometry() as? Polygon ?: return false

        val coordinates = geometry.coordinates()[0]
        val minLat = coordinates.minOf { it.latitude() }
        val maxLat = coordinates.maxOf { it.latitude() }
        val minLng = coordinates.minOf { it.longitude() }
        val maxLng = coordinates.maxOf { it.longitude() }

        if (point.latitude < minLat || point.latitude > maxLat ||
            point.longitude < minLng || point.longitude > maxLng
        ) {
            return false
        }

        var inside = false
        for (i in 0 until coordinates.size - 1) {
            val j = (i + 1) % coordinates.size
            if ((coordinates[i].latitude() > point.latitude) != (coordinates[j].latitude() > point.latitude) &&
                point.longitude < (coordinates[j].longitude() - coordinates[i].longitude()) * (point.latitude - coordinates[i].latitude()) /
                (coordinates[j].latitude() - coordinates[i].latitude()) + coordinates[i].longitude()
            ) {
                inside = !inside
            }
        }
        return inside
    }

    /**
     * Sets up the drag listener for polygon manipulation.
     * This allows users to drag polygons on the map.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener() {
        mapLibreMap?.addOnMapClickListener { point ->
            if (currentPolygonId != null && currentSourceId != null) {
                isPointInPolygon(point, currentPolygonId!!, currentSourceId!!)
            } else {
                false
            }
        }

        mapLibreMap?.addOnMapLongClickListener { point ->
            if (panEnabled) {
                for ((sourceId, features) in polygonFeatures) {
                    for ((polygonId, _) in features) {
                        if (isPointInPolygon(point, polygonId, sourceId)) {
                            if (sourceId == allowedSourceId &&
                                polygonId in (allowedPolygonIds ?: emptyList())
                            ) {
                                isDragging = true
                                lastDragPoint = point
                                currentPolygonId = polygonId
                                currentSourceId = sourceId
                                updatePolygonColor(polygonId, true, sourceId)
                                return@addOnMapLongClickListener true
                            }
                        }
                    }
                }
                false
            } else {
                _target.postValue(CGLatLng(point.latitude, point.longitude))
                true
            }
        }

        mapView?.setOnTouchListener { _, event ->
            if (isDragging) {
                when (event.action) {
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val point = mapLibreMap?.projection?.fromScreenLocation(
                            android.graphics.PointF(event.x, event.y)
                        )
                        point?.let { newPoint ->
                            lastDragPoint?.let { lastPoint ->
                                val latDiff = newPoint.latitude - lastPoint.latitude
                                val lngDiff = newPoint.longitude - lastPoint.longitude

                                currentPolygonId?.let { polygonId ->
                                    currentSourceId?.let { sourceId ->
                                        movePolygon(polygonId, latDiff, lngDiff, sourceId)
                                    }
                                }
                                lastDragPoint = newPoint
                            }
                        }
                        true
                    }

                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                        lastDragPoint = null
                        currentPolygonId?.let { polygonId ->
                            currentSourceId?.let { sourceId ->
                                updatePolygonColor(polygonId, false, sourceId)
                            }
                        }
                        currentPolygonId = null
                        currentSourceId = null
                        true
                    }

                    else -> false
                }
            } else {
                false
            }
        }
    }

    /**
     * Moves a polygon on the map by a specified offset.
     *
     * @param polygonId The ID of the polygon to move
     * @param latDiff The latitude difference to move by
     * @param lngDiff The longitude difference to move by
     * @param sourceId The ID of the source containing the polygon
     */
    private fun movePolygon(polygonId: String, latDiff: Double, lngDiff: Double, sourceId: String) {
        val feature = polygonFeatures[sourceId]?.get(polygonId) ?: return
        val geometry = feature.geometry() as? Polygon ?: return

        val newCoordinates = geometry.coordinates().map { ring ->
            ring.map { point ->
                Point.fromLngLat(
                    point.longitude() + lngDiff,
                    point.latitude() + latDiff
                )
            }
        }

        val newPolygon = Polygon.fromLngLats(newCoordinates)
        val newFeature = Feature.fromGeometry(newPolygon)

        val properties = feature.properties()
        if (properties != null) {
            properties.keySet().forEach { key ->
                val value = properties.get(key)
                try {
                    val strValue = value.asString
                    newFeature.addStringProperty(key, strValue)
                } catch (e: Exception) {
                    try {
                        val numValue = value.asDouble
                        newFeature.addNumberProperty(key, numValue)
                    } catch (e: Exception) {
                    }
                }
            }
        }

        polygonFeatures[sourceId]?.put(polygonId, newFeature)

        val style = mapLibreMap?.style ?: return
        val geoJsonSource = style.getSourceAs<GeoJsonSource>(sourceId)
        geoJsonSource?.setGeoJson(FeatureCollection.fromFeatures(polygonFeatures[sourceId]?.values?.toList() ?: emptyList()))

        notifyFeatureUpdate(newFeature)
    }

    /**
     * Updates the color of a polygon based on whether it's being dragged.
     *
     * @param polygonId The ID of the polygon to update
     * @param isDragging Whether the polygon is currently being dragged
     * @param sourceId The ID of the source containing the polygon
     */
    private fun updatePolygonColor(polygonId: String, isDragging: Boolean, sourceId: String) {
        val feature = polygonFeatures[sourceId]?.get(polygonId) ?: return
        val newFeature = Feature.fromGeometry(feature.geometry())

        feature.getStringProperty("id")?.let { newFeature.addStringProperty("id", it) }

        if (isDragging) {
            dragProperties?.let { props ->
                newFeature.addStringProperty("fill-color", dragProperties?.fillColor)
                newFeature.addStringProperty("line-color", dragProperties?.lineColor)
                newFeature.addNumberProperty("line-width", dragProperties?.lineWidth)
                newFeature.addNumberProperty("fill-opacity", dragProperties?.fillOpacity)
            } ?: run {
                newFeature.addStringProperty("fill-color", primaryYellow)
                newFeature.addNumberProperty("line-width", 2.0)
                newFeature.addStringProperty("line-color", primaryYellowDark)
                newFeature.addNumberProperty("fill-opacity", 0.4)
            }
        } else {
            oriPolygonProperties?.let { props ->
                newFeature.addStringProperty("fill-color", props.fillColor)
                newFeature.addStringProperty("line-color", props.lineColor)
                newFeature.addNumberProperty("line-width", props.lineWidth.toDouble())
                newFeature.addNumberProperty("fill-opacity", props.fillOpacity.toDouble())
            }
        }

        polygonFeatures[sourceId]?.put(polygonId, newFeature)

        val style = mapLibreMap?.style ?: return
        val geoJsonSource = style.getSourceAs<GeoJsonSource>(sourceId)
        geoJsonSource?.setGeoJson(FeatureCollection.fromFeatures(polygonFeatures[sourceId]?.values?.toList() ?: emptyList()))

        val layerId = allowedLayerId ?: return
        val outlineLayerId = "$layerId-outline"

        style.getLayer(layerId)?.let { layer ->
            layer.setProperties(
                PropertyFactory.fillColor(Expression.get("fill-color")),
                PropertyFactory.fillOpacity(Expression.get("fill-opacity"))
            )
        }

        style.getLayer(outlineLayerId)?.let { layer ->
            layer.setProperties(
                PropertyFactory.lineColor(Expression.get("line-color")),
                PropertyFactory.lineWidth(Expression.get("line-width"))
            )
        }

        if (!isDragging) {
            notifyFeatureUpdate(newFeature)
        }
    }

    /**
     * Flies the camera to a specified location with a given zoom level.
     *
     * @param cgLatLng The target location
     * @param zoom The target zoom level
     */
    fun flyTo(cgLatLng: CGLatLng, zoom: Double) {
        Log.d("TAG", cgLatLng.toFormattedString())
        mapLibreMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                cgLatLng.toLatLng(),
                zoom
            ),
        )
    }

    /**
     * Sets up the vertex layer for polygon vertex manipulation.
     * This includes creating layers for vertex points and midpoints.
     */
    private fun setupVertexLayer() {
        val style = mapLibreMap?.style ?: return
        Logger.d("Setting up vertex layer")

        style.removeLayer(vertexLayerId)
        style.removeSource(vertexSourceId)

        style.addSource(GeoJsonSource(vertexSourceId))
        Logger.d("Added vertex source")

        val outlineLayerId = "$allowedLayerId-outline"

        val vertexTouchLayer = CircleLayer("$vertexLayerId-vertex-touch", vertexSourceId)
            .withFilter(Expression.eq(Expression.get("type"), Expression.literal("vertex")))
            .withProperties(
                PropertyFactory.circleRadius(20f),
                PropertyFactory.circleColor("rgba(0, 0, 0, 0)"),
                PropertyFactory.circleStrokeWidth(0f)
            )

        val midpointTouchLayer = CircleLayer("$vertexLayerId-midpoint-touch", vertexSourceId)
            .withFilter(Expression.eq(Expression.get("type"), Expression.literal("midpoint")))
            .withProperties(
                PropertyFactory.circleRadius(20f),
                PropertyFactory.circleColor("rgba(0, 0, 0, 0)"),
                PropertyFactory.circleStrokeWidth(0f)
            )

        val vertexCircleLayer = CircleLayer("$vertexLayerId-vertex", vertexSourceId)
            .withFilter(Expression.eq(Expression.get("type"), Expression.literal("vertex")))
            .withProperties(
                PropertyFactory.circleRadius(vertexProperties?.circleRadius ?: 8f),
                PropertyFactory.circleColor(vertexProperties?.circleColor ?: "#FFFFFF"),
                PropertyFactory.circleStrokeWidth(vertexProperties?.circleStrokeWidth ?: 2f),
                PropertyFactory.circleStrokeColor(vertexProperties?.circleStrokeColor ?: primaryYellowDark)
            )

        val midpointCircleLayer = CircleLayer("$vertexLayerId-midpoint", vertexSourceId)
            .withFilter(Expression.eq(Expression.get("type"), Expression.literal("midpoint")))
            .withProperties(
                PropertyFactory.circleRadius(vertexProperties?.midCircleRadius ?: 8f),
                PropertyFactory.circleColor(vertexProperties?.midCircleColor ?: "#FFFFFF"),
                PropertyFactory.circleStrokeWidth(vertexProperties?.midCircleStrokeWidth ?: 2f),
                PropertyFactory.circleStrokeColor(vertexProperties?.midCircleStrokeColor ?: primaryYellowDark)
            )

        style.addLayerAbove(vertexTouchLayer, outlineLayerId)
        style.addLayerAbove(midpointTouchLayer, "$vertexLayerId-vertex-touch")
        style.addLayerAbove(vertexCircleLayer, "$vertexLayerId-midpoint-touch")
        style.addLayerAbove(midpointCircleLayer, "$vertexLayerId-vertex")

        Logger.d("Added vertex layers above $outlineLayerId")
    }

    /**
     * Sets up the vertex drag listener for polygon vertex manipulation.
     * This allows users to drag vertices and midpoints of polygons.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupVertexDragListener() {
        mapView?.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val point = mapLibreMap?.projection?.fromScreenLocation(
                        android.graphics.PointF(event.x, event.y)
                    ) ?: return@setOnTouchListener false

                    val vertexFeatures = mapLibreMap?.queryRenderedFeatures(
                        mapLibreMap?.projection?.toScreenLocation(point) ?: return@setOnTouchListener false,
                        "$vertexLayerId-vertex-touch"
                    ) ?: emptyList<Feature>()

                    val midpointFeatures = mapLibreMap?.queryRenderedFeatures(
                        mapLibreMap?.projection?.toScreenLocation(point) ?: return@setOnTouchListener false,
                        "$vertexLayerId-midpoint-touch"
                    ) ?: emptyList<Feature>()

                    if (vertexFeatures.size > 0 && vertexEnabled) {
                        val feature = vertexFeatures[0]
                        currentVertexIndex = feature.getNumberProperty("index")?.toInt() ?: -1
                        currentVertexType = VertexType.VERTEX
                        isDraggingVertex = true
                        true
                    } else if (midpointFeatures.size > 0 && vertexEnabled) {
                        val feature = midpointFeatures[0]
                        currentVertexIndex = feature.getNumberProperty("index")?.toInt() ?: -1
                        currentVertexType = VertexType.MIDPOINT
                        isDraggingVertex = true
                        true
                    } else {
                        false
                    }
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    if (isDraggingVertex) {
                        val point = mapLibreMap?.projection?.fromScreenLocation(
                            android.graphics.PointF(event.x, event.y)
                        )
                        point?.let { moveVertex(it) }
                        true
                    } else {
                        false
                    }
                }

                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingVertex) {
                        isDraggingVertex = false
                        currentVertexIndex = -1
                        currentVertexType = VertexType.NONE
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    /**
     * Updates the vertex layer for a specific polygon.
     *
     * @param polygonId The ID of the polygon
     * @param sourceId The ID of the source containing the polygon
     */
    private fun updateVertexLayer(polygonId: String, sourceId: String) {
        Logger.d("Updating vertex layer for polygon: $polygonId, source: $sourceId")
        val feature = polygonFeatures[sourceId]?.get(polygonId)
        if (feature == null) {
            Logger.e("Feature not found for polygon: $polygonId")
            return
        }
        val geometry = feature.geometry() as? Polygon
        if (geometry == null) {
            Logger.e("Geometry is not a polygon")
            return
        }
        val coordinates = geometry.coordinates()[0]

        val features = mutableListOf<Feature>()

        coordinates.forEachIndexed { index, point ->
            if (index < coordinates.size - 1) {
                val vertexFeature = Feature.fromGeometry(point)
                vertexFeature.addStringProperty("type", "vertex")
                vertexFeature.addNumberProperty("index", index)
                features.add(vertexFeature)

                val nextPoint = coordinates[(index + 1) % (coordinates.size - 1)]
                val midPoint = Point.fromLngLat(
                    (point.longitude() + nextPoint.longitude()) / 2,
                    (point.latitude() + nextPoint.latitude()) / 2
                )
                val midpointFeature = Feature.fromGeometry(midPoint)
                midpointFeature.addStringProperty("type", "midpoint")
                midpointFeature.addNumberProperty("index", index)
                features.add(midpointFeature)
            }
        }

        val style = mapLibreMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(vertexSourceId)
        source?.setGeoJson(FeatureCollection.fromFeatures(features))
        Logger.d("Updated vertex layer with ${features.size} features")
    }

    /**
     * Moves a vertex of a polygon to a new position.
     *
     * @param newPoint The new position for the vertex
     */
    private fun moveVertex(newPoint: LatLng) {
        val feature = polygonFeatures[allowedSourceId]?.get(currentPolygonId) ?: return
        val geometry = feature.geometry() as? Polygon ?: return
        val coordinates = geometry.coordinates()[0].toMutableList()

        when (currentVertexType) {
            VertexType.VERTEX -> {
                coordinates[currentVertexIndex] = Point.fromLngLat(newPoint.longitude, newPoint.latitude)
                if (currentVertexIndex == 0) {
                    coordinates[coordinates.size - 1] = coordinates[0]
                }
            }

            VertexType.MIDPOINT -> {
                val newVertex = Point.fromLngLat(newPoint.longitude, newPoint.latitude)
                coordinates.add(currentVertexIndex + 1, newVertex)
                currentVertexType = VertexType.VERTEX
                currentVertexIndex += 1
            }

            else -> return
        }

        val newPolygon = Polygon.fromLngLats(listOf(coordinates))
        val newFeature = Feature.fromGeometry(newPolygon)

        val properties = feature.properties()
        if (properties != null) {
            properties.keySet().forEach { key ->
                val value = properties.get(key)
                try {
                    val strValue = value.asString
                    newFeature.addStringProperty(key, strValue)
                } catch (e: Exception) {
                    try {
                        val numValue = value.asDouble
                        newFeature.addNumberProperty(key, numValue)
                    } catch (e: Exception) {
                    }
                }
            }
        }

        allowedSourceId?.let { sourceId ->
            polygonFeatures[sourceId]?.put(currentPolygonId!!, newFeature)
            val style = mapLibreMap?.style ?: return
            val source = style.getSourceAs<GeoJsonSource>(sourceId)
            source?.setGeoJson(FeatureCollection.fromFeatures(polygonFeatures[sourceId]?.values?.toList() ?: emptyList()))

            updateVertexLayer(currentPolygonId!!, sourceId)

            notifyFeatureUpdate(newFeature)
        }
    }

    /**
     * Notifies listeners about feature updates.
     *
     * @param feature The updated feature
     */
    private fun notifyFeatureUpdate(feature: Feature) {
        Logger.d("Original Feature: ${feature.toJson()}")

        val geometry = feature.geometry() as? Polygon ?: return
        val props = feature.properties() ?: return
        val distance = GeoCalculator.measurePerimeter(geometry.coordinates()[0])
        val area = GeoCalculator.measureArea(geometry.coordinates()[0])
        val cgFeature = CGFeature(
            polygon = geometry,
            area = area,
            distance = distance
        )

        props.keySet().forEach { key ->
            if (key != "fill-color" && key != "line-color" && key != "line-width" && key != "fill-opacity") {
                val value = props.get(key)
                val strValue: String = when {
                    value.isJsonNull -> "null"
                    value.isJsonPrimitive -> {
                        val primitive = value.asJsonPrimitive
                        when {
                            primitive.isString -> primitive.asString
                            primitive.isNumber -> primitive.asNumber.toString()
                            primitive.isBoolean -> primitive.asBoolean.toString()
                            else -> value.toString()
                        }
                    }

                    else -> value.toString()
                }
                cgFeature.addExtra(mapOf(key to strValue))
            }
        }

        Logger.d("Converted CGFeature: ${cgFeature.toGeoJSON()}")
        onFeatureUpdateListener?.invoke(cgFeature)
    }

    /**
     * Downloads map tiles for a specified area and converts them to MBTiles format.
     *
     * @param coordinates The coordinates defining the area to download
     * @param tileUrl The URL template for the tile server
     * @param savePath The path where to save the downloaded tiles
     * @param minZoom The minimum zoom level to download
     * @param maxZoom The maximum zoom level to download
     * @param tileScheme The tile URL scheme (e.g., "xyz")
     */
    fun download(coordinates: List<List<List<Double>>>, tileUrl: String, savePath: String, minZoom: Int, maxZoom: Int, tileScheme: String) {
        val fullPath = getDefaultTilePath(savePath)
        pendingConversion = Pair(fullPath, "$fullPath/$savePath.mbtiles")
        isDownloading = true
        tileDownloader.download(coordinates, tileUrl, fullPath, minZoom, maxZoom, tileScheme)
    }

    private fun getDefaultTilePath(s: String): String {
        return context.getExternalFilesDir(s)?.absolutePath ?: throw IllegalStateException("Unable to get external storage directory")
    }

    /**
     * Stops the current tile download operation.
     */
    fun stopDownload() {
        if (isDownloading) {
            tileDownloader.cancel()
            pendingConversion = null
            isDownloading = false
        }
    }

    /**
     * Stops the current tile conversion operation.
     */
    fun stopConversion() {
        if (isConverting) {
            mbtConverter.cancelConversion()
            isConverting = false
        }
    }

    /**
     * Stops all ongoing operations (download and conversion).
     */
    fun stopAll() {
        if (isDownloading) {
            stopDownload()
        }
        if (isConverting) {
            stopConversion()
        }
    }

    /**
     * Checks if there are any ongoing operations.
     *
     * @return true if there are ongoing operations, false otherwise
     */
    fun isProcessing(): Boolean {
        return isDownloading || isConverting
    }

    /**
     * Gets the current processing status.
     *
     * @return A string describing the current status
     */
    fun getProcessingStatus(): String {
        return when {
            isDownloading -> "Downloading"
            isConverting -> "Converting"
            else -> "Idle"
        }
    }

    val downloadProgress: LiveData<TileDownloader.DownloadProgress> get() = tileDownloader.downloadProgress
    val conversionProgress: LiveData<MBTilesConverter.ConversionProgress> get() = mbtConverter.conversionProgress
    private fun getDefaultTilePath(): String {
        return context.getExternalFilesDir("tiles")?.absolutePath ?: throw IllegalStateException("Unable to get external storage directory")
    }

    /**
     * Adds an overlay layer to the map.
     *
     * @param rasterLayer The raster layer configuration
     * @param isVisible Whether the layer should be visible initially
     * @param order The display order of the layer
     */
    private fun addOverlay(rasterLayer: CGRasterLayer, isVisible: Boolean, order: Int) {
        val overlay = CGOverlayLayer(
            rasterLayer = rasterLayer,
            isVisible = isVisible,
            order = order
        )
        overlayLayers.add(overlay)
        addRasterLayer(rasterLayer)

        mapLibreMap?.style?.getLayer(rasterLayer.layerId)?.setProperties(
            PropertyFactory.rasterOpacity(if (isVisible) 1.0f else 0.0f)
        )
    }

    /**
     * Adds multiple overlay layers to the map.
     *
     * @param overlays The list of overlay layers to add
     */
    fun addOverlays(overlays: List<CGOverlayLayer>) {
        val sortedOverlays = overlays.sortedBy { it.order }

        sortedOverlays.forEach { overlay ->
            addOverlay(
                rasterLayer = overlay.rasterLayer,
                isVisible = false,
                order = overlay.order
            )
        }

        if (sortedOverlays.isNotEmpty()) {
            val firstLayerId = sortedOverlays.first().rasterLayer.layerId
            mapLibreMap?.style?.getLayer(firstLayerId)?.setProperties(
                PropertyFactory.rasterOpacity(1.0f)
            )
        }
    }

    /**
     * Sets the visibility of an overlay layer.
     *
     * @param id The ID of the overlay layer
     * @param visible Whether the layer should be visible
     */
    fun setOverlayVisibility(id: String, visible: Boolean) {
        overlayLayers.find { it.rasterLayer.layerId == id }?.let { overlay ->
            overlay.isVisible = visible
            mapLibreMap?.style?.getLayer(overlay.rasterLayer.layerId)?.setProperties(
                PropertyFactory.rasterOpacity(if (visible) 1.0f else 0.0f)
            )
        }
    }

    /**
     * Sets the transition progress for multiple image layers.
     *
     * @param progress The transition progress (0.0 to 1.0)
     * @param imageIds The list of image layer IDs
     * @param fadePrevious Whether to fade out the previous layer
     */
    fun setTransitionProgress(progress: Float, imageIds: List<String>, fadePrevious: Boolean) {
        val style = mapLibreMap?.style ?: return
        val maxIndex = imageIds.size - 1

        val currentIndex = floor(progress).toInt().coerceIn(0, maxIndex)
        val nextIndex = (currentIndex + 1).coerceIn(0, maxIndex)
        val partialProgress = (progress - currentIndex).coerceIn(0f, 1f)

        if (progress == 0.0f) {
            imageIds.forEachIndexed { index, layerId ->
                style.getLayer(layerId)?.setProperties(
                    PropertyFactory.rasterOpacity(if (index == 0) 1.0f else 0.0f)
                )
            }
            return
        }

        imageIds.forEachIndexed { index, layerId ->
            val opacity = when {
                progress == currentIndex.toFloat() -> {
                    1.0f
                }

                index == currentIndex -> {
                    if (fadePrevious) 1.0f - partialProgress else 1.0f
                }

                index == nextIndex -> {
                    partialProgress.coerceIn(0f, 1f)
                }

                index < currentIndex -> {
                    if (fadePrevious) 0.0f else 1.0f
                }

                else -> {
                    0.0f
                }
            }

            style.getLayer(layerId)?.setProperties(
                PropertyFactory.rasterOpacity(opacity)
            )
        }
    }

    /**
     * Starts a transition animation between multiple image layers.
     *
     * @param imageIds The list of image layer IDs
     * @param fadePrevious Whether to fade out the previous layer
     * @param duration The duration of the animation in milliseconds
     */
    fun startTransitionAnimation(imageIds: List<String>, fadePrevious: Boolean, duration: Long) {
        stopTransitionAnimation()

        val maxIndex = (imageIds.size - 1).toFloat()

        imageIds.forEach { layerId ->
            mapLibreMap?.style?.getLayer(layerId)?.setProperties(
                PropertyFactory.rasterOpacity(0.0f)
            )
        }

        mapLibreMap?.style?.getLayer(imageIds.first())?.setProperties(
            PropertyFactory.rasterOpacity(1.0f)
        )

        transitionAnimator = ValueAnimator.ofFloat(0f, maxIndex).apply {
            this.duration = duration
            interpolator = LinearInterpolator()

            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                setTransitionProgress(progress, imageIds, fadePrevious)
            }

            start()
        }
    }

    /**
     * Gets the list of all overlay layers.
     *
     * @return The list of overlay layers
     */
    fun getOverlays(): List<CGOverlayLayer> = overlayLayers.toList()

    /**
     * Stops the current transition animation.
     */
    fun stopTransitionAnimation() {
        transitionAnimator?.cancel()
        transitionAnimator = null
    }

    /**
     * Adds a border layer to the map.
     *
     * @param cgRasterLayer The raster layer configuration for the border
     */
    fun addBorder(cgRasterLayer: CGRasterLayer) {
        addRasterLayer(cgRasterLayer)
    }

    /**
     * Removes a border layer from the map.
     *
     * @param cgRasterLayer The raster layer configuration for the border
     */
    fun removeBorder(cgRasterLayer: CGRasterLayer) {
        removeRasterLayer(cgRasterLayer)
    }
}

