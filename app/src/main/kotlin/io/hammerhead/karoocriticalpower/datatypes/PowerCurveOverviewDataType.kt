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
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karoocriticalpower.PowerBuffer
import io.hammerhead.karoocriticalpower.data.PowerCurveRepository
import io.hammerhead.karoocriticalpower.extensions.streamDataFlow
import io.hammerhead.karoocriticalpower.views.PowerBarData
import io.hammerhead.karoocriticalpower.views.PowerBarsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
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
    private val powerCurveRepository: PowerCurveRepository,
    private val showPrComparison: () -> Boolean
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

    // Create a PowerBuffer for each duration
    private val powerBuffers: Map<Int, PowerBuffer> = DURATIONS.associate { config ->
        config.seconds to PowerBuffer(config.seconds)
    }

    private var streamJob: Job? = null
    private var viewJob: Job? = null

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private val glance = GlanceRemoteViews()

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d(TAG, "Starting stream for power-curve-overview")

        val scope = CoroutineScope(Dispatchers.IO)
        streamJob = scope.launch {
            karooSystem.streamDataFlow(DataType.Type.POWER)
                .filterIsInstance<StreamState.Streaming>()
                .catch { e ->
                    Log.e(TAG, "Error streaming power data", e)
                    emitter.onNext(StreamState.NotAvailable)
                }
                .collect { state ->
                    val watts = state.dataPoint.singleValue
                    if (watts != null && watts >= 0) {
                        // Feed all buffers with the power sample
                        powerBuffers.values.forEach { buffer ->
                            buffer.addSample(watts)
                        }

                        // Check if at least the shortest duration has data
                        val shortestBuffer = powerBuffers[DURATIONS.first().seconds]
                        if (shortestBuffer?.getBestAverage() != null) {
                            // Build data fields for the stream
                            val dataFields = mutableMapOf<String, Double>()
                            DURATIONS.forEach { config ->
                                powerBuffers[config.seconds]?.getBestAverage()?.let { best ->
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
            fun buildBars(): List<PowerBarData> {
                return DURATIONS.map { durationConfig ->
                    val currentPower = powerBuffers[durationConfig.seconds]?.getBestAverage()?.toInt()
                    val prPower = if (showPrComparison()) {
                        powerCurveRepository.getPrForDuration(durationConfig.seconds)?.toInt()
                    } else null

                    val percentage = when {
                        currentPower == null || prPower == null || prPower <= 0 -> 0f
                        else -> currentPower.toFloat() / prPower.toFloat()
                    }

                    PowerBarData(
                        label = durationConfig.label,
                        durationSeconds = durationConfig.seconds,
                        currentPower = currentPower,
                        prPower = prPower,
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

            // Collect power data updates and re-render
            karooSystem.streamDataFlow(DataType.Type.POWER)
                .filterIsInstance<StreamState.Streaming>()
                .catch { e ->
                    Log.e(TAG, "Error in view stream", e)
                }
                .collect { state ->
                    val watts = state.dataPoint.singleValue
                    if (watts != null && watts >= 0) {
                        // Update all buffers
                        powerBuffers.values.forEach { buffer ->
                            buffer.addSample(watts)
                        }
                        // Re-render with updated data
                        renderView()
                    }
                }
        }

        emitter.setCancellable {
            Log.d(TAG, "Cancelling view for power-curve-overview")
            viewJob?.cancel()
            viewJob = null
        }
    }

    /**
     * Reset all power buffers (call at start of new ride).
     */
    fun reset() {
        powerBuffers.values.forEach { it.reset() }
    }
}
