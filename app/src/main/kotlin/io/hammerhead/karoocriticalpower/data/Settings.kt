package io.hammerhead.karoocriticalpower.data

import kotlinx.serialization.Serializable

/**
 * Timeframe options for PR comparison
 */
enum class PrTimeframe(val days: Int?, val displayName: String) {
    ALL_TIME(null, "All Time"),
    DAYS_90(90, "Last 90 Days"),
    DAYS_42(42, "Last 42 Days"),
    PER_RIDE(-1, "Per Ride")
}

/**
 * Settings for the Karoo Critical Power extension
 */
@Serializable
data class CriticalPowerSettings(
    val intervalsApiKey: String = "",
    val intervalsAthleteId: String = "",
    val prTimeframe: PrTimeframe = PrTimeframe.ALL_TIME,
    val showPrComparison: Boolean = true
) {
    val isConfigured: Boolean
        get() = intervalsApiKey.isNotBlank() && intervalsAthleteId.isNotBlank()
}
