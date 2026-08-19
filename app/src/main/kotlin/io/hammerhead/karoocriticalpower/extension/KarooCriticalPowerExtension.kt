package io.hammerhead.karoocriticalpower.extension

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karoocriticalpower.Durations
import io.hammerhead.karoocriticalpower.PowerBufferManager
import io.hammerhead.karoocriticalpower.data.ApiError
import io.hammerhead.karoocriticalpower.data.PowerCurveRepository
import io.hammerhead.karoocriticalpower.data.CriticalPowerSettings
import io.hammerhead.karoocriticalpower.data.PrTimeframe
import io.hammerhead.karoocriticalpower.data.SettingsDataStore
import io.hammerhead.karoocriticalpower.datatypes.CriticalPowerDataType
import io.hammerhead.karoocriticalpower.datatypes.PowerCurveOverviewDataType
import io.hammerhead.karoocriticalpower.extensions.streamDataFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class KarooCriticalPowerExtension : KarooExtension("karoo-critical-power", "1.0") {

    companion object {
        private const val TAG = "KarooCriticalPower"
        private const val STREAM_RETRY_DELAY_MS = 5_000L
        private const val FETCH_RETRY_DELAY_MS = 5 * 60_000L
        private const val MAX_FETCH_RETRIES = 6
        private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }

    private lateinit var karooSystem: KarooSystemService
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var powerCurveRepository: PowerCurveRepository
    private lateinit var bufferManager: PowerBufferManager

    // All work is launched in this scope so onDestroy can cancel everything at once
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentSettings: CriticalPowerSettings = CriticalPowerSettings()

    // Samples are only fed to the buffers while recording
    @Volatile
    private var isRecording: Boolean = false

    // Previous ride state; null until the first event so a mid-ride service
    // restart (first observed state = Recording) is not treated as a new ride
    private var lastRideState: RideState? = null

    private var rideStateConsumerId: String? = null

    private var fetchRetryJob: Job? = null
    private var fetchRetryAttempts = 0

    // Callback to check if PR comparison should be shown
    private val showPrComparison: () -> Boolean = { currentSettings.showPrComparison }

    // Callback to get the current PR timeframe setting
    private val getPrTimeframe: () -> PrTimeframe = { currentSettings.prTimeframe }

    // Callback to check if intervals.icu is configured
    private val isIntervalsConfigured: () -> Boolean = { currentSettings.isConfigured }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        settingsDataStore = SettingsDataStore(applicationContext)
        powerCurveRepository = PowerCurveRepository.getInstance(applicationContext)
        bufferManager = PowerBufferManager(applicationContext)

        serviceScope.launch {
            karooSystem.connect { connected ->
                Log.d(TAG, "Karoo system connected: $connected")
            }

            // Listen for ride state changes
            rideStateConsumerId = karooSystem.addConsumer { rideState: RideState ->
                onRideStateChanged(rideState)
            }

            // Start power stream - this is the SINGLE place where samples are added
            startPowerStream()

            // Load settings and observe changes
            var firstEmission = true
            settingsDataStore.settings.collect { settings ->
                val previous = currentSettings
                currentSettings = settings
                Log.d(TAG, "Settings updated: configured=${settings.isConfigured}")

                if (firstEmission) {
                    firstEmission = false
                    // Service start: only hit the network if the cache is stale
                    if (settings.isConfigured) {
                        val success = powerCurveRepository.fetchIfStale(settings, CACHE_MAX_AGE_MS)
                        if (!success) scheduleFetchRetry()
                    }
                    return@collect
                }

                // Refetch only when something affecting the power curve changed
                val fetchConfigChanged = previous.intervalsApiKey != settings.intervalsApiKey ||
                    previous.intervalsAthleteId != settings.intervalsAthleteId ||
                    previous.prTimeframe != settings.prTimeframe
                if (settings.isConfigured && fetchConfigChanged) {
                    fetchPowerCurve()
                }
            }
        }

        Log.d(TAG, "KarooCriticalPowerExtension created")
    }

    private fun onRideStateChanged(rideState: RideState) {
        val previous = lastRideState
        lastRideState = rideState
        isRecording = rideState is RideState.Recording

        when (rideState) {
            is RideState.Recording -> {
                Log.d(TAG, "Ride recording (previous=$previous)")
                // Only an Idle -> Recording transition is a new ride; resuming
                // from pause or a mid-ride service restart must NOT reset
                if (previous is RideState.Idle) {
                    serviceScope.launch {
                        bufferManager.resetForNewRide()
                        // Fetch fresh PR data if configured
                        if (currentSettings.isConfigured) {
                            fetchPowerCurve()
                        }
                    }
                }
            }
            is RideState.Paused -> {
                Log.d(TAG, "Ride paused")
                serviceScope.launch { bufferManager.persistNow() }
            }
            RideState.Idle -> {
                Log.d(TAG, "Ride idle/stopped")
                // Ride over - drop the restart-recovery state so it can't
                // leak into the next ride
                serviceScope.launch { bufferManager.onRideEnded() }
            }
        }
    }

    /**
     * Start the power data stream.
     * This is the ONLY place where power samples are added to the buffer manager.
     * Restarts the stream if it fails or completes so one error can't silently
     * stop sample collection for the rest of the ride.
     */
    private fun startPowerStream() {
        serviceScope.launch {
            while (isActive) {
                try {
                    Log.d(TAG, "Starting power stream for buffer manager")
                    karooSystem.streamDataFlow(DataType.Type.POWER)
                        .filterIsInstance<StreamState.Streaming>()
                        .collect { state ->
                            val watts = state.dataPoint.singleValue
                            if (watts != null && watts >= 0 && isRecording) {
                                bufferManager.addSample(watts)
                            }
                        }
                    Log.w(TAG, "Power stream completed, restarting in ${STREAM_RETRY_DELAY_MS}ms")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in power stream, restarting in ${STREAM_RETRY_DELAY_MS}ms", e)
                }
                delay(STREAM_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun fetchPowerCurve() {
        Log.d(TAG, "Fetching power curve from intervals.icu")
        fetchRetryJob?.cancel()
        fetchRetryAttempts = 0
        val success = powerCurveRepository.fetchPowerCurve(currentSettings)
        if (success) {
            Log.d(TAG, "Power curve fetched successfully")
            fetchRetryAttempts = 0
        } else {
            Log.w(TAG, "Failed to fetch power curve: ${powerCurveRepository.lastError.value}")
            scheduleFetchRetry()
        }
    }

    /**
     * Retry a failed fetch when the failure was network-related (the common
     * case: garage with no WiFi at ride start). Capped so we don't ping
     * forever on a ride with genuinely no connectivity.
     */
    private fun scheduleFetchRetry() {
        if (powerCurveRepository.lastError.value !is ApiError.NetworkError) return
        if (fetchRetryJob?.isActive == true) return

        fetchRetryJob = serviceScope.launch {
            while (fetchRetryAttempts < MAX_FETCH_RETRIES) {
                delay(FETCH_RETRY_DELAY_MS)
                if (!currentSettings.isConfigured) return@launch
                fetchRetryAttempts++
                Log.d(TAG, "Retrying power curve fetch (attempt $fetchRetryAttempts)")
                val success = powerCurveRepository.fetchPowerCurve(currentSettings)
                if (success) {
                    fetchRetryAttempts = 0
                    return@launch
                }
                // Stop retrying on non-network failures (bad credentials etc.)
                if (powerCurveRepository.lastError.value !is ApiError.NetworkError) return@launch
            }
            Log.w(TAG, "Giving up on power curve fetch after $fetchRetryAttempts retries")
        }
    }

    override fun onDestroy() {
        // Persist buffer state before the process can be killed; bounded so
        // onDestroy can't hang on a slow DataStore write
        runBlocking {
            withTimeoutOrNull(2_000) { bufferManager.persistNow() }
        }

        serviceScope.cancel()
        rideStateConsumerId?.let { karooSystem.removeConsumer(it) }
        karooSystem.disconnect()
        super.onDestroy()
        Log.d(TAG, "KarooCriticalPowerExtension destroyed")
    }

    override val types by lazy {
        Durations.ALL.map { duration ->
            CriticalPowerDataType(
                extension,
                duration.seconds,
                duration.idSuffix,
                bufferManager,
                powerCurveRepository,
                showPrComparison,
                getPrTimeframe,
                isIntervalsConfigured
            )
        } + PowerCurveOverviewDataType(
            extension,
            bufferManager,
            powerCurveRepository,
            showPrComparison,
            getPrTimeframe,
            isIntervalsConfigured
        )
    }
}
