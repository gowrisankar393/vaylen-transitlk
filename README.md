# TransitLK Driver App

> **Project:** TransitLK  
> **Author:** Gowrisankar Sivakumar  
> **Module:** Driver App — Mobile Telemetry & Safety System  
> **Platform:** Android (Kotlin + WebView)  
> **Package:** `com.transitlk.transitlkbeta`

---

## Overview

The TransitLK Driver App is the data collection and safety hub of the TransitLK system. It runs on the bus driver's Android phone and is responsible for:

- Continuously recording GPS, accelerometer, and gyroscope data during trips
- Running an on-device crash detection model (ONNX Runtime)
- Triggering an emergency protocol when a crash is detected
- Uploading trip CSVs and incident reports to Firebase Cloud Storage
- Sending SMS notifications to a designated emergency contact on trip start, end, and crash

The app is architecturally a **Kotlin WebView wrapper** — the UI is built as a single HTML file (`index.html`) bundled in `assets/`, rendered inside a `WebView`. The Kotlin layer exposes a `JavascriptInterface` (`Android.*`) that gives the web UI direct access to native Android hardware: GPS, IMU sensors, SMS, phone calls, and the filesystem.

---

## Architecture

```
┌─────────────────────────────────────────────┐
│              index.html (UI Layer)           │
│  Tracker tab │ Trips tab │ Emergency Screen  │
│                                             │
│  Calls: Android.startTracking()             │
│         Android.stopTracking()              │
│         Android.downloadCSV()               │
│         Android.triggerEmergencyCall()      │
│         Android.confirmEmergencyCancel()    │
│         Android.getTripsHistory()           │
│         Android.getTripData()               │
│         Android.deleteTrip()                │
│         Android.getLocationName()           │
└───────────────────┬─────────────────────────┘
                    │  JavascriptInterface (WebAppInterface)
                    ▼
┌─────────────────────────────────────────────┐
│           MainActivity.kt (Native Layer)    │
│                                             │
│  GPS (FusedLocationProvider, GNSS)          │
│  IMU (Accelerometer + Gyroscope)            │
│  ONNX Runtime (Crash Detection Model)       │
│  Emergency System (SoundPool + Vibrator)    │
│  CSV Logger (FileWriter)                    │
│  WakeLock / Screen Management              │
└───────────────────┬─────────────────────────┘
                    │
          ┌─────────┴──────────┐
          ▼                    ▼
  Firebase Storage         SMS / Phone Call
  (CloudBackupService.kt)  (SmsManager, TelecomManager)
  trips/{date}/            Emergency Number: +94788894941
  incidents/{date}/
```

---

## Files

| File | Description |
|------|-------------|
| `MainActivity.kt` | Core activity — sensor reading, GPS tracking, crash detection, emergency protocol, WebView bridge |
| `CloudBackupService.kt` | Firebase Storage upload service for trip CSVs and incident reports |
| `index.html` | Full UI — bundled in `assets/`, rendered by the WebView |
| `AndroidManifest.xml` | App manifest — permissions, activity config, FileProvider |
| `assets/TransitLK-MSFCD-SCD-XGB-2.onnx` | On-device crash detection model (XGBoost exported to ONNX) |
| `res/raw/alarm.mp3` | Alarm sound played on crash detection |

---

## UI — `index.html`

The web UI has two main tabs and one full-screen overlay:

### Tracker Tab
The primary screen shown during a trip. Displays live telemetry:

| Section | Displayed Values |
|---------|-----------------|
| **Speed** | Current speed (km/h), large display |
| **GPS** | Lat/Lng in DMS format, location name (reverse geocoded), GPS status badge, satellite count |
| **Acceleration** | Accel X, Y, Z (m/s²) |
| **Motion Sensors** | Gyro X, Y, Z (°/s) |
| **Trip Stats** | Distance (km), Duration, Avg Speed, Max Speed, Avg Altitude, Max Altitude, Heading, Direction, Coordinates |
| **Sensor Calibration** | Auto-calibration status indicator |
| **Crash Detection** | Active/inactive status |

Controls:
- **Start Tracking** button (enabled only when GPS is ready)
- **Finish Trip** button — triggers a swipe-to-confirm gesture before stopping
- **Settings menu** — GPS status, satellite count

### Trips Tab
Lists all saved trips stored locally via `SharedPreferences`. Each trip entry shows the date, frame count, and start coordinates. Actions:
- **View trip** — opens a trip detail modal with the full telemetry stats table
- **Export CSV** — calls `Android.downloadCSV()` to write the trip CSV to `Downloads/TransitLK Test/`
- **Delete trip** — calls `Android.deleteTrip(tripId)`

### Emergency Screen (Full-screen Overlay)
Triggered automatically when a crash is detected. Shows:
- Title: **"Crash Detected"**
- Crash force (m/s²), location, and timestamp
- **15-second countdown** to auto-execute the emergency protocol
- **Swipe-to-confirm** gesture to cancel (driver confirms they are safe)
- **Call Emergency** button to immediately execute the protocol

---

## Native Layer — `MainActivity.kt`

### Sensor Collection
- **Accelerometer & Gyroscope** sampled at `SENSOR_DELAY_GAME` (~50 Hz)
- **GPS** via `FusedLocationProviderClient` at 200ms interval, 100ms minimum, `PRIORITY_HIGH_ACCURACY`
- **GNSS satellite count** tracked via `GnssStatus.Callback`
- Speed is filtered through a 5-sample rolling average; readings below 0.1 m/s are zeroed (noise threshold); GPS accuracy must be ≤ 35m for speed to be used

### Sensor Auto-Calibration
When the app is idle (not tracking), it auto-calibrates every 10 seconds by recording sensor offsets. When a trip starts, calibration is **locked** — offsets are frozen for the duration of the trip. This removes the mounting-position bias of the phone from all sensor readings.

### Trip Data Recording
Each GPS update during a trip logs a `TripFrame`:

| Column | Description |
|--------|-------------|
| `timestamp` | `yyyy-MM-dd HH:mm:ss.SSS` (UTC, Excel-friendly) |
| `lat` / `lng` | GPS coordinates |
| `altitude` | Altitude in metres |
| `speed_mps` | Filtered speed in m/s |
| `accuracy` | GPS accuracy in metres |
| `satellites` | GNSS satellites in use |
| `ax`, `ay`, `az` | Calibrated accelerometer (m/s²) |
| `gx`, `gy`, `gz` | Calibrated gyroscope (rad/s) |

CSVs are written to `Downloads/TransitLK Test/TransitLK_{timestamp}.csv` and also stored in memory / `SharedPreferences` for the Trips tab.

### Crash Detection
The app loads `TransitLK-MSFCD-SCD-XGB-2.onnx` (the XGBoost sensor model from the MSFCD pipeline, exported to ONNX) via **ONNX Runtime** on device.

Detection runs every 100ms while tracking is active. A **threshold check** is also applied directly on raw acceleration magnitude:

```
Crash threshold: 38.0 m/s²  (~3.8G)
```

When the threshold is breached, the emergency protocol is triggered.

### Emergency Protocol

```
Crash detected (accel > 38 m/s²)
        │
        ▼
Emergency screen shown + alarm + vibration
        │
        ▼
15-second countdown starts
        │
   ┌────┴────┐
   │         │
Driver    Countdown
swipes      reaches
 safe         zero
   │         │
   ▼         ▼
Cancel    Execute:
+ upload  - Phone call to emergency number
incident  - SMS with crash data + Google Maps link
(cancelled)  - Upload incident CSV to Firebase
             - SMS with Firebase download link
```

### SMS Notifications
All SMS messages use SIM slot 0 (Hutch SIM). Three types of SMS are sent:

| Event | SMS Contents |
|-------|-------------|
| **Trip Start** | Timestamp, start location (Google Maps link + coordinates) |
| **Trip End** | End time, duration, end location, max speed, Firebase download link (or fallback with location only) |
| **Crash Incident** | Crash time, force (m/s²), location, speed, altitude, status (cancelled/emergency executed), Firebase incident download link |

Emergency contact number: **+94788894941**

---

## `CloudBackupService.kt`

Handles all Firebase Storage operations:

| Method | Description |
|--------|-------------|
| `uploadTripCSV(file, tripId)` | Uploads trip CSV to `trips/{yyyy-MM-dd}/trip_{id}_{HHmmss}.csv` |
| `uploadIncidentCSV(...)` | Creates and uploads an incident CSV to `incidents/{yyyy-MM-dd}/INCIDENT_{HHmmss}.csv` |
| `listBackedUpTrips()` | Lists all trip filenames in Firebase Storage |
| `cleanupOldBackups(days)` | Deletes trip folders older than `days` (default: 365) |

### Incident CSV Format (Horizontal)
Each incident is a two-row CSV (headers + one data row):

`DateTime`, `Timestamp`, `Force_mps2`, `Force_G`, `Latitude`, `Longitude`, `Altitude_m`, `GoogleMaps_Link`, `Speed_mps`, `Speed_kmh`, `Heading_deg`, `Satellites`, `AccelX_mps2`, `AccelY_mps2`, `AccelZ_mps2`, `GyroX_dps`, `GyroY_dps`, `GyroZ_dps`, `EmergencyExecuted`, `UserCancelled`, `Status`, `TripID`, `EmergencyNumber`

---

## Android Permissions

| Permission | Purpose |
|-----------|---------|
| `ACCESS_FINE_LOCATION` | High-accuracy GPS |
| `ACCESS_COARSE_LOCATION` | Fallback location |
| `ACCESS_BACKGROUND_LOCATION` | GPS when screen is off |
| `FOREGROUND_SERVICE` | Keep tracking service alive |
| `WAKE_LOCK` | Prevent CPU sleep during tracking |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent OS from killing the app |
| `CALL_PHONE` | Emergency phone call |
| `SEND_SMS` | Trip/incident SMS notifications |
| `READ_PHONE_STATE` | SIM slot selection |
| `POST_NOTIFICATIONS` | Android 13+ notification permission |
| `VIBRATE` | Crash alert vibration |
| `INTERNET` | Firebase uploads |
| `WRITE_EXTERNAL_STORAGE` | CSV save to Downloads (≤ Android 9) |

---

## Activity Configuration
- **Orientation:** Portrait only
- **Launch mode:** `singleTask`
- **Screen:** Always on (`keepScreenOn`, `FLAG_KEEP_SCREEN_ON`, `SCREEN_BRIGHT_WAKE_LOCK`)
- **Show when locked:** Yes — the app can wake and display on the lock screen
- **Hardware acceleration:** Enabled
- **WakeLock duration:** Up to 12 hours (tracking) / 10 hours (screen)

---

## Building

### Prerequisites
- Android Studio
- Android SDK (minSdk 21 recommended)
- Firebase project with Storage enabled — place `google-services.json` in `app/`
- ONNX Runtime for Android dependency in `build.gradle`

### Dependencies (key)
```gradle
implementation 'com.google.android.gms:play-services-location:21.x.x'
implementation 'com.google.firebase:firebase-storage-ktx'
implementation 'com.microsoft.onnxruntime:onnxruntime-android:x.x.x'
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:x.x.x'
```

### Asset Setup
Place the following in `app/src/main/assets/`:
```
assets/
├── index.html              ← Driver app UI
└── TransitLK-MSFCD-SCD-XGB-2.onnx  ← Crash detection model
```

---

## About TransitLK

**TransitLK** is a project to modernise public bus transit in Sri Lanka. The Driver App is the primary data source for the entire system — the live telemetry it sends to Firebase powers the admin dashboard's real-time map, the ETA predictions computed on Render, and the passenger-facing arrival information.

---

## Author

**Gowrisankar Sivakumar**  
TransitLK Project — Driver App

---

## License

All rights reserved by the author unless otherwise stated.
