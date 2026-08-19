package io.hammerhead.karoocriticalpower

/**
 * Configuration for a power duration.
 *
 * @param seconds Duration in seconds
 * @param idSuffix Suffix used in the data type ID (must match extension_info.xml, never change)
 * @param label Display label used in the overview bars
 */
data class DurationConfig(
    val seconds: Int,
    val idSuffix: String,
    val label: String
)

/**
 * Single source of truth for all supported power durations.
 */
object Durations {
    val ALL = listOf(
        DurationConfig(5, "5s", "5s"),
        DurationConfig(15, "15s", "15s"),
        DurationConfig(30, "30s", "30s"),
        DurationConfig(60, "1m", "1m"),
        DurationConfig(180, "3m", "3m"),
        DurationConfig(300, "5m", "5m"),
        DurationConfig(1200, "20m", "20m"),
        DurationConfig(1800, "30m", "30m"),
        DurationConfig(2700, "45m", "45m"),
        DurationConfig(3600, "1h", "60m"),
        DurationConfig(5400, "1h30m", "90m")
    )

    val SECONDS = ALL.map { it.seconds }

    val MAX_SECONDS = SECONDS.max()
}
