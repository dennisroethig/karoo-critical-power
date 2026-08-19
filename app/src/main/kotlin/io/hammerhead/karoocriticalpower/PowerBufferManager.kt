package io.hammerhead.karoocriticalpower

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
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
 *
 * The caller (extension service) is responsible for only feeding samples while
 * a ride is recording, and for calling [resetForNewRide] on an Idle -> Recording
 * transition. Gaps in the stream (sensor dropouts, pauses) are zero-filled so
 * rolling windows never stitch non-contiguous efforts together.
 */
class PowerBufferManager(private val context: Context) {

    companion object {
        private const val TAG = "PowerBufferManager"

        // Ignore samples arriving faster than this (buffers assume 1s granularity)
        private const val MIN_SAMPLE_INTERVAL_MS = 500L

        // Persisted state older than this is discarded on restore
        private const val STATE_MAX_AGE_MS = 2 * 60 * 60 * 1000L

        // Keys for persisted state
        private fun bestAverageKey(duration: Int) = doublePreferencesKey("best_avg_$duration")
        private val LAST_SAMPLE_TIME_KEY = longPreferencesKey("last_sample_time")
        private val SAMPLE_COUNT_KEY = longPreferencesKey("sample_count")
    }

    // Single set of buffers for all durations
    private val buffers: Map<Int, PowerBuffer> = Durations.SECONDS.associateWith { PowerBuffer(it) }

    // Mutex for thread-safe access
    private val mutex = Mutex()

    private var lastSampleTime: Long = 0L
    private var sampleCount: Long = 0L

    // Completed once persisted state has been loaded (or load failed);
    // resets wait on this so they can't race the restore.
    private val stateLoaded = CompletableDeferred<Unit>()

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
     * This should only be called from ONE place (the stream) and only while recording.
     */
    suspend fun addSample(watts: Double) {
        mutex.withLock {
            val now = System.currentTimeMillis()

            if (lastSampleTime > 0) {
                val sinceLast = now - lastSampleTime
                // Buffers assume 1 sample per second; drop faster duplicates
                if (sinceLast < MIN_SAMPLE_INTERVAL_MS) return@withLock

                // Zero-fill missed seconds so windows stay time-contiguous
                // (sensor dropouts and pauses must not stitch efforts together)
                val missedSeconds = (sinceLast / 1000L).toInt() - 1
                if (missedSeconds in 1..Durations.MAX_SECONDS) {
                    repeat(missedSeconds) {
                        buffers.values.forEach { it.addSample(0.0) }
                    }
                    logDiagnostic("GAP_FILL: Zero-filled ${missedSeconds}s gap")
                } else if (missedSeconds > Durations.MAX_SECONDS) {
                    // Gap longer than the longest window: clear windows, keep bests
                    buffers.values.forEach { it.clearWindow() }
                    logDiagnostic("GAP_CLEAR: Cleared windows after ${missedSeconds}s gap")
                }
            }

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
     * Reset all buffers for a new ride.
     * Call this only on a genuine Idle -> Recording transition.
     * Waits for persisted-state loading so a reset can't race the restore.
     */
    suspend fun resetForNewRide() {
        stateLoaded.await()
        mutex.withLock {
            logDiagnostic("RESET: Resetting buffers for new ride (samples=$sampleCount)")
            buffers.values.forEach { it.reset() }
            lastSampleTime = 0L
            sampleCount = 0L
            clearPersistedState()
        }
    }

    /**
     * Called when the ride ends (Idle). The persisted state only exists to
     * survive a service restart mid-ride, so it can be discarded now — this
     * prevents a finished ride's bests leaking into the next ride.
     */
    suspend fun onRideEnded() {
        mutex.withLock {
            logDiagnostic("RIDE_ENDED: Clearing persisted state (samples=$sampleCount)")
            clearPersistedState()
        }
    }

    /**
     * Persist current best values now (called on pause and service destroy).
     */
    suspend fun persistNow() {
        mutex.withLock {
            logDiagnostic("PERSIST_NOW: Persisting state (samples=$sampleCount)")
            persistBestValues()
        }
    }

    private suspend fun persistBestValues() {
        try {
            context.bufferDataStore.edit { prefs ->
                Durations.SECONDS.forEach { duration ->
                    buffers[duration]?.getBestAverage()?.let { best ->
                        prefs[bestAverageKey(duration)] = best
                    }
                }
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

            mutex.withLock {
                val persistedLastSample = prefs[LAST_SAMPLE_TIME_KEY] ?: 0L
                val stateAge = System.currentTimeMillis() - persistedLastSample

                if (persistedLastSample > 0 && stateAge < STATE_MAX_AGE_MS) {
                    // Don't clobber live values if samples arrived before the load finished
                    if (lastSampleTime == 0L) {
                        lastSampleTime = persistedLastSample
                        sampleCount = prefs[SAMPLE_COUNT_KEY] ?: 0L
                    }
                    Durations.SECONDS.forEach { duration ->
                        prefs[bestAverageKey(duration)]?.let { best ->
                            buffers[duration]?.restoreBestAverage(best)
                        }
                    }
                    logDiagnostic("STATE_RESTORED: Restored state from ${stateAge / 1000}s ago")
                    Log.d(TAG, "Restored persisted state from ${stateAge / 1000}s ago")
                } else if (persistedLastSample > 0) {
                    logDiagnostic("STATE_EXPIRED: Persisted state too old (${stateAge / 1000}s), starting fresh")
                    Log.d(TAG, "Persisted state too old, starting fresh")
                    clearPersistedState()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted state", e)
            logDiagnostic("LOAD_ERROR: ${e.message}")
        } finally {
            // Mark state as loaded (even on error) so pending resets can proceed
            stateLoaded.complete(Unit)
            logDiagnostic("STATE_LOAD_COMPLETE")
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
}
