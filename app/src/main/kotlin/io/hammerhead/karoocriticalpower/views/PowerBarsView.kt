package io.hammerhead.karoocriticalpower.views

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.hammerhead.karoocriticalpower.R

/**
 * Data class representing a single power bar's state.
 */
data class PowerBarData(
    val label: String,
    val durationSeconds: Int,
    val currentPower: Int?,
    val prPower: Int?,
    val percentage: Float // 0.0 to 1.0+, can exceed 1.0 if current > PR
)

// Layout constants
private const val LABEL_WIDTH_DP = 40
private const val LABEL_SPACING_DP = 4
private const val BAR_PADDING_DP = 8  // 4dp padding on each side

/**
 * Color scheme for the power bars.
 */
private object BarColors {
    val background = Color(0xFFE0E0E0)    // Light gray
    val belowPr = Color(0xFFFFC107)        // Yellow/Amber
    val atOrAbovePr = Color(0xFF4CAF50)    // Green
    val textColor = Color.Black
    val textColorOnDark = Color.White
    val prTextColor = Color(0xFF424242)    // Dark gray for visibility
    val noDataBackground = Color(0xFFBDBDBD)    // Darker gray for no-data state
}

/**
 * Glance composable that displays all power durations as stacked horizontal bars.
 *
 * @param bars List of power bar data for each duration
 * @param viewWidthDp The available view width in dp (from ViewConfig)
 */
@Composable
fun PowerBarsView(
    bars: List<PowerBarData>,
    viewWidthDp: Int
) {
    // Calculate the available width for the bar fill area
    // Total width - outer padding (4dp each side) - label - label spacing
    val barAreaWidthDp = viewWidthDp - BAR_PADDING_DP - LABEL_WIDTH_DP - LABEL_SPACING_DP

    // Glance Column has a 10 element limit, so we split into two columns
    val firstHalf = bars.take(6)
    val secondHalf = bars.drop(6)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(4.dp)
    ) {
        // First column with first 6 bars
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .defaultWeight()
        ) {
            firstHalf.forEach { bar ->
                PowerBarRow(bar, barAreaWidthDp)
            }
        }

        // Second column with remaining 5 bars
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .defaultWeight()
        ) {
            secondHalf.forEach { bar ->
                PowerBarRow(bar, barAreaWidthDp)
            }
        }
    }
}

/**
 * A single horizontal bar showing duration label, colored status bar, and values.
 *
 * Visual design:
 * - Green fill when current >= PR (beating or matching PR)
 * - Yellow fill proportional to percentage when current < PR
 * - Gray background when no data or no PR available
 * - Shows current value / PR value with percentage indicator
 *
 * @param bar The power bar data
 * @param barAreaWidthDp The available width for the bar area in dp
 */
@Composable
private fun PowerBarRow(bar: PowerBarData, barAreaWidthDp: Int) {
    // Determine fill color and width based on status
    val fillColor: Color
    val fillWidthDp: Int

    when {
        bar.currentPower == null -> {
            // No current data yet
            fillColor = BarColors.noDataBackground
            fillWidthDp = 0
        }
        bar.prPower == null || bar.prPower <= 0 -> {
            // Has current data but no PR - show small indicator
            fillColor = BarColors.noDataBackground
            fillWidthDp = 8
        }
        bar.currentPower >= bar.prPower -> {
            // At or above PR - full bar, green
            fillColor = BarColors.atOrAbovePr
            fillWidthDp = barAreaWidthDp
        }
        else -> {
            // Below PR - proportional fill, yellow
            fillColor = BarColors.belowPr
            val percentage = (bar.currentPower.toFloat() / bar.prPower.toFloat()).coerceIn(0f, 1f)
            fillWidthDp = (barAreaWidthDp * percentage).toInt().coerceAtLeast(4)
        }
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(31.dp)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Duration label (fixed width)
        // Uses a day/night color resource because this label sits directly on the
        // Karoo screen background, not on a bar fill — black would be unreadable
        // on dark mode's dark background.
        Text(
            text = bar.label,
            style = TextStyle(
                color = ColorProvider(R.color.power_bar_label),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            ),
            modifier = GlanceModifier.width(40.dp)
        )

        Spacer(modifier = GlanceModifier.width(4.dp))

        // Bar container with gray background
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .background(ColorProvider(BarColors.background)),
            contentAlignment = Alignment.CenterStart
        ) {
            // Colored fill bar with calculated width
            if (fillWidthDp > 0) {
                Box(
                    modifier = GlanceModifier
                        .width(fillWidthDp.dp)
                        .fillMaxHeight()
                        .background(ColorProvider(fillColor))
                ) {}
            }

            // Values overlay
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.Horizontal.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Current power value
                Text(
                    text = if (bar.currentPower != null) "${bar.currentPower} W" else "--",
                    style = TextStyle(
                        color = ColorProvider(BarColors.textColor),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                )

                Spacer(modifier = GlanceModifier.defaultWeight())

                // PR value (if available)
                Text(
                    text = if (bar.prPower != null) "${bar.prPower} W" else "--",
                    style = TextStyle(
                        color = ColorProvider(BarColors.prTextColor),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal
                    )
                )
            }
        }
    }
}
