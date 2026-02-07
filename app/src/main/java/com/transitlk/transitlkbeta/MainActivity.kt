package com.transitlk.transitlkbeta

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.location.GnssStatus
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var webView: WebView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var isTracking = false
    private var logFile: File? = null
    private var fileWriter: FileWriter? = null
    private var permissionsGranted = false
    private var isWebViewReady = false
    private var accelOffsetX = 0f
    private var accelOffsetY = 0f
    private var accelOffsetZ = 0f
    private var gyroOffsetX = 0f
    private var gyroOffsetY = 0f
    private var gyroOffsetZ = 0f
    private val dataLock = ReentrantLock()
    @Volatile private var accelX = 0f
    @Volatile private var accelY = 0f
    @Volatile private var accelZ = 0f
    @Volatile private var gyroX = 0f
    @Volatile private var gyroY = 0f
    @Volatile private var gyroZ = 0f
    @Volatile private var currentLat = 0.0
    @Volatile private var currentLng = 0.0
    @Volatile private var currentSpeed = 0f
    @Volatile private var currentAltitude = 0.0
    @Volatile private var currentHeading = 0f
    @Volatile private var currentAccuracy = 0f
    @Volatile private var satelliteCount = 0
    private val speedHistory = mutableListOf<Float>()
    private val speedHistorySize = 5
    private val minimumSpeedThreshold = 0.2f
    private val noiseThreshold = 0.1f
    private val stationaryThreshold = 0.2f
    private val minAccuracyForSpeed = 35f
    private val tripsList = mutableListOf<TripData>()
    private val currentTripData = mutableListOf<TripFrame>()
    private var tripStartTime: Long = 0
    private var maxSpeed = 0f // ✅ Track max speed
    private var geocoder: Geocoder? = null // ✅ For reverse geocoding
    private var gnssStatusCallback: GnssStatus.Callback? = null

    companion object {
        private const val TAG = "TransitLK"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val PREFS_NAME = "TransitLKPrefs"
        private const val TRIPS_KEY = "trips_history"
    }

    data class TripFrame(
        val timestamp: String,
        val lat: Double,
        val lng: Double,
        val altitude: Double,
        val speed: Float,
        val accuracy: Float,
        val satellites: Int,
        val ax: Float,
        val ay: Float,
        val az: Float,
        val gx: Float,
        val gy: Float,
        val gz: Float
    )

    data class TripData(
        val id: Long,
        val date: String,
        val frames: List<TripFrame>,
        val startLat: Double,
        val startLng: Double,
        val maxSpeed: Float // ✅ Include max speed in trip data
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        )
            }

            webView = WebView(this)
            setContentView(webView)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    val logMsg = "${msg.sourceId()}:${msg.lineNumber()} - ${msg.message()}"
                    when (msg.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, "WebView Error: $logMsg")
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "WebView Warning: $logMsg")
                        else -> Log.d(TAG, "WebView: $logMsg")
                    }
                    return true
                }
            }

            webView.addJavascriptInterface(WebAppInterface(this), "Android")

            setupWakeLock()
            initializeSensors()
            initializeLocation()
            setupGNSSListener()
            loadTripsFromStorage()

            // ✅ Initialize Geocoder
            geocoder = Geocoder(this, Locale.getDefault())

            webView.loadUrl("file:///android_asset/index.html")

            handler.postDelayed({
                isWebViewReady = true
                handler.postDelayed({
                    checkAndRequestPermissions()
                }, 500)
            }, 1500)

        } catch (e: Exception) {
            Log.e(TAG, "onCreate error: ${e.message}", e)
            Toast.makeText(this, "App initialization error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TransitLK::TrackingWakeLock"
            )
            wakeLock?.setReferenceCounted(false)
        } catch (e: Exception) {
            Log.e(TAG, "Wake lock error: ${e.message}", e)
        }
    }

    private fun initializeSensors() {
        try {
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        } catch (e: Exception) {
            Log.e(TAG, "Sensor init error: ${e.message}", e)
        }
    }

    private fun initializeLocation() {
        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    try {
                        locationResult.lastLocation?.let { location ->
                            updateLocationData(location)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Location result error: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location init error: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupGNSSListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssStatusCallback = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        var count = 0
                        for (i in 0 until status.satelliteCount) {
                            if (status.usedInFix(i)) {
                                count++
                            }
                        }
                        satelliteCount = count
                        Log.d(TAG, "Satellites in use: $satelliteCount")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GNSS setup error: ${e.message}", e)
        }
    }

    private fun getFilteredSpeed(rawSpeed: Float, accuracy: Float): Float {
        if (accuracy > minAccuracyForSpeed) {
            return if (speedHistory.isNotEmpty()) speedHistory.last() else 0f
        }
        val cleanSpeed = if (rawSpeed < noiseThreshold) 0f else rawSpeed
        speedHistory.add(cleanSpeed)
        if (speedHistory.size > speedHistorySize) {
            speedHistory.removeAt(0)
        }
        if (speedHistory.size < 3) {
            return cleanSpeed
        }
        val avgSpeed = speedHistory.average().toFloat()
        return if (avgSpeed < stationaryThreshold) {
            0f
        } else {
            avgSpeed
        }
    }

    private fun updateLocationData(location: Location) {
        try {
            currentLat = location.latitude
            currentLng = location.longitude
            currentAccuracy = location.accuracy
            val rawSpeed = if (location.hasSpeed()) location.speed else 0f
            currentSpeed = getFilteredSpeed(rawSpeed, currentAccuracy)
            currentAltitude = location.altitude
            currentHeading = if (location.hasBearing()) location.bearing else 0f

            // ✅ Update max speed
            if (currentSpeed > maxSpeed) {
                maxSpeed = currentSpeed
            }

            if (!isWebViewReady) return

            val json = JSONObject().apply {
                put("lat", currentLat)
                put("lng", currentLng)
                put("speed", currentSpeed.toDouble())
                put("altitude", currentAltitude)
                put("heading", currentHeading.toDouble())
                put("accuracy", currentAccuracy.toDouble())
                put("satellites", satelliteCount)
            }

            handler.post {
                try {
                    webView.evaluateJavascript(
                        "if(typeof updateGPS === 'function') { updateGPS($json); }",
                        null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "JS updateGPS error: ${e.message}", e)
                }
            }

            if (isTracking) {
                logDataFrame()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location update error: ${e.message}", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        try {
            dataLock.withLock {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        accelX = event.values[0] - accelOffsetX
                        accelY = event.values[1] - accelOffsetY
                        accelZ = event.values[2] - accelOffsetZ
                        if (isWebViewReady) {
                            val json = JSONObject().apply {
                                put("x", accelX.toDouble())
                                put("y", accelY.toDouble())
                                put("z", accelZ.toDouble())
                            }
                            handler.post {
                                try {
                                    webView.evaluateJavascript(
                                        "if(typeof updateAccel === 'function') { updateAccel($json); }",
                                        null
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "JS updateAccel error: ${e.message}", e)
                                }
                            }
                        }
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        gyroX = event.values[0] - gyroOffsetX
                        gyroY = event.values[1] - gyroOffsetY
                        gyroZ = event.values[2] - gyroOffsetZ
                        if (isWebViewReady) {
                            val json = JSONObject().apply {
                                put("x", gyroX.toDouble())
                                put("y", gyroY.toDouble())
                                put("z", gyroZ.toDouble())
                            }
                            handler.post {
                                try {
                                    webView.evaluateJavascript(
                                        "if(typeof updateGyro === 'function') { updateGyro($json); }",
                                        null
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "JS updateGyro error: ${e.message}", e)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sensor error: ${e.message}", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun logDataFrame() {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            dataLock.withLock {
                currentTripData.add(TripFrame(
                    timestamp = timestamp,
                    lat = currentLat,
                    lng = currentLng,
                    altitude = currentAltitude,
                    speed = currentSpeed,
                    accuracy = currentAccuracy,
                    satellites = satelliteCount,
                    ax = accelX,
                    ay = accelY,
                    az = accelZ,
                    gx = gyroX,
                    gy = gyroY,
                    gz = gyroZ
                ))
            }
            fileWriter?.apply {
                val dataRow = "$timestamp,$currentLat,$currentLng,$currentAltitude,$currentSpeed,$currentAccuracy,$satelliteCount," +
                        "$accelX,$accelY,$accelZ,$gyroX,$gyroY,$gyroZ\n"
                append(dataRow)
                if (currentTripData.size % 50 == 0) {
                    flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Log data error: ${e.message}", e)
        }
    }

    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun calibrateSensors() {
            handler.post {
                try {
                    dataLock.withLock {
                        accelOffsetX = accelX
                        accelOffsetY = accelY
                        accelOffsetZ = accelZ
                        gyroOffsetX = gyroX
                        gyroOffsetY = gyroY
                        gyroOffsetZ = gyroZ
                    }
                    Toast.makeText(context, "Sensors calibrated", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Calibration error: ${e.message}", e)
                }
            }
        }

        @JavascriptInterface
        fun startTracking() {
            handler.post {
                try {
                    if (!permissionsGranted) {
                        Toast.makeText(context, "Location permission needed", Toast.LENGTH_SHORT).show()
                        checkAndRequestPermissions()
                        return@post
                    }
                    if (isTracking) return@post
                    enableImmersiveMode()
                    isTracking = true
                    tripStartTime = System.currentTimeMillis()
                    currentTripData.clear()
                    speedHistory.clear()
                    maxSpeed = 0f // ✅ Reset max speed
                    createLogFile()
                    acquireWakeLock()
                    Toast.makeText(context, "Tracking started", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Start tracking error: ${e.message}", e)
                    Toast.makeText(context, "Start failed: ${e.message}", Toast.LENGTH_LONG).show()
                    isTracking = false
                }
            }
        }

        @JavascriptInterface
        fun stopTracking() {
            handler.post {
                try {
                    if (!isTracking) return@post
                    disableImmersiveMode()
                    isTracking = false
                    closeLogFile()
                    releaseWakeLock()
                    if (currentTripData.size > 0) {
                        saveTripData()
                        Toast.makeText(context, "Trip saved: ${currentTripData.size} points", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No data recorded", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Stop tracking error: ${e.message}", e)
                    Toast.makeText(context, "Stop failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ✅ NEW: Get location name from coordinates
        @JavascriptInterface
        fun getLocationName(lat: Double, lng: Double) {
            handler.post {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        geocoder?.getFromLocation(lat, lng, 1) { addresses ->
                            if (addresses.isNotEmpty()) {
                                val address = addresses[0]
                                val locationName = address.featureName ?: address.thoroughfare ?: address.subLocality ?: "%.4f, %.4f\".format(lat, lng)"
                                webView.evaluateJavascript(
                                    "if(typeof updateLocationName === 'function') { updateLocationName('$locationName'); }",
                                    null
                                )
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val addresses = geocoder?.getFromLocation(lat, lng, 1)
                        if (!addresses.isNullOrEmpty()) {
                            val address = addresses[0]
                            val locationName = address.featureName ?: address.thoroughfare ?: address.subLocality ?: "%.4f, %.4f\".format(lat, lng)"
                            webView.evaluateJavascript(
                                "if(typeof updateLocationName === 'function') { updateLocationName('$locationName'); }",
                                null
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Geocoder error: ${e.message}", e)
                }
            }
        }

        @JavascriptInterface
        fun getTripsHistory(): String {
            return try {
                val arr = JSONArray()
                synchronized(tripsList) {
                    tripsList.forEach { trip ->
                        arr.put(JSONObject().apply {
                            put("id", trip.id)
                            put("date", trip.date)
                            put("frameCount", trip.frames.size)
                            put("startLat", trip.startLat)
                            put("startLng", trip.startLng)
                        })
                    }
                }
                arr.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Get trips error: ${e.message}", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getTripData(tripId: Long): String {
            return try {
                val trip = synchronized(tripsList) {
                    tripsList.find { it.id == tripId }
                } ?: return "{}"
                val frames = JSONArray()
                trip.frames.forEach { f ->
                    frames.put(JSONObject().apply {
                        put("ts", f.timestamp)
                        put("lat", f.lat)
                        put("lng", f.lng)
                        put("alt", f.altitude)
                        put("spd", f.speed.toDouble())
                        put("acc", f.accuracy.toDouble())
                        put("sats", f.satellites)
                        put("ax", f.ax.toDouble())
                        put("ay", f.ay.toDouble())
                        put("az", f.az.toDouble())
                        put("gx", f.gx.toDouble())
                        put("gy", f.gy.toDouble())
                        put("gz", f.gz.toDouble())
                    })
                }
                JSONObject().apply {
                    put("id", trip.id)
                    put("date", trip.date)
                    put("startLat", trip.startLat)
                    put("startLng", trip.startLng)
                    put("maxSpeed", trip.maxSpeed.toDouble()) // ✅ Include max speed
                    put("data", frames)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Get trip data error: ${e.message}", e)
                "{}"
            }
        }

        @JavascriptInterface
        fun deleteTrip(tripId: Long): Boolean {
            return try {
                synchronized(tripsList) {
                    tripsList.removeAll { it.id == tripId }
                }
                saveTripsToStorage()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Delete error: ${e.message}", e)
                false
            }
        }


        @JavascriptInterface
        fun downloadCSV(csvData: String, filename: String) {
            handler.post {
                try {
                    // Get Downloads directory
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )

                    // Create TransitLK Test subfolder
                    val transitLKDir = File(downloadsDir, "TransitLK Test")
                    if (!transitLKDir.exists()) {
                        transitLKDir.mkdirs() // Create folder if it doesn't exist
                    }

                    // Create file inside the subfolder
                    val file = File(transitLKDir, filename)
                    FileWriter(file, false).use { writer ->
                        writer.write(csvData)
                        writer.flush()
                    }

                    Toast.makeText(context, "CSV saved: TransitLK Test/${file.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "CSV download error: ${e.message}", e)
                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGPSTracking() {
        try {
            if (!permissionsGranted) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssStatusCallback?.let {
                    locationManager.registerGnssStatusCallback(it, handler)
                }
            }
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                200
            ).apply {
                setMinUpdateIntervalMillis(100)
                setWaitForAccurateLocation(true)
                setMaxUpdateDelayMillis(300)
                setMinUpdateDistanceMeters(0f)
                setGranularity(Granularity.GRANULARITY_FINE)
                setMaxUpdates(Int.MAX_VALUE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setDurationMillis(Long.MAX_VALUE)
                }
            }.build()
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "GPS tracking started with FAST settings")
        } catch (e: Exception) {
            Log.e(TAG, "GPS start error: ${e.message}", e)
        }
    }

    private fun stopGPSTracking() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssStatusCallback?.let {
                    locationManager.unregisterGnssStatusCallback(it)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GPS stop error: ${e.message}", e)
        }
    }

    private fun startSensorTracking() {
        try {
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            gyroscope?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sensor start error: ${e.message}", e)
        }
    }

    private fun stopSensorTracking() {
        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Sensor stop error: ${e.message}", e)
        }
    }

    private fun createLogFile() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "TransitLK_$timestamp.csv"
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            logFile = File(downloadsDir, fileName)
            fileWriter = FileWriter(logFile, false)
            val header = "timestamp,lat,lng,altitude,speed_mps,accuracy,satellites,ax,ay,az,gx,gy,gz\n"
            fileWriter?.write(header)
            fileWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "CSV create error: ${e.message}", e)
        }
    }

    private fun closeLogFile() {
        try {
            fileWriter?.flush()
            fileWriter?.close()
            fileWriter = null
        } catch (e: Exception) {
            Log.e(TAG, "CSV close error: ${e.message}", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wake lock acquire error: ${e.message}", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wake lock release error: ${e.message}", e)
        }
    }

    private fun enableImmersiveMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Immersive mode error: ${e.message}", e)
        }
    }

    private fun disableImmersiveMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Disable immersive error: ${e.message}", e)
        }
    }

    private fun saveTripData() {
        try {
            if (currentTripData.isEmpty()) return
            val framesCopy = currentTripData.map { it.copy() }.toList()
            val trip = TripData(
                id = tripStartTime,
                date = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(tripStartTime)),
                frames = framesCopy,
                startLat = framesCopy.firstOrNull()?.lat ?: 0.0,
                startLng = framesCopy.firstOrNull()?.lng ?: 0.0,
                maxSpeed = maxSpeed // ✅ Save max speed
            )
            synchronized(tripsList) {
                tripsList.add(0, trip)
            }
            saveTripsToStorage()
            if (isWebViewReady) {
                handler.post {
                    try {
                        webView.evaluateJavascript(
                            "if(typeof onTripSaved === 'function') { onTripSaved(); }",
                            null
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "JS callback error: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save trip error: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private fun saveTripsToStorage() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = JSONArray()
            synchronized(tripsList) {
                tripsList.take(20).forEach { trip ->
                    val frames = JSONArray()
                    trip.frames.forEach { f ->
                        frames.put(JSONObject().apply {
                            put("ts", f.timestamp)
                            put("lat", f.lat)
                            put("lng", f.lng)
                            put("alt", f.altitude)
                            put("spd", f.speed.toDouble())
                            put("acc", f.accuracy.toDouble())
                            put("sats", f.satellites)
                            put("ax", f.ax.toDouble())
                            put("ay", f.ay.toDouble())
                            put("az", f.az.toDouble())
                            put("gx", f.gx.toDouble())
                            put("gy", f.gy.toDouble())
                            put("gz", f.gz.toDouble())
                        })
                    }
                    arr.put(JSONObject().apply {
                        put("id", trip.id)
                        put("date", trip.date)
                        put("startLat", trip.startLat)
                        put("startLng", trip.startLng)
                        put("maxSpeed", trip.maxSpeed.toDouble()) // ✅ Save max speed
                        put("frames", frames)
                    })
                }
            }
            prefs.edit().putString(TRIPS_KEY, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Save storage error: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private fun loadTripsFromStorage() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(TRIPS_KEY, "[]") ?: "[]"
            val arr = JSONArray(json)
            synchronized(tripsList) {
                tripsList.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val framesArr = obj.getJSONArray("frames")
                    val frames = mutableListOf<TripFrame>()
                    for (j in 0 until framesArr.length()) {
                        val f = framesArr.getJSONObject(j)
                        frames.add(TripFrame(
                            timestamp = f.getString("ts"),
                            lat = f.getDouble("lat"),
                            lng = f.getDouble("lng"),
                            altitude = f.getDouble("alt"),
                            speed = f.getDouble("spd").toFloat(),
                            accuracy = f.optDouble("acc", 0.0).toFloat(),
                            satellites = f.optInt("sats", 0),
                            ax = f.getDouble("ax").toFloat(),
                            ay = f.getDouble("ay").toFloat(),
                            az = f.getDouble("az").toFloat(),
                            gx = f.getDouble("gx").toFloat(),
                            gy = f.getDouble("gy").toFloat(),
                            gz = f.getDouble("gz").toFloat()
                        ))
                    }
                    tripsList.add(TripData(
                        id = obj.getLong("id"),
                        date = obj.getString("date"),
                        frames = frames,
                        startLat = obj.getDouble("startLat"),
                        startLng = obj.getDouble("startLng"),
                        maxSpeed = obj.optDouble("maxSpeed", 0.0).toFloat() // ✅ Load max speed
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load trips error: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            onPermissionsGrantedInternal()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                onPermissionsGrantedInternal()
            } else {
                Toast.makeText(this, "Location permission required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onPermissionsGrantedInternal() {
        permissionsGranted = true
        startGPSTracking()
        startSensorTracking()
        if (isWebViewReady) {
            handler.post {
                webView.evaluateJavascript(
                    "if(typeof onPermissionsGranted === 'function') { onPermissionsGranted(); }",
                    null
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (isTracking) {
                closeLogFile()
                if (currentTripData.isNotEmpty()) {
                    saveTripData()
                }
            }
            stopGPSTracking()
            stopSensorTracking()
            releaseWakeLock()
        } catch (e: Exception) {
            Log.e(TAG, "Destroy error: ${e.message}", e)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            if (isTracking) {
                Toast.makeText(this, "Stop tracking before exiting", Toast.LENGTH_SHORT).show()
            } else {
                super.onBackPressed()
            }
        }
    }
}