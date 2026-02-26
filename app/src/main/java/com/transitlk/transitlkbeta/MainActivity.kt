package com.transitlk.transitlkbeta

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.location.GnssStatus
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.*
import ai.onnxruntime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

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

    private var screenWakeLock: PowerManager.WakeLock? = null

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private var isCrashDetectionActive = false
    private var lastInferenceTime: Long = 0

    private val sensorWindowSize = 50
    private val accelBufferX = ArrayDeque<Float>(sensorWindowSize)
    private val accelBufferY = ArrayDeque<Float>(sensorWindowSize)
    private val accelBufferZ = ArrayDeque<Float>(sensorWindowSize)
    private val gyroBufferX = ArrayDeque<Float>(sensorWindowSize)
    private val gyroBufferY = ArrayDeque<Float>(sensorWindowSize)
    private val gyroBufferZ = ArrayDeque<Float>(sensorWindowSize)
    private val bufferLock = ReentrantLock()

    private var isEmergencyActive = false
    private var emergencyHandler = Handler(Looper.getMainLooper())
    private var emergencyRunnable: Runnable? = null
    private val emergencyCountdown = 15
    private var soundPool: android.media.SoundPool? = null
    private var alarmSoundId: Int = 0
    private var vibrator: Vibrator? = null

    private var autoCalibrationActive = true
    private var lastCalibrationTime: Long = 0
    private val calibrationInterval = 10000L

    @Volatile private var rawAccelX = 0f
    @Volatile private var rawAccelY = 0f
    @Volatile private var rawAccelZ = 0f
    @Volatile private var rawGyroX = 0f
    @Volatile private var rawGyroY = 0f
    @Volatile private var rawGyroZ = 0f

    @Volatile private var accelOffsetX = 0f
    @Volatile private var accelOffsetY = 0f
    @Volatile private var accelOffsetZ = 0f
    @Volatile private var gyroOffsetX = 0f
    @Volatile private var gyroOffsetY = 0f
    @Volatile private var gyroOffsetZ = 0f

    @Volatile private var accelX = 0f
    @Volatile private var accelY = 0f
    @Volatile private var accelZ = 0f
    @Volatile private var gyroX = 0f
    @Volatile private var gyroY = 0f
    @Volatile private var gyroZ = 0f

    private val dataLock = ReentrantLock()
    @Volatile private var currentLat = 0.0
    @Volatile private var currentLng = 0.0
    @Volatile private var currentSpeed = 0f
    @Volatile private var currentAltitude = 0.0
    @Volatile private var currentHeading = 0f
    @Volatile private var currentAccuracy = 0f
    @Volatile private var satelliteCount = 0
    private val speedHistory = mutableListOf<Float>()
    private val speedHistorySize = 5
    private val minAccuracyForSpeed = 35f
    private val noiseThreshold = 0.1f
    private val stationaryThreshold = 0.2f
    private val tripsList = mutableListOf<TripData>()
    private val currentTripData = mutableListOf<TripFrame>()
    private var tripStartTime: Long = 0
    private var maxSpeed = 0f
    private var geocoder: Geocoder? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null

    // Store start location for trip start message
    private var tripStartLat = 0.0
    private var tripStartLng = 0.0

    // Store crash data for incident upload
    private var lastCrashData: CrashData? = null

    private val csvTimestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private var mediaPlayer: MediaPlayer? = null

    // Cloud backup service
    private var cloudBackupService: CloudBackupService? = null

    companion object {
        private const val TAG = "TransitLK"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val PREFS_NAME = "TransitLKPrefs"
        private const val TRIPS_KEY = "trips_history"
        const val EMERGENCY_NUMBER = "+94788894941"
        private const val HUTCH_SIM_SLOT = 0

        // CRASH THRESHOLD: 38.0f = ~3.8G
        private const val CRASH_THRESHOLD = 38.0f
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
        val maxSpeed: Float
    )

    // Crash incident data class
    data class CrashData(
        val timestamp: Long,
        val dateTime: String,
        val force: Float,
        val lat: Double,
        val lng: Double,
        val altitude: Double,
        val speed: Float,
        val heading: Float,
        val satellites: Int,
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
        val gyroX: Float,
        val gyroY: Float,
        val gyroZ: Float
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

            keepScreenOn()

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

            // Initialize Cloud Backup Service
            cloudBackupService = CloudBackupService(this)

            setupWakeLock()
            initializeSensors()
            initializeLocation()
            setupGNSSListener()
            initializeCrashDetection()
            initializeEmergencySystem()
            loadTripsFromStorage()

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

    private fun keepScreenOn() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "TransitLK::ScreenWakeLock"
            )
            screenWakeLock?.setReferenceCounted(false)

            Log.d(TAG, "Screen wake lock initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Screen wake lock error: ${e.message}", e)
        }
    }

    private fun releaseScreenWakeLock() {
        try {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (screenWakeLock?.isHeld == true) {
                screenWakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Release screen wake lock error: ${e.message}", e)
        }
    }

    private fun initializeCrashDetection() {
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            val modelPath = "TransitLK-MSFCD-SCD-XGB-2.onnx"
            assets.open(modelPath).use { inputStream ->
                val modelBytes = inputStream.readBytes()
                val sessionOptions = OrtSession.SessionOptions()
                ortSession = ortEnvironment?.createSession(modelBytes, sessionOptions)
            }
            Log.d(TAG, "Crash detection model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load crash detection model: ${e.message}", e)
        }
    }

    private fun initializeEmergencySystem() {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = android.media.SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(audioAttributes)
                .build()

            alarmSoundId = soundPool?.load(this, R.raw.alarm, 1) ?: 0
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        } catch (e: Exception) {
            Log.e(TAG, "Emergency system init error: ${e.message}", e)
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

    private fun performAutoCalibration() {
        if (!autoCalibrationActive || isTracking) return

        val now = System.currentTimeMillis()
        if (now - lastCalibrationTime < calibrationInterval) return

        dataLock.withLock {
            accelOffsetX = rawAccelX
            accelOffsetY = rawAccelY
            accelOffsetZ = rawAccelZ
            gyroOffsetX = rawGyroX
            gyroOffsetY = rawGyroY
            gyroOffsetZ = rawGyroZ

            lastCalibrationTime = now
        }

        Log.d(TAG, "Auto calibration performed")

        if (isWebViewReady) {
            handler.post {
                try {
                    webView.evaluateJavascript(
                        "if(typeof onAutoCalibrated === 'function') { onAutoCalibrated(); }",
                        null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "JS onAutoCalibrated error: ${e.message}", e)
                }
            }
        }
    }

    private fun lockCalibration() {
        autoCalibrationActive = false
        dataLock.withLock {
            Log.d(TAG, "Calibration LOCKED")
        }

        isCrashDetectionActive = true

        if (isWebViewReady) {
            handler.post {
                try {
                    webView.evaluateJavascript(
                        "if(typeof onCalibrationLocked === 'function') { onCalibrationLocked(); }",
                        null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "JS onCalibrationLocked error: ${e.message}", e)
                }
            }
        }
    }

    private fun resetCalibrationState() {
        autoCalibrationActive = true
        lastCalibrationTime = 0
        isCrashDetectionActive = false
        Log.d(TAG, "Calibration state reset")
    }

    private fun runCrashDetectionInference() {
        if (!isCrashDetectionActive || isEmergencyActive) return

        val now = System.currentTimeMillis()
        if (now - lastInferenceTime < 100) return
        lastInferenceTime = now

        val accelMagnitude = sqrt(accelX*accelX + accelY*accelY + accelZ*accelZ)

        if (accelMagnitude > 15) {
            Log.d(TAG, "⚠️ High acceleration: $accelMagnitude m/s²")
        }

        if (accelMagnitude > CRASH_THRESHOLD) {
            Log.d(TAG, "🚨 CRASH DETECTED: $accelMagnitude m/s² (threshold: $CRASH_THRESHOLD)")

            // Store crash data for incident upload
            lastCrashData = CrashData(
                timestamp = now,
                dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now)),
                force = accelMagnitude,
                lat = currentLat,
                lng = currentLng,
                altitude = currentAltitude,
                speed = currentSpeed,
                heading = currentHeading,
                satellites = satelliteCount,
                accelX = accelX,
                accelY = accelY,
                accelZ = accelZ,
                gyroX = gyroX,
                gyroY = gyroY,
                gyroZ = gyroZ
            )

            isCrashDetectionActive = false

            handler.post {
                triggerEmergency("Impact detected! Force: ${accelMagnitude.toInt()} m/s²")
            }
        }
    }

    private fun triggerEmergency(reason: String) {
        if (isEmergencyActive) return

        isEmergencyActive = true
        isCrashDetectionActive = false

        soundPool?.play(alarmSoundId, 1.0f, 1.0f, 1, -1, 1.0f)

        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(longArrayOf(0, 500, 500), 0)
            }
        }

        if (isWebViewReady) {
            val json = JSONObject().apply {
                put("reason", reason)
                put("lat", currentLat)
                put("lng", currentLng)
            }

            handler.post {
                webView.evaluateJavascript(
                    "if(typeof showEmergencyScreen === 'function') { showEmergencyScreen($json); }",
                    null
                )
            }
        }

        var remainingSeconds = emergencyCountdown
        emergencyRunnable = object : Runnable {
            override fun run() {
                if (!isEmergencyActive) return

                if (remainingSeconds > 0) {
                    if (isWebViewReady) {
                        handler.post {
                            webView.evaluateJavascript(
                                "if(typeof updateEmergencyCountdown === 'function') { updateEmergencyCountdown($remainingSeconds); }",
                                null
                            )
                        }
                    }
                    remainingSeconds--
                    emergencyHandler.postDelayed(this, 1000)
                } else {
                    executeEmergencyProtocol()
                }
            }
        }
        emergencyHandler.postDelayed(emergencyRunnable!!, 1000)
    }

    fun cancelEmergency() {
        // Upload incident with cancelled status
        lastCrashData?.let { crashData ->
            uploadIncidentToCloud(crashData, emergencyExecuted = false, cancelled = true)
            lastCrashData = null
        }

        isEmergencyActive = false
        emergencyRunnable?.let { emergencyHandler.removeCallbacks(it) }

        soundPool?.autoPause()
        vibrator?.cancel()

        isCrashDetectionActive = isTracking

        if (isWebViewReady) {
            handler.post {
                webView.evaluateJavascript(
                    "if(typeof hideEmergencyScreen === 'function') { hideEmergencyScreen(); }",
                    null
                )
            }
        }
    }

    fun executeEmergencyProtocol() {
        if (!isEmergencyActive) return

        soundPool?.autoPause()

        // Upload incident with emergency executed status
        lastCrashData?.let { crashData ->
            uploadIncidentToCloud(crashData, emergencyExecuted = true, cancelled = false)
            lastCrashData = null
        }

        makeEmergencyCall()
        sendEmergencySMS()

        if (isWebViewReady) {
            handler.post {
                webView.evaluateJavascript(
                    "if(typeof onEmergencyExecuted === 'function') { onEmergencyExecuted(); }",
                    null
                )
            }
        }
    }

    /**
     * Upload crash incident to Firebase as CSV
     */
    private fun uploadIncidentToCloud(crashData: CrashData, emergencyExecuted: Boolean, cancelled: Boolean) {
        try {
            cloudBackupService?.let { service ->
                CoroutineScope(Dispatchers.IO).launch {
                    val result = service.uploadIncidentCSV(
                        context = this@MainActivity,
                        crashData = crashData,
                        emergencyExecuted = emergencyExecuted,
                        cancelled = cancelled,
                        tripId = if (isTracking) tripStartTime else null
                    )

                    result.fold(
                        onSuccess = { downloadUrl ->
                            Log.d(TAG, "✅ Incident uploaded to cloud: $downloadUrl")

                            // Send SMS with incident link
                            val statusText = when {
                                cancelled -> "CANCELLED - Driver confirmed safe"
                                emergencyExecuted -> "EMERGENCY EXECUTED - Help contacted"
                                else -> "UNKNOWN STATUS"
                            }

                            val message = """
                                🚨 TRANSITLK CRASH INCIDENT
                                
                                Time: ${crashData.dateTime}
                                Force: ${"%.1f".format(crashData.force)} m/s²
                                Location: https://maps.google.com/?q=${crashData.lat},${crashData.lng}
                                Coordinates: ${"%.6f".format(crashData.lat)}, ${"%.6f".format(crashData.lng)}
                                Altitude: ${"%.1f".format(crashData.altitude)} m
                                Status: $statusText
                                
                                Incident Data: $downloadUrl
                            """.trimIndent()

                            sendSMSAutomatically(EMERGENCY_NUMBER, message)
                        },
                        onFailure = { error ->
                            Log.e(TAG, "❌ Incident upload failed: ${error.message}")
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading incident: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun makeEmergencyCall() {
        try {
            val telecomManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            } else null

            val uri = android.net.Uri.fromParts("tel", EMERGENCY_NUMBER, null)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && telecomManager != null) {
                val accountHandle = telecomManager.callCapablePhoneAccounts.getOrNull(HUTCH_SIM_SLOT)
                if (accountHandle != null) {
                    telecomManager.placeCall(uri, Bundle().apply {
                        putParcelable(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
                    })
                } else {
                    val intent = android.content.Intent(android.content.Intent.ACTION_CALL, uri)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            } else {
                val intent = android.content.Intent(android.content.Intent.ACTION_CALL, uri)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }

            Log.d(TAG, "Emergency call initiated to $EMERGENCY_NUMBER")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to make emergency call: ${e.message}", e)
            Toast.makeText(this, "Failed to make emergency call", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendEmergencySMS() {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                @SuppressLint("MissingPermission")
                val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
                val subscriptionId = if (subscriptionInfoList != null && subscriptionInfoList.size > HUTCH_SIM_SLOT) {
                    subscriptionInfoList[HUTCH_SIM_SLOT].subscriptionId
                } else {
                    SubscriptionManager.getDefaultSmsSubscriptionId()
                }
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val message = """
                🚨 TRANSITLK CRASH ALERT 🚨
                
                Time: $timestamp
                Location: https://maps.google.com/?q=$currentLat,$currentLng
                Coordinates: ${"%.6f".format(currentLat)}, ${"%.6f".format(currentLng)}
                Speed: ${"%.1f".format(currentSpeed * 3.6)} km/h
                Altitude: ${"%.1f".format(currentAltitude)} m
                
                Driver did not respond to emergency confirmation.
                Immediate assistance required!
            """.trimIndent()

            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(
                EMERGENCY_NUMBER,
                null,
                parts,
                null,
                null
            )

            Log.d(TAG, "Emergency SMS sent to $EMERGENCY_NUMBER")
            Toast.makeText(this, "Emergency SMS sent", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send emergency SMS: ${e.message}", e)
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
                        rawAccelX = event.values[0]
                        rawAccelY = event.values[1]
                        rawAccelZ = event.values[2]

                        accelX = rawAccelX - accelOffsetX
                        accelY = rawAccelY - accelOffsetY
                        accelZ = rawAccelZ - accelOffsetZ

                        bufferLock.withLock {
                            accelBufferX.addLast(accelX)
                            accelBufferY.addLast(accelY)
                            accelBufferZ.addLast(accelZ)
                            if (accelBufferX.size > sensorWindowSize) accelBufferX.removeFirst()
                            if (accelBufferY.size > sensorWindowSize) accelBufferY.removeFirst()
                            if (accelBufferZ.size > sensorWindowSize) accelBufferZ.removeFirst()
                        }

                        performAutoCalibration()

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
                        rawGyroX = event.values[0]
                        rawGyroY = event.values[1]
                        rawGyroZ = event.values[2]

                        gyroX = rawGyroX - gyroOffsetX
                        gyroY = rawGyroY - gyroOffsetY
                        gyroZ = rawGyroZ - gyroOffsetZ

                        bufferLock.withLock {
                            gyroBufferX.addLast(gyroX)
                            gyroBufferY.addLast(gyroY)
                            gyroBufferZ.addLast(gyroZ)
                            if (gyroBufferX.size > sensorWindowSize) gyroBufferX.removeFirst()
                            if (gyroBufferY.size > sensorWindowSize) gyroBufferY.removeFirst()
                            if (gyroBufferZ.size > sensorWindowSize) gyroBufferZ.removeFirst()
                        }

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

            runCrashDetectionInference()

        } catch (e: Exception) {
            Log.e(TAG, "Sensor error: ${e.message}", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun logDataFrame() {
        try {
            // Excel-friendly ISO format: 2024-01-15 14:30:25.123
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

    // ==================== CLOUD BACKUP & SMS FUNCTIONS ====================

    /**
     * Send trip start SMS with time and location
     */
    private fun sendTripStartSMS() {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                @SuppressLint("MissingPermission")
                val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
                val subscriptionId = if (subscriptionInfoList != null && subscriptionInfoList.size > HUTCH_SIM_SLOT) {
                    subscriptionInfoList[HUTCH_SIM_SLOT].subscriptionId
                } else {
                    SubscriptionManager.getDefaultSmsSubscriptionId()
                }
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(tripStartTime))

            val message = """
                🚗 TransitLK Trip Started
                
                Time: $timestamp
                Location: https://maps.google.com/?q=$tripStartLat,$tripStartLng
                Coordinates: ${"%.6f".format(tripStartLat)}, ${"%.6f".format(tripStartLng)}
                
                Tracking is now active. Drive safe!
            """.trimIndent()

            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(
                EMERGENCY_NUMBER,
                null,
                parts,
                null,
                null
            )

            Log.d(TAG, "✅ Trip start SMS sent to $EMERGENCY_NUMBER")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send trip start SMS: ${e.message}", e)
        }
    }

    /**
     * Backup trip to Firebase Cloud Storage and send SMS notification with location
     */
    private fun backupTripToCloud(tripId: Long) {
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val transitLKDir = File(downloadsDir, "TransitLK Test")

            if (!transitLKDir.exists()) {
                Log.e(TAG, "TransitLK directory not found")
                // Still send SMS even if cloud backup fails
                sendTripEndSMSWithLocation()
                return
            }

            val csvFiles = transitLKDir.listFiles { file ->
                file.name.startsWith("TransitLK_") && file.name.endsWith(".csv")
            }

            if (csvFiles.isNullOrEmpty()) {
                Log.w(TAG, "No CSV files found")
                sendTripEndSMSWithLocation()
                return
            }

            val latestFile = csvFiles.maxByOrNull { it.lastModified() }
            if (latestFile == null || !latestFile.canRead()) {
                Log.w(TAG, "Latest CSV not accessible")
                sendTripEndSMSWithLocation()
                return
            }

            Log.d(TAG, "Backing up trip: ${latestFile.name}")

            // Upload to Firebase Cloud Storage
            cloudBackupService?.let { service ->
                CoroutineScope(Dispatchers.IO).launch {
                    val result = service.uploadTripCSV(latestFile, tripId)

                    result.fold(
                        onSuccess = { downloadUrl ->
                            Log.d(TAG, "✅ Trip backed up to cloud: $downloadUrl")

                            // Send SMS with download link and location
                            sendTripEndSMSWithCloudLink(downloadUrl, latestFile.name)

                            // Clean up old backups
                            service.cleanupOldBackups(365)
                        },
                        onFailure = { error ->
                            Log.e(TAG, "❌ Cloud backup failed: ${error.message}")

                            // Fallback to SMS with location only
                            sendTripEndSMSWithLocation()
                        }
                    )
                }
            } ?: run {
                // No cloud service available, send location SMS
                sendTripEndSMSWithLocation()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error backing up trip: ${e.message}", e)
            sendTripEndSMSWithLocation()
        }
    }

    /**
     * Send trip end SMS with cloud download link and end location
     */
    private fun sendTripEndSMSWithCloudLink(downloadUrl: String, fileName: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                @SuppressLint("MissingPermission")
                val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
                val subscriptionId = if (subscriptionInfoList != null && subscriptionInfoList.size > HUTCH_SIM_SLOT) {
                    subscriptionInfoList[HUTCH_SIM_SLOT].subscriptionId
                } else {
                    SubscriptionManager.getDefaultSmsSubscriptionId()
                }
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val endTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val duration = if (tripStartTime > 0) {
                val elapsedMs = System.currentTimeMillis() - tripStartTime
                val hours = elapsedMs / 3600000
                val minutes = (elapsedMs % 3600000) / 60000
                "${hours}h ${minutes}m"
            } else "Unknown"

            val message = """
                🚗 TransitLK Trip Completed
                
                End Time: $endTime
                Duration: $duration
                End Location: https://maps.google.com/?q=$currentLat,$currentLng
                End Coordinates: ${"%.6f".format(currentLat)}, ${"%.6f".format(currentLng)}
                Max Speed: ${"%.1f".format(maxSpeed * 3.6)} km/h
                
                Download: $downloadUrl
                File: $fileName
            """.trimIndent()

            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(
                EMERGENCY_NUMBER,
                null,
                parts,
                null,
                null
            )

            Log.d(TAG, "✅ Trip end SMS with cloud link sent to $EMERGENCY_NUMBER")

            runOnUiThread {
                Toast.makeText(this, "Trip saved, backed up & SMS sent", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send trip end SMS: ${e.message}", e)
        }
    }

    /**
     * Send trip end SMS with location only (fallback when cloud fails)
     */
    private fun sendTripEndSMSWithLocation() {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                @SuppressLint("MissingPermission")
                val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
                val subscriptionId = if (subscriptionInfoList != null && subscriptionInfoList.size > HUTCH_SIM_SLOT) {
                    subscriptionInfoList[HUTCH_SIM_SLOT].subscriptionId
                } else {
                    SubscriptionManager.getDefaultSmsSubscriptionId()
                }
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val endTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val duration = if (tripStartTime > 0) {
                val elapsedMs = System.currentTimeMillis() - tripStartTime
                val hours = elapsedMs / 3600000
                val minutes = (elapsedMs % 3600000) / 60000
                "${hours}h ${minutes}m"
            } else "Unknown"

            val message = """
                🚗 TransitLK Trip Completed
                
                End Time: $endTime
                Duration: $duration
                End Location: https://maps.google.com/?q=$currentLat,$currentLng
                End Coordinates: ${"%.6f".format(currentLat)}, ${"%.6f".format(currentLng)}
                Max Speed: ${"%.1f".format(maxSpeed * 3.6)} km/h
                
                Cloud backup unavailable. CSV saved locally.
            """.trimIndent()

            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(
                EMERGENCY_NUMBER,
                null,
                parts,
                null,
                null
            )

            Log.d(TAG, "✅ Trip end SMS with location sent to $EMERGENCY_NUMBER")

            runOnUiThread {
                Toast.makeText(this, "Trip saved & SMS sent (cloud backup failed)", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send trip end SMS: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Trip saved but SMS failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Send SMS automatically (no user interaction)
     */
    private fun sendSMSAutomatically(phoneNumber: String, message: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                @SuppressLint("MissingPermission")
                val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
                val subscriptionId = if (subscriptionInfoList != null && subscriptionInfoList.size > HUTCH_SIM_SLOT) {
                    subscriptionInfoList[HUTCH_SIM_SLOT].subscriptionId
                } else {
                    SubscriptionManager.getDefaultSmsSubscriptionId()
                }
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(
                phoneNumber,
                null,
                parts,
                null,
                null
            )

            Log.d(TAG, "✅ SMS sent to $phoneNumber")

            runOnUiThread {
                Toast.makeText(this, "Incident reported & SMS sent", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ SMS failed: ${e.message}", e)
        }
    }

    /**
     * Send CSV summary via SMS (fallback for when cloud backup fails)
     */
    private fun sendCSVSummaryViaSMS(csvFile: File) {
        try {
            val lines = csvFile.readLines()
            if (lines.size < 2) {
                return
            }

            val dataLines = lines.drop(1)
            val totalPoints = dataLines.size

            val firstLine = dataLines.firstOrNull()?.split(",") ?: return
            val lastLine = dataLines.lastOrNull()?.split(",") ?: return

            val startTime = firstLine.getOrNull(0) ?: "N/A"
            val endTime = lastLine.getOrNull(0) ?: "N/A"
            val startLat = firstLine.getOrNull(1) ?: "N/A"
            val startLng = firstLine.getOrNull(2) ?: "N/A"
            val endLat = lastLine.getOrNull(1) ?: "N/A"
            val endLng = lastLine.getOrNull(2) ?: "N/A"

            var maxSpeed = 0f
            dataLines.forEach { line ->
                val cols = line.split(",")
                val speed = cols.getOrNull(4)?.toFloatOrNull() ?: 0f
                if (speed > maxSpeed) maxSpeed = speed
            }

            val message = """
                🚗 TransitLK Trip Summary
                
                Points: $totalPoints
                Time: $startTime to $endTime
                Start: $startLat, $startLng
                End: $endLat, $endLng
                Max Speed: ${String.format("%.1f", maxSpeed * 3.6)} km/h
                
                Cloud backup failed. CSV saved locally.
            """.trimIndent()

            sendSMSAutomatically(EMERGENCY_NUMBER, message)

        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS summary: ${e.message}", e)
        }
    }

    inner class WebAppInterface(private val context: Context) {

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

                    // Store start location before locking calibration
                    tripStartLat = currentLat
                    tripStartLng = currentLng
                    tripStartTime = System.currentTimeMillis()

                    lockCalibration()

                    enableImmersiveMode()
                    isTracking = true
                    currentTripData.clear()
                    speedHistory.clear()
                    maxSpeed = 0f
                    createLogFile()
                    acquireWakeLock()

                    if (screenWakeLock?.isHeld != true) {
                        screenWakeLock?.acquire(10 * 60 * 60 * 1000L)
                    }

                    // Send trip start SMS
                    sendTripStartSMS()

                    Toast.makeText(context, "Tracking started - Crash detection active", Toast.LENGTH_SHORT).show()
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

                    if (isEmergencyActive) {
                        cancelEmergency()
                    }

                    resetCalibrationState()

                    closeLogFile()
                    releaseWakeLock()

                    if (screenWakeLock?.isHeld == true) {
                        screenWakeLock?.release()
                    }

                    if (currentTripData.size > 0) {
                        saveTripData()
                        // SMS is now sent via backupTripToCloud or sendTripEndSMSWithLocation
                        // Toast is shown in those methods
                    } else {
                        Toast.makeText(context, "No data recorded", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Stop tracking error: ${e.message}", e)
                    Toast.makeText(context, "Stop failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun confirmEmergencyCancel() {
            handler.post {
                cancelEmergency()
            }
        }

        @JavascriptInterface
        fun triggerEmergencyCall() {
            handler.post {
                executeEmergencyProtocol()
            }
        }

        @JavascriptInterface
        fun getLocationName(lat: Double, lng: Double) {
            handler.post {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        geocoder?.getFromLocation(lat, lng, 1) { addresses ->
                            if (addresses.isNotEmpty()) {
                                val address = addresses[0]
                                val locationName = address.featureName ?: address.thoroughfare ?: address.subLocality ?: "%.4f, %.4f".format(lat, lng)
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
                            val locationName = address.featureName ?: address.thoroughfare ?: address.subLocality ?: "%.4f, %.4f".format(lat, lng)
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
                    put("maxSpeed", trip.maxSpeed.toDouble())
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
                var writer: BufferedWriter? = null
                try {
                    val lines = csvData.lines()
                    if (lines.isEmpty()) {
                        Toast.makeText(context, "No data to export", Toast.LENGTH_SHORT).show()
                        return@post
                    }

                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    val transitLKDir = File(downloadsDir, "TransitLK Test")
                    if (!transitLKDir.exists()) {
                        transitLKDir.mkdirs()
                    }
                    val file = File(transitLKDir, filename)

                    writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8))

                    writer.write("timestamp,lat,lng,altitude,speed_mps,accuracy,satellites,ax,ay,az,gx,gy,gz")
                    writer.newLine()

                    val dataLines = if (lines[0].contains("timestamp", ignoreCase = true)) {
                        lines.drop(1)
                    } else {
                        lines
                    }

                    dataLines.forEach { line ->
                        if (line.isNotBlank()) {
                            val columns = line.split(",")
                            if (columns.size >= 13) {
                                val originalTimestamp = columns[0].trim()
                                val formattedTimestamp = formatTimestampForCSV(originalTimestamp)

                                val sb = StringBuilder()
                                sb.append(formattedTimestamp)
                                for (i in 1 until 13) {
                                    sb.append(",").append(columns[i].trim())
                                }
                                writer.write(sb.toString())
                                writer.newLine()
                            } else {
                                writer.write(line)
                                writer.newLine()
                            }
                        }
                    }

                    writer.flush()
                    Toast.makeText(context, "CSV saved: TransitLK Test/${file.name}", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "CSV exported: ${file.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "CSV download error: ${e.message}", e)
                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    try {
                        writer?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing writer: ${e.message}", e)
                    }
                }
            }
        }

        private fun formatTimestampForCSV(timestamp: String): String {
            return try {
                val inputFormats = arrayOf(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    "yyyy-MM-dd HH:mm:ss",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'"
                )

                var parsedDate: Date? = null

                for (format in inputFormats) {
                    try {
                        val sdf = SimpleDateFormat(format, Locale.US)
                        sdf.timeZone = TimeZone.getTimeZone("UTC")
                        parsedDate = sdf.parse(timestamp)
                        if (parsedDate != null) break
                    } catch (e: Exception) {
                        continue
                    }
                }

                if (parsedDate != null) {
                    csvTimestampFormat.format(parsedDate)
                } else {
                    timestamp
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not format timestamp: $timestamp")
                timestamp
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
            Log.d(TAG, "GPS tracking started")
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

    // FIXED: Excel-friendly timestamp format
    private fun createLogFile() {
        try {
            // Excel-friendly ISO format for filename: 2024-01-15_143025
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "TransitLK_$timestamp.csv"
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            logFile = File(downloadsDir, fileName)
            fileWriter = FileWriter(logFile, false)

            // Header with proper timestamp column
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
                maxSpeed = maxSpeed
            )
            synchronized(tripsList) {
                tripsList.add(0, trip)
            }
            saveTripsToStorage()

            // Backup to cloud and send SMS with location
            backupTripToCloud(tripStartTime)

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
        }
    }

    private fun saveTripsToStorage() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = JSONArray()
            synchronized(tripsList) {
                tripsList.forEach { trip ->
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
                        put("maxSpeed", trip.maxSpeed.toDouble())
                        put("frames", frames)
                    })
                }
            }
            prefs.edit().putString(TRIPS_KEY, arr.toString()).apply()
            Log.d(TAG, "Saved ${tripsList.size} trips to storage")
        } catch (e: Exception) {
            Log.e(TAG, "Save storage error: ${e.message}", e)
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
                        maxSpeed = obj.optDouble("maxSpeed", 0.0).toFloat()
                    ))
                }
            }
            Log.d(TAG, "Loaded ${tripsList.size} trips from storage")
        } catch (e: Exception) {
            Log.e(TAG, "Load trips error: ${e.message}", e)
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.CALL_PHONE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.SEND_SMS)
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
            if (isEmergencyActive) {
                cancelEmergency()
            }

            stopGPSTracking()
            stopSensorTracking()
            releaseWakeLock()
            releaseScreenWakeLock()

            inferenceExecutor.shutdown()
            ortSession?.close()
            ortEnvironment?.close()
            soundPool?.release()
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