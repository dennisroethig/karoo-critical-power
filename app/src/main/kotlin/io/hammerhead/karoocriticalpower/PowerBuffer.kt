package io.hammerhead.karoocriticalpower

/**
 * Circular buffer for calculating rolling best average power over a specific duration.
 * Uses a running sum for O(1) average calculation.
 *
 * @param durationSeconds The duration in seconds for the rolling average
 * @param sampleRateHz The expected sample rate (default 1Hz)
 */
class PowerBuffer(
    private val durationSeconds: Int,
    private val sampleRateHz: Int = 1
) {
    private val bufferSize = durationSeconds * sampleRateHz
    private val buffer = DoubleArray(bufferSize)
    private var index = 0
    private var count = 0
    private var currentSum = 0.0
    private var bestAverage = 0.0

    /**
     * Add a power sample to the buffer and update the best average if applicable.
     */
    fun addSample(watts: Double) {
        // Subtract old value from sum if buffer is full
        if (count >= bufferSize) {
            currentSum -= buffer[index]
        }

        // Add new value
        buffer[index] = watts
        currentSum += watts
        count = minOf(count + 1, bufferSize)
        index = (index + 1) % bufferSize

        // Update best if buffer is full
        if (count >= bufferSize) {
            val avg = currentSum / bufferSize
            if (avg > bestAverage) {
                bestAverage = avg
            }
        }
    }

    /**
     * Get the best average power recorded, or null if buffer hasn't been filled yet.
     */
    fun getBestAverage(): Double? = if (bestAverage > 0) bestAverage else null

    /**
     * Get the current rolling average, or null if buffer isn't full yet.
     */
    fun getCurrentAverage(): Double? = if (count >= bufferSize) currentSum / bufferSize else null

    /**
     * Clear the rolling window (e.g. after a long gap in the power stream)
     * while keeping the best average recorded so far.
     */
    fun clearWindow() {
        buffer.fill(0.0)
        index = 0
        count = 0
        currentSum = 0.0
    }

    /**
     * Reset the buffer for a new ride.
     */
    fun reset() {
        buffer.fill(0.0)
        index = 0
        count = 0
        currentSum = 0.0
        bestAverage = 0.0
    }

    /**
     * Check if the buffer has enough samples to calculate an average.
     */
    fun isReady(): Boolean = count >= bufferSize

    /**
     * Restore a previously persisted best average.
     * Used when recovering from service restart.
     */
    fun restoreBestAverage(value: Double) {
        if (value > bestAverage) {
            bestAverage = value
        }
    }
}
