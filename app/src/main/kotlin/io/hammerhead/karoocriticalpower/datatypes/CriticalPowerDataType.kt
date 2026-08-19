package io.hammerhead.karoocriticalpower.datatypes

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Data type for displaying best power over a specific duration.
 * One instance per duration, all backed by the shared buffer manager.
 *
 * @param extensionId The extension ID
 * @param durationSeconds The duration in seconds for best power calculation
 * @param typeIdSuffix The suffix for the type ID (e.g., "5s", "1m", "20m")
 * @param bufferManager Shared buffer manager for power data
 * @param powerCurveRepository Repository for PR data from intervals.icu
 * @param showPrComparison Function to check if PR comparison should be shown
 */
class CriticalPowerDataType(
    extensionId: String,
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

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private val glance = GlanceRemoteViews()

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d(TAG, "Starting stream for critical-power-$durationSeconds")

        // Karoo may start several streams for the same data type; each gets its
        // own job, and the cancellable must cancel exactly this one
        val job = CoroutineScope(Dispatchers.IO).launch {
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in stream loop", e)
                    emitter.onNext(StreamState.NotAvailable)
                }

                // Update at 1Hz
                delay(1000)
            }
        }

        emitter.setCancellable {
            Log.d(TAG, "Cancelling stream for critical-power-$durationSeconds")
            job.cancel()
        }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting view for critical-power-$durationSeconds")

        val job = CoroutineScope(Dispatchers.Main).launch {
            // Skip re-composing when nothing changed - Glance composition and
            // the RemoteViews IPC are expensive on the Karoo at 1Hz
            var lastRendered: Pair<Int?, Int?>? = null

            // Poll buffer manager for updates (no adding samples - just reading).
            // PR data is re-read every tick, so a PR load/update is picked up
            // within a second without a separate observer.
            while (true) {
                try {
                    val bestPower = bufferManager.getBestAverage(durationSeconds)?.toInt()
                    val currentRolling = bufferManager.getCurrentAverage(durationSeconds)?.toInt()

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

                    val toRender = displayPower to referencePower
                    if (toRender != lastRendered) {
                        Log.d(TAG, "Rendering $durationSeconds: power=$displayPower, ref=$referencePower, perRide=$usePerRideMode")
                        val result = glance.compose(context, DpSize.Unspecified) {
                            PowerWithPrView(
                                power = displayPower,
                                pr = referencePower,
                                dataAlignment = config.alignment
                            )
                        }
                        emitter.updateView(result.remoteViews)
                        lastRendered = toRender
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in view loop", e)
                }

                // Update at 1Hz
                delay(1000)
            }
        }

        emitter.setCancellable {
            Log.d(TAG, "Cancelling view for critical-power-$durationSeconds")
            job.cancel()
        }
    }
}
