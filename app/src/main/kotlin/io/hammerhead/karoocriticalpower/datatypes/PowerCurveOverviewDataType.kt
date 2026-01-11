package io.hammerhead.karoocriticalpower.datatypes

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karoocriticalpower.PowerBufferManager
import io.hammerhead.karoocriticalpower.data.PowerCurveRepository
import io.hammerhead.karoocriticalpower.data.PrTimeframe
import io.hammerhead.karoocriticalpower.views.PowerBarData
import io.hammerhead.karoocriticalpower.views.PowerBarsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Configuration for a power duration.
 */
data class DurationConfig(
    val seconds: Int,
    val label: String
)

/**
 * Data type that displays all 11 power durations as stacked horizontal bars with PR comparison.
 */
class PowerCurveOverviewDataType(
    extensionId: String,
    private val karooSystem: KarooSystemService,
    private val bufferManager: PowerBufferManager,
    private val powerCurveRepository: PowerCurveRepository,
    private val showPrComparison: () -> Boolean,
    private val getPrTimeframe: () -> PrTimeframe,
    private val isIntervalsConfigured: () -> Boolean
) : DataTypeImpl(extensionId, "power-curve-overview") {

    companion object {
        private const val TAG = "PowerCurveOverview"

        val DURATIONS = listOf(
            DurationConfig(5, "5s"),
            DurationConfig(15, "15s"),
            DurationConfig(30, "30s"),
            DurationConfig(60, "1m"),
            DurationConfig(180, "3m"),
            DurationConfig(300, "5m"),
            DurationConfig(1200, "20m"),
            DurationConfig(1800, "30m"),
            DurationConfig(2700, "45m"),
            DurationConfig(3600, "60m"),
            DurationConfig(5400, "90m")
        )
    }

    private var streamJob: Job? = null
    private var viewJob: Job? = null

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private val glance = GlanceRemoteViews()

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d(TAG, "Starting stream for power-curve-overview")

        val scope = CoroutineScope(Dispatchers.IO)
        streamJob = scope.launch {
            // Poll the buffer manager for updates
            // The extension handles adding samples, we just read
            while (true) {
                try {
                    // Check if at least the shortest duration has data
                    val shortestBest = bufferManager.getBestAverage(DURATIONS.first().seconds)

                    if (shortestBest != null) {
                        // Build data fields for the stream
                        val dataFields = mutableMapOf<String, Double>()
                        for (config in DURATIONS) {
                            bufferManager.getBestAverage(config.seconds)?.let { best ->
                                dataFields["best_${config.seconds}"] = best
                            }
                        }

                        emitter.onNext(
                            StreamState.Streaming(
                                DataPoint(dataTypeId, dataFields)
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
            Log.d(TAG, "Cancelling stream for power-curve-overview")
            streamJob?.cancel()
            streamJob = null
        }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting view for power-curve-overview")
        Log.d(TAG, "ViewConfig: $config")
        Log.d(TAG, "ViewConfig grid: ${config.gridSize}, viewSize: ${config.viewSize}")

        // Get view width in dp (viewSize is in pixels, assume ~2x density for Karoo 3)
        val density = context.resources.displayMetrics.density
        val viewWidthDp = (config.viewSize.first / density).toInt()
        Log.d(TAG, "View width: ${config.viewSize.first}px, density: $density, widthDp: $viewWidthDp")

        val scope = CoroutineScope(Dispatchers.Main)
        viewJob = scope.launch {
            // Helper to build bar data from current state
            suspend fun buildBars(): List<PowerBarData> {
                val prTimeframe = getPrTimeframe()
                val isConfigured = isIntervalsConfigured()
                // Use per-ride mode if explicitly selected OR if intervals.icu is not configured (fallback)
                val usePerRideMode = prTimeframe == PrTimeframe.PER_RIDE || !isConfigured

                return DURATIONS.map { durationConfig ->
                    val bestAverage = bufferManager.getBestAverage(durationConfig.seconds)?.toInt()
                    val currentRolling = bufferManager.getCurrentAverage(durationConfig.seconds)?.toInt()

                    // Determine what to show based on mode
                    val (currentPower, referencePower) = if (usePerRideMode) {
                        // Per Ride mode: show current rolling average vs current ride's best
                        currentRolling to bestAverage
                    } else {
                        // PR mode: show current ride's best vs historical PR
                        val historicalPr = if (showPrComparison()) {
                            powerCurveRepository.getPrForDuration(durationConfig.seconds)?.toInt()
                        } else null
                        bestAverage to historicalPr
                    }

                    val percentage = when {
                        currentPower == null || referencePower == null || referencePower <= 0 -> 0f
                        else -> currentPower.toFloat() / referencePower.toFloat()
                    }

                    PowerBarData(
                        label = durationConfig.label,
                        durationSeconds = durationConfig.seconds,
                        currentPower = currentPower,
                        prPower = referencePower,
                        percentage = percentage
                    )
                }
            }

            // Helper to render the view
            suspend fun renderView() {
                try {
                    val bars = buildBars()
                    // Use a large size to ensure all bars can render
                    // Karoo full screen is approximately 480x800, use generous sizing
                    val result = glance.compose(context, DpSize(400.dp, 600.dp)) {
                        PowerBarsView(bars = bars, viewWidthDp = viewWidthDp)
                    }
                    emitter.updateView(result.remoteViews)
                } catch (e: Exception) {
                    Log.e(TAG, "Error composing view", e)
                }
            }

            // Emit initial view
            renderView()

            // Observe PR data changes
            launch {
                powerCurveRepository.powerCurve
                    .drop(1)
                    .filterNotNull()
                    .collect {
                        Log.d(TAG, "PR data loaded, re-rendering")
                        renderView()
                    }
            }

            // Poll buffer manager for updates (no adding samples - just reading)
            while (true) {
                try {
                    renderView()
                    // Update at 1Hz
                    delay(1000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in view loop", e)
                    delay(1000)
                }
            }
        }

        emitter.setCancellable {
            Log.d(TAG, "Cancelling view for power-curve-overview")
            viewJob?.cancel()
            viewJob = null
        }
    }
}
