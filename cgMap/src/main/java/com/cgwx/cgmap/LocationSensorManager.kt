package com.cgwx.cgmap

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.tencent.map.geolocation.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.maplibre.android.geometry.LatLng
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Interface for receiving azimuth (compass heading) updates
 */
interface AzimuthListener {
    /**
     * Called when the azimuth angle changes
     * @param azimuth The new azimuth angle in degrees (0-360)
     */
    fun onAzimuthChanged(azimuth: Double)
}

/**
 * Interface for receiving location updates
 */
interface LocationListener {
    /**
     * Called when the location changes
     * @param latLng Map containing latitude, longitude and accuracy information
     */
    fun onLocationChanged(latLng: Map<String, Any?>)
}

/**
 * Manages location and sensor updates for the application
 * Handles both online (Tencent) and offline location services
 * Manages various sensors including accelerometer, gravity, and magnetic field
 */
class LocationSensorManager(
    private val context: Context,
    private val azimuthListener: AzimuthListener,
    private val locationListener: LocationListener
) : SensorEventListener, TencentLocationListener {
    private var sensorManager: SensorManager
    private var linearAccSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var magneticSensor: Sensor? = null
    private var tencentLocationManager: TencentLocationManager? = null
    private var locationRequest: TencentLocationRequest? = null
    private val gravityValues = FloatArray(3)
    private val magneticValues = FloatArray(3)
    private var isMovingByAcceleration = false
    var azimuthDeg = 0.0
    private var currentInterval: Long = 5000
    private var currentMovementType: MovementType = MovementType.STATIONARY
    private var offlineLocationManager: android.location.LocationManager

    /**
     * Initialize the LocationSensorManager
     * Sets up sensors and location managers
     */
    init {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        offlineLocationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        initializeSensors()
        initializeLocationManager()
    }

    /**
     * Initialize all required sensors
     * Attempts to get linear acceleration sensor first, falls back to accelerometer if not available
     */
    private fun initializeSensors() {
        linearAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (linearAccSensor == null) {
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

    /**
     * Initialize the Tencent location manager and request settings
     */
    private fun initializeLocationManager() {
        tencentLocationManager = TencentLocationManager.getInstance(context)
        locationRequest = TencentLocationRequest.create().apply {
            requestLevel = TencentLocationRequest.REQUEST_LEVEL_GEO
            isAllowCache = true
            interval = 5000
        }
    }

    /**
     * Start receiving location updates from Tencent location service
     */
    fun startLocationUpdates() {
        tencentLocationManager?.requestLocationUpdates(locationRequest, this)
    }

    /**
     * Stop receiving location updates from Tencent location service
     */
    fun stopLocationUpdates() {
        tencentLocationManager?.removeUpdates(this)
    }

    /**
     * Register all sensors for updates
     * Uses UI delay for sensor updates
     */
    fun registerSensors() {
        linearAccSensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: accelerometerSensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_UI)
    }

    /**
     * Unregister all sensors
     */
    fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    /**
     * Handle sensor value changes
     * @param event The sensor event containing new values
     */
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val linearAcc = sqrt(ax * ax + ay * ay + az * az)
                val threshold = 0.05f
                isMovingByAcceleration = linearAcc > threshold
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val totalAcc = sqrt(ax * ax + ay * ay + az * az)
                val threshold = 0.5f
                isMovingByAcceleration = abs(totalAcc - 9.8f) > threshold
            }

            Sensor.TYPE_GRAVITY -> {
                gravityValues[0] = event.values[0]
                gravityValues[1] = event.values[1]
                gravityValues[2] = event.values[2]
                updateAzimuth()
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticValues[0] = event.values[0]
                magneticValues[1] = event.values[1]
                magneticValues[2] = event.values[2]
                updateAzimuth()
            }
        }
    }

    /**
     * Handle sensor accuracy changes
     * @param sensor The sensor that changed accuracy
     * @param accuracy The new accuracy value
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    /**
     * Handle location updates from Tencent location service
     * @param location The new location data
     * @param status The status of the location update
     * @param reason Additional information about the update
     */
    override fun onLocationChanged(location: TencentLocation?, status: Int, reason: String?) {
        if (status == TencentLocation.ERROR_OK && location != null) {
            val speed = if (location.speed >= 0) location.speed else 0f
            val newMovementType = getMovementTypeBySpeed(speed)
            if (newMovementType != currentMovementType) {
                currentMovementType = newMovementType
                val newInterval = getIntervalByType(newMovementType)
                if (newInterval != currentInterval) {
                    currentInterval = newInterval
                    restartLocationUpdates()
                }
            }
            val convert = gcj02ToWgs84(location.latitude, location.longitude)
            val locationData = mapOf(
                "latitude" to convert["lat"],
                "longitude" to convert["lon"],
                "accuracy" to location.accuracy
            )
            locationListener.onLocationChanged(locationData)
        }
    }

    /**
     * Handle status updates from Tencent location service
     * @param name The name of the status update
     * @param status The status code
     * @param desc Description of the status
     */
    override fun onStatusUpdate(name: String?, status: Int, desc: String?) {
    }

    /**
     * Get the last known location from the GPS provider
     * Requires GPS permissions
     */
    @SuppressLint("MissingPermission")
    private fun getOfflineLocation() {
        if (offlineLocationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            val location = offlineLocationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            if (location != null) {
                val latitude = location.latitude
                val longitude = location.longitude
                val locationData = mapOf(
                    "latitude" to location.latitude,
                    "longitude" to location.longitude,
                    "accuracy" to 0.0
                )
                Log.d("OfflineLocation", "Latitude: $latitude, Longitude: $longitude")
            } else {
                Log.d("OfflineLocation", "No cached location available.")
            }
        } else {
            Log.d("OfflineLocation", "GPS is not enabled.")
        }
    }

    /**
     * Start location updates based on network availability
     * Uses online service if available, falls back to offline GPS if not
     */
    fun locate() {
        TencentLocationManager.setUserAgreePrivacy(true)
        Log.d("isOnline ", isOnline().toString())
        if (isOnline()) {
            startLocation(currentInterval)
        } else {
            getOfflineLocation()
        }
    }

    /**
     * Check if the device has an active network connection
     * @return Boolean indicating if the device is online
     */
    private fun isOnline(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetworkInfo
        return activeNetwork != null && activeNetwork.isConnected
    }

    /**
     * Start location updates with specified interval
     * @param interval The update interval in milliseconds
     */
    private fun startLocation(interval: Long) {
        val request = TencentLocationRequest.create()
        request.requestLevel = TencentLocationRequest.REQUEST_LEVEL_GEO
        request.isAllowCache = true
        request.setAllowGPS(true)
        request.setInterval(interval)
        request.setAllowDirection(true)

        tencentLocationManager?.requestLocationUpdates(request, this)
    }

    /**
     * Restart location updates with current interval
     */
    private fun restartLocationUpdates() {
        tencentLocationManager?.removeUpdates(this)
        startLocation(currentInterval)
    }

    /**
     * Update the azimuth (compass heading) based on gravity and magnetic field data
     */
    private var lastAzimuthDeg: Double = -1.0
    private val azimuthThreshold = 3.0  // 设置一个最小角度变化阈值（单位：度）

    private fun updateAzimuth() {
        val rotationMatrix = FloatArray(9)
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, gravityValues, magneticValues)
        if (success) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val newAzimuth = (Math.toDegrees(orientation[0].toDouble()) + 360) % 360

            if (abs(newAzimuth - lastAzimuthDeg) > azimuthThreshold || lastAzimuthDeg < 0) {
                azimuthDeg = newAzimuth
                lastAzimuthDeg = newAzimuth
//                Log.d("azimuthDeg", azimuthDeg.toString())
                azimuthListener.onAzimuthChanged(azimuthDeg)
            }
        }
    }
}