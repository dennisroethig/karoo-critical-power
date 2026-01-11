package io.hammerhead.karoocriticalpower.extension

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karoocriticalpower.PowerBufferManager
import io.hammerhead.karoocriticalpower.data.PowerCurveRepository
import io.hammerhead.karoocriticalpower.data.CriticalPowerSettings
import io.hammerhead.karoocriticalpower.data.PrTimeframe
import io.hammerhead.karoocriticalpower.data.SettingsDataStore
import io.hammerhead.karoocriticalpower.datatypes.*
import io.hammerhead.karoocriticalpower.extensions.streamDataFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

class KarooCriticalPowerExtension : KarooExtension("karoo-critical-power", "1.0") {

    companion object {
        private const val TAG = "KarooCriticalPower"
    }

    private lateinit var karooSystem: KarooSystemService
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var powerCurveRepository: PowerCurveRepository
    private lateinit var bufferManager: PowerBufferManager
    private var serviceJob: Job? = null
    private var powerStreamJob: Job? = null
    private var currentSettings: CriticalPowerSettings = CriticalPowerSettings()
    private var rideStateConsumerId: String? = null

    // Callback to check if PR comparison should be shown
    private val showPrComparison: () -> Boolean = { currentSettings.showPrComparison }

    // Callback to get the current PR timeframe setting
    private val getPrTimeframe: () -> PrTimeframe = { currentSettings.prTimeframe }

    // Callback to check if intervals.icu is configured
    private val isIntervalsConfigured: () -> Boolean = { currentSettings.isConfigured }

    // All 11 individual data types - using shared buffer manager
    private val criticalPower5s by lazy { CriticalPower5sDataType(extension, karooSystem, bufferManager, powerCurveRepository, showPrComparison, getPrTimeframe, isIntervalsConfigured) }
    private val criticalPower15s by lazy { CriticalPower15sDataType(extension, karooSystem, bufferManager, powerCurveRepository, showPrComparison, getPrTimeframe, isIntervalsConfigured) }
    private val criticalPower30s by lazy { CriticalPower30sDataType(extension, karooSystem, bufferManager, powerCurveRepository, showPrComparison, getPrTimeframe, isIntervalsConfigured) }
    private val criticalPower1m by lazy { CriticalPower1mDataType(extension, karooSystem, bufferManager, powerCurveRepository, showPrComparison, getPrTimeframe, isIntervalsConfigured) }
    private val criticalPower3m by lazy { CriticalPower3mDataType(extension, karooSystem, bufferManager, powerCurveRepository, showPrComparison, getPrTimeframe, isIntervalsConfigured) }
    private val criticalPower5m by lazy { CriticalPower5mDataType(extension, karooSystem, bufferManager, powerCurveRepository, showPrComparison, getPrTimeframe, isIntervalsConfigured) }
    private val criticalPower20m by lazy { CriticalPower20mDataType(extension, karooSystem, bufferManager, powerCurveRepository, showPrComparison, getPrTimeframe, isIntervalsConfigured) }
    private val criticalPower30m by lazy { CriticalPower30mDataType(extension, karooSystem, bufferManager, powerCurveRepository, showPrComparison, getPrTimeframe, isIntervalsConfigured) }
    private val criticalPower45m by lazy { CriticalPower45mDataType(extension, karooSystem, bufferManager, powerCurveRepository, showPrComparison, getPrTimeframe, isIntervalsConfigured) }
    private val criticalPower1h by lazy { CriticalPower1hDataType(extension, karooSystem, bufferManager, powerCurveRepository, showPrComparison, getPrTimeframe, isIntervalsConfigured) }
    private val criticalPower1h30m by lazy { CriticalPower1h30mDataType(extension, karooSystem, bufferManager, powerCurveRepository, showPrComparison, getPrTimeframe, isIntervalsConfigured) }

    // Power curve overview (all durations as bars) - using shared buffer manager
    private val powerCurveOverview by lazy {
        PowerCurveOverviewDataType(
            extension,
            karooSystem,
            bufferManager,
            powerCurveRepository,
            showPrComparison,
            getPrTimeframe,
            isIntervalsConfigured
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        settingsDataStore = SettingsDataStore(applicationContext)
        powerCurveRepository = PowerCurveRepository(applicationContext)
        bufferManager = PowerBufferManager(applicationContext)

        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.connect { connected ->
                Log.d(TAG, "Karoo system connected: $connected")
            }

            // Listen for ride state changes
            rideStateConsumerId = karooSystem.addConsumer { rideState: RideState ->
                when (rideState) {
                    is RideState.Recording -> {
                        Log.d(TAG, "Ride recording state detected")
                        // Reset buffers for new ride (with guard logic in buffer manager)
                        CoroutineScope(Dispatchers.IO).launch {
                            bufferManager.resetForNewRide()
                            // Fetch fresh PR data if configured
                            if (currentSettings.isConfigured) {
                                fetchPowerCurve()
                            }
                        }
                    }
                    is RideState.Paused -> {
                        Log.d(TAG, "Ride paused")
                    }
                    RideState.Idle -> {
                        Log.d(TAG, "Ride idle/stopped")
                    }
                }
            }

            // Start power stream - this is the SINGLE place where samples are added
            startPowerStream()

            // Load settings and observe changes
            settingsDataStore.settings.collect { settings ->
                currentSettings = settings
                Log.d(TAG, "Settings updated: configured=${settings.isConfigured}")

                // Fetch power curve when settings are configured
                if (settings.isConfigured) {
                    fetchPowerCurve()
                }
            }
        }

        Log.d(TAG, "KarooCriticalPowerExtension created")
    }

    /**
     * Start the power data stream.
     * This is the ONLY place where power samples are added to the buffer manager.
     */
    private fun startPowerStream() {
        powerStreamJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Starting power stream for buffer manager")
            karooSystem.streamDataFlow(DataType.Type.POWER)
                .filterIsInstance<StreamState.Streaming>()
                .catch { e ->
                    Log.e(TAG, "Error in power stream", e)
                }
                .collect { state ->
                    val watts = state.dataPoint.singleValue
                    if (watts != null && watts >= 0) {
                        bufferManager.addSample(watts)
                    }
                }
        }
    }

    private suspend fun fetchPowerCurve() {
        Log.d(TAG, "Fetching power curve from intervals.icu")
        val success = powerCurveRepository.fetchPowerCurve(currentSettings)
        if (success) {
            Log.d(TAG, "Power curve fetched successfully")
        } else {
            Log.w(TAG, "Failed to fetch power curve: ${powerCurveRepository.lastError.value}")
        }
    }

    override fun onDestroy() {
        // Persist buffer state before destroying
        CoroutineScope(Dispatchers.IO).launch {
            bufferManager.onServiceDestroy()
        }

        powerStreamJob?.cancel()
        serviceJob?.cancel()
        rideStateConsumerId?.let { karooSystem.removeConsumer(it) }
        karooSystem.disconnect()
        super.onDestroy()
        Log.d(TAG, "KarooCriticalPowerExtension destroyed")
    }

    override val types by lazy {
        listOf(
            criticalPower5s,
            criticalPower15s,
            criticalPower30s,
            criticalPower1m,
            criticalPower3m,
            criticalPower5m,
            criticalPower20m,
            criticalPower30m,
            criticalPower45m,
            criticalPower1h,
            criticalPower1h30m,
            powerCurveOverview
        )
    }
}
