package io.hammerhead.karoocriticalpower

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.bufferDataStore by preferencesDataStore(name = "power_buffer_state")

/**
 * Centralized manager for all power buffers.
 *
 * This ensures:
 * - Single source of truth for power data (no duplicate buffers)
 * - Thread-safe access via mutex
 * - Persistence of best values to survive service restarts
 * - Diagnostic logging for post-ride analysis
 */
class PowerBufferManager(private val context: Context) {

    companion object {
        private const val TAG = "PowerBufferManager"

        // All supported durations in seconds
        val DURATIONS = listOf(5, 15, 30, 60, 180, 300, 1200, 1800, 2700, 3600, 5400)

        // Keys for persisted state
        private fun bestAverageKey(duration: Int) = doublePreferencesKey("best_avg_$duration")
        private val RIDE_START_TIME_KEY = longPreferencesKey("ride_start_time")
        private val LAST_SAMPLE_TIME_KEY = longPreferencesKey("last_sample_time")
        private val SAMPLE_COUNT_KEY = longPreferencesKey("sample_count")
    }

    // Single set of buffers for all durations
    private val buffers: Map<Int, PowerBuffer> = DURATIONS.associateWith { PowerBuffer(it) }

    // Mutex for thread-safe access
    private val mutex = Mutex()

    // Track ride timing for reset guard
    private var rideStartTime: Long = 0L
    private var lastSampleTime: Long = 0L
    private var sampleCount: Long = 0L

    // Flag to prevent resets before persisted state is loaded
    @Volatile
    private var stateLoaded: Boolean = false

    // Diagnostic log file
    private val logFile: File by lazy {
        File(context.filesDir, "power_buffer_diagnostics.log")
    }

    init {
        // Load persisted state on creation
        CoroutineScope(Dispatchers.IO).launch {
            loadPersistedState()
        }
    }

    /**
     * Add a power sample to all buffers.
     * This should only be called from ONE place (the stream).
     */
    suspend fun addSample(watts: Double) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            lastSampleTime = now
            sampleCount++

            buffers.values.forEach { buffer ->
                buffer.addSample(watts)
            }

            // Persist best values periodically (every 60 samples = ~1 minute at 1Hz)
            if (sampleCount % 60 == 0L) {
                persistBestValues()
            }
        }
    }

    /**
     * Get the best average for a specific duration.
     */
    suspend fun getBestAverage(durationSeconds: Int): Double? {
        mutex.withLock {
            return buffers[durationSeconds]?.getBestAverage()
        }
    }

    /**
     * Get the current rolling average for a specific duration.
     */
    suspend fun getCurrentAverage(durationSeconds: Int): Double? {
        mutex.withLock {
            return buffers[durationSeconds]?.getCurrentAverage()
        }
    }

    /**
     * Check if a buffer is ready (has enough samples).
     */
    suspend fun isReady(durationSeconds: Int): Boolean {
        mutex.withLock {
            return buffers[durationSeconds]?.isReady() ?: false
        }
    }

    /**
     * Get all best averages as a map.
     */
    suspend fun getAllBestAverages(): Map<Int, Double?> {
        mutex.withLock {
            return DURATIONS.associateWith { buffers[it]?.getBestAverage() }
        }
    }

    /**
     * Get all current rolling averages as a map.
     */
    suspend fun getAllCurrentAverages(): Map<Int, Double?> {
        mutex.withLock {
            return DURATIONS.associateWith { buffers[it]?.getCurrentAverage() }
        }
    }

    /**
     * Reset all buffers for a new ride.
     * Includes guard logic to prevent accidental mid-ride resets.
     */
    suspend fun resetForNewRide(force: Boolean = false) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLastSample = now - lastSampleTime
            val rideElapsed = now - rideStartTime

            // Guard: Don't reset if we have recent data and significant ride time
            // Block reset if persisted state hasn't loaded yet (race condition on service restart)
            if (!stateLoaded && !force) {
                logDiagnostic("RESET_DEFERRED: State not yet loaded, blocking reset")
                Log.w(TAG, "Blocking reset - persisted state not yet loaded")
                return@withLock
            }

            // Allow reset if:
            // - force is true (explicit user action)
            // - No samples in last 5 minutes (ride probably ended)
            // - Less than 60 seconds of ride time (just started)
            val shouldReset = force ||
                timeSinceLastSample > 5 * 60 * 1000 ||
                rideElapsed < 60 * 1000

            if (shouldReset) {
                logDiagnostic("RESET: Resetting buffers (force=$force, samples=$sampleCount, " +
                    "timeSinceLastSample=${timeSinceLastSample}ms, rideElapsed=${rideElapsed}ms)")

                buffers.values.forEach { it.reset() }
                rideStartTime = now
                sampleCount = 0L

                // Clear persisted state
                clearPersistedState()
            } else {
                logDiagnostic("RESET_BLOCKED: Ignored reset request (samples=$sampleCount, " +
                    "timeSinceLastSample=${timeSinceLastSample}ms, rideElapsed=${rideElapsed}ms)")
                Log.w(TAG, "Blocked suspicious reset request mid-ride")
            }
        }
    }

    /**
     * Called when service is being destroyed - persist current state.
     */
    suspend fun onServiceDestroy() {
        mutex.withLock {
            logDiagnostic("SERVICE_DESTROY: Persisting state (samples=$sampleCount)")
            persistBestValues()
        }
    }

    private suspend fun persistBestValues() {
        try {
            context.bufferDataStore.edit { prefs ->
                DURATIONS.forEach { duration ->
                    buffers[duration]?.getBestAverage()?.let { best ->
                        prefs[bestAverageKey(duration)] = best
                    }
                }
                prefs[RIDE_START_TIME_KEY] = rideStartTime
                prefs[LAST_SAMPLE_TIME_KEY] = lastSampleTime
                prefs[SAMPLE_COUNT_KEY] = sampleCount
            }
            Log.d(TAG, "Persisted best values to DataStore")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist best values", e)
            logDiagnostic("PERSIST_ERROR: ${e.message}")
        }
    }

    private suspend fun loadPersistedState() {
        try {
            val prefs = context.bufferDataStore.data.first()

            rideStartTime = prefs[RIDE_START_TIME_KEY] ?: 0L
            lastSampleTime = prefs[LAST_SAMPLE_TIME_KEY] ?: 0L
            sampleCount = prefs[SAMPLE_COUNT_KEY] ?: 0L

            // Check if persisted state is still valid (ride not too old)
            val now = System.currentTimeMillis()
            val stateAge = now - lastSampleTime

            // Only restore if state is less than 2 hours old
            if (stateAge < 2 * 60 * 60 * 1000 && lastSampleTime > 0) {
                DURATIONS.forEach { duration ->
                    prefs[bestAverageKey(duration)]?.let { best ->
                        buffers[duration]?.restoreBestAverage(best)
                    }
                }
                logDiagnostic("STATE_RESTORED: Restored state from ${stateAge / 1000}s ago")
                Log.d(TAG, "Restored persisted state from ${stateAge / 1000}s ago")
            } else {
                logDiagnostic("STATE_EXPIRED: Persisted state too old (${stateAge / 1000}s), starting fresh")
                Log.d(TAG, "Persisted state too old, starting fresh")
                clearPersistedState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted state", e)
            logDiagnostic("LOAD_ERROR: ${e.message}")
        } finally {
            // Mark state as loaded (even on error) so reset guard can function
            stateLoaded = true
            logDiagnostic("STATE_LOAD_COMPLETE: stateLoaded=true")
        }
    }

    private suspend fun clearPersistedState() {
        try {
            context.bufferDataStore.edit { prefs ->
                prefs.clear()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear persisted state", e)
        }
    }

    private fun logDiagnostic(message: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val logLine = "[$timestamp] $message\n"
            logFile.appendText(logLine)

            // Keep log file under 100KB by trimming old entries
            if (logFile.length() > 100 * 1024) {
                val lines = logFile.readLines()
                val keepLines = lines.takeLast(500)
                logFile.writeText(keepLines.joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write diagnostic log", e)
        }
    }

    /**
     * Get the diagnostic log contents for debugging.
     */
    fun getDiagnosticLog(): String {
        return try {
            if (logFile.exists()) logFile.readText() else "No diagnostic log available"
        } catch (e: Exception) {
            "Error reading log: ${e.message}"
        }
    }

    /**
     * Clear the diagnostic log.
     */
    fun clearDiagnosticLog() {
        try {
            logFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear diagnostic log", e)
        }
    }
}
