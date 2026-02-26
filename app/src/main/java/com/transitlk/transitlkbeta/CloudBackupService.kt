package com.transitlk.transitlkbeta

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Firebase Cloud Storage backup service
 * Uploads trip CSV files and incident reports to Firebase cloud
 */
class CloudBackupService(private val context: Context) {

    companion object {
        private const val TAG = "CloudBackup"

        // Firebase Storage paths
        private const val PATH_TRIPS = "trips"
        private const val PATH_INCIDENTS = "incidents"
    }

    private val storage = Firebase.storage
    private val storageRef = storage.reference

    /**
     * Upload trip CSV to Firebase Cloud Storage
     */
    suspend fun uploadTripCSV(csvFile: File, tripId: Long): Result<String> {
        return try {
            if (!csvFile.exists()) {
                return Result.failure(Exception("File does not exist"))
            }

            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val fileName = "trip_${tripId}_${SimpleDateFormat("HHmmss", Locale.US).format(Date())}.csv"
            val path = "$PATH_TRIPS/$dateStr/$fileName"

            val fileRef = storageRef.child(path)

            Log.d(TAG, "Uploading to: $path")

            // Upload file
            val uploadTask = fileRef.putFile(Uri.fromFile(csvFile))
            uploadTask.await()

            // Get download URL
            val downloadUrl = fileRef.downloadUrl.await()

            Log.d(TAG, "✅ Upload successful: $downloadUrl")
            Result.success(downloadUrl.toString())

        } catch (e: Exception) {
            Log.e(TAG, "❌ Upload failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Upload crash incident CSV to Firebase Cloud Storage
     * Horizontal format with proper link formatting
     */
    suspend fun uploadIncidentCSV(
        context: Context,
        crashData: MainActivity.CrashData,
        emergencyExecuted: Boolean,
        cancelled: Boolean,
        tripId: Long?
    ): Result<String> {
        return try {
            val timestamp = System.currentTimeMillis()
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
            val timeStr = SimpleDateFormat("HHmmss", Locale.US).format(Date(timestamp))
            val fileName = "INCIDENT_${timeStr}.csv"
            val path = "$PATH_INCIDENTS/$dateStr/$fileName"

            // Create temporary CSV file
            val tempFile = File(context.cacheDir, fileName)
            val writer = FileWriter(tempFile)

            // HORIZONTAL FORMAT: Headers in first row, values in second row
            val headers = listOf(
                "DateTime",
                "Timestamp",
                "Force_mps2",
                "Force_G",
                "Latitude",
                "Longitude",
                "Altitude_m",
                "GoogleMaps_Link",
                "Speed_mps",
                "Speed_kmh",
                "Heading_deg",
                "Satellites",
                "AccelX_mps2",
                "AccelY_mps2",
                "AccelZ_mps2",
                "GyroX_dps",
                "GyroY_dps",
                "GyroZ_dps",
                "EmergencyExecuted",
                "UserCancelled",
                "Status",
                "TripID",
                "EmergencyNumber"
            )

            // Build Google Maps link (no commas, properly encoded)
            val mapsLink = "https://maps.google.com/?q=${crashData.lat},${crashData.lng}"

            val values = listOf(
                crashData.dateTime,
                crashData.timestamp.toString(),
                "%.2f".format(crashData.force),
                "%.2f".format(crashData.force / 9.81),
                "%.6f".format(crashData.lat),
                "%.6f".format(crashData.lng),
                "%.1f".format(crashData.altitude),
                mapsLink,  // No quotes needed, no commas in URL
                "%.2f".format(crashData.speed),
                "%.1f".format(crashData.speed * 3.6),
                "%.1f".format(crashData.heading),
                crashData.satellites.toString(),
                "%.3f".format(crashData.accelX),
                "%.3f".format(crashData.accelY),
                "%.3f".format(crashData.accelZ),
                "%.3f".format(crashData.gyroX),
                "%.3f".format(crashData.gyroY),
                "%.3f".format(crashData.gyroZ),
                emergencyExecuted.toString(),
                cancelled.toString(),
                when {
                    cancelled -> "CANCELLED-Driver confirmed safe"
                    emergencyExecuted -> "EMERGENCY EXECUTED-Help contacted"
                    else -> "UNKNOWN"
                },
                tripId?.toString() ?: "N/A",
                MainActivity.EMERGENCY_NUMBER
            )

            // Write CSV - properly escaped for Excel/Google Sheets
            writer.write(headers.joinToString(",") { escapeCsv(it) })
            writer.write("\n")
            writer.write(values.joinToString(",") { escapeCsv(it) })
            writer.write("\n")

            writer.flush()
            writer.close()

            val fileRef = storageRef.child(path)

            Log.d(TAG, "Uploading incident to: $path")

            // Upload file
            val uploadTask = fileRef.putFile(Uri.fromFile(tempFile))
            uploadTask.await()

            // Get download URL
            val downloadUrl = fileRef.downloadUrl.await()

            // Delete temp file
            tempFile.delete()

            Log.d(TAG, "✅ Incident upload successful: $downloadUrl")
            Result.success(downloadUrl.toString())

        } catch (e: Exception) {
            Log.e(TAG, "❌ Incident upload failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Escape CSV values properly
     * - Wrap in quotes if contains comma, quote, or newline
     * - Double up quotes if present
     */
    private fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
        return if (needsQuotes) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    /**
     * List all backed up trips
     */
    suspend fun listBackedUpTrips(): List<String> {
        return try {
            val listResult = storageRef.child(PATH_TRIPS).listAll().await()
            listResult.items.map { it.name }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list trips: ${e.message}")
            emptyList()
        }
    }

    /**
     * Delete old backups (keep last 365 days by default)
     */
    suspend fun cleanupOldBackups(daysToKeep: Int = 365) {
        try {
            val calendar = java.util.Calendar.getInstance()
            calendar.add(java.util.Calendar.DAY_OF_YEAR, -daysToKeep)
            val cutoffDate = calendar.time

            // List all trip folders
            val listResult = storageRef.child(PATH_TRIPS).listAll().await()

            for (prefix in listResult.prefixes) {
                try {
                    val folderDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(prefix.name)
                    if (folderDate != null && folderDate.before(cutoffDate)) {
                        // Delete old folder
                        val folderItems = prefix.listAll().await()
                        for (item in folderItems.items) {
                            item.delete().await()
                        }
                        Log.d(TAG, "Deleted old backup folder: ${prefix.name}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing date for folder: ${prefix.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}")
        }
    }
}