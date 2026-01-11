package io.hammerhead.karoocriticalpower.datatypes

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karoocriticalpower.PowerBufferManager
import io.hammerhead.karoocriticalpower.data.PowerCurveRepository
import io.hammerhead.karoocriticalpower.data.PrTimeframe
import io.hammerhead.karoocriticalpower.views.PowerWithPrView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Base data type for displaying best power over a specific duration.
 *
 * @param extensionId The extension ID
 * @param karooSystem The Karoo system service for subscribing to power data
 * @param durationSeconds The duration in seconds for best power calculation
 * @param typeIdSuffix The suffix for the type ID (e.g., "5s", "1m", "20m")
 * @param bufferManager Shared buffer manager for power data
 * @param powerCurveRepository Repository for PR data from intervals.icu
 * @param showPrComparison Function to check if PR comparison should be shown
 */
abstract class CriticalPowerDataType(
    extensionId: String,
    private val karooSystem: KarooSystemService,
    private val durationSeconds: Int,
    typeIdSuffix: String,
    private val bufferManager: PowerBufferManager,
    private val powerCurveRepository: PowerCurveRepository,
    private val showPrComparison: () -> Boolean,
    private val getPrTimeframe: () -> PrTimeframe,
    private val isIntervalsConfigured: () -> Boolean
) : DataTypeImpl(extensionId, "critical-power-$typeIdSuffix") {

    companion object {
        private const val TAG = "CriticalPowerDataType"

        // Custom field for PR value
        const val FIELD_PR = "pr"
    }

    private var streamJob: Job? = null
    private var viewJob: Job? = null

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private val glance = GlanceRemoteViews()

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d(TAG, "Starting stream for critical-power-$durationSeconds")

        val scope = CoroutineScope(Dispatchers.IO)
        streamJob = scope.launch {
            // Poll the buffer manager for updates
            // The extension handles adding samples, we just read
            while (true) {
                try {
                    val bestPower = bufferManager.getBestAverage(durationSeconds)

                    if (bestPower != null) {
                        val dataFields = mutableMapOf<String, Double>(
                            DataType.Field.SINGLE to bestPower
                        )

                        // Add PR value if available and enabled
                        if (showPrComparison()) {
                            val prWatts = powerCurveRepository.getPrForDuration(durationSeconds)
                            if (prWatts != null) {
                                dataFields[FIELD_PR] = prWatts
                            }
                        }

                        emitter.onNext(
                            StreamState.Streaming(
                                DataPoint(
                                    dataTypeId,
                                    dataFields
                                )
                            )
                        )
                    } else {
                        emitter.onNext(StreamState.Searching)
                    }

                    // Update at 1Hz
                    delay(1000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in stream loop", e)
                    emitter.onNext(StreamState.NotAvailable)
                    delay(1000)
                }
            }
        }

        emitter.setCancellable {
            Log.d(TAG, "Cancelling stream for critical-power-$durationSeconds")
            streamJob?.cancel()
            streamJob = null
        }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting view for critical-power-$durationSeconds")

        // Track current best power for PR observation re-renders
        var currentBestPower: Int? = null

        val scope = CoroutineScope(Dispatchers.Main)
        viewJob = scope.launch {
            // Helper to render the view
            suspend fun renderView(power: Int?, pr: Int?) {
                try {
                    val result = glance.compose(context, DpSize.Unspecified) {
                        PowerWithPrView(
                            power = power,
                            pr = pr,
                            dataAlignment = config.alignment
                        )
                    }
                    emitter.updateView(result.remoteViews)
                } catch (e: Exception) {
                    Log.e(TAG, "Error composing view", e)
                }
            }

            // Emit initial view immediately
            val initialPr = powerCurveRepository.getPrForDuration(durationSeconds)?.toInt()
            Log.d(TAG, "Initial view for $durationSeconds: power=null, pr=$initialPr")
            renderView(null, initialPr)

            // Observe PR data changes - when power curve loads/updates, re-render
            // Use drop(1) to skip initial value (already handled above)
            launch {
                powerCurveRepository.powerCurve
                    .drop(1)
                    .filterNotNull()
                    .collect {
                        val pr = powerCurveRepository.getPrForDuration(durationSeconds)?.toInt()
                        Log.d(TAG, "PR data loaded, re-rendering $durationSeconds: power=$currentBestPower, pr=$pr")
                        renderView(currentBestPower, pr)
                    }
            }

            // Poll buffer manager for updates (no adding samples - just reading)
            while (true) {
                try {
                    val bestPower = bufferManager.getBestAverage(durationSeconds)?.toInt()
                    val currentRolling = bufferManager.getCurrentAverage(durationSeconds)?.toInt()
                    currentBestPower = bestPower

                    // Check if we should use per-ride mode
                    val prTimeframe = getPrTimeframe()
                    val isConfigured = isIntervalsConfigured()
                    val usePerRideMode = prTimeframe == PrTimeframe.PER_RIDE || !isConfigured

                    val (displayPower, referencePower) = if (usePerRideMode) {
                        // Per Ride mode: show current rolling average vs current ride's best
                        currentRolling to bestPower
                    } else {
                        // PR mode: show current ride's best vs historical PR
                        val showPr = showPrComparison()
                        val historicalPr = if (showPr) powerCurveRepository.getPrForDuration(durationSeconds)?.toInt() else null
                        bestPower to historicalPr
                    }

                    Log.d(TAG, "Power update for $durationSeconds: power=$displayPower, ref=$referencePower, perRide=$usePerRideMode")
                    renderView(displayPower, referencePower)

                    // Update at 1Hz
                    delay(1000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in view loop", e)
                    delay(1000)
                }
            }
        }

        emitter.setCancellable {
            Log.d(TAG, "Cancelling view for critical-power-$durationSeconds")
            viewJob?.cancel()
            viewJob = null
        }
    }
}
