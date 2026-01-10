package io.hammerhead.karoocriticalpower.views

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.hammerhead.karooext.models.ViewConfig

/**
 * Glance composable that displays power with optional PR comparison.
 *
 * Shows: "285 / PR: 310" in a horizontal layout
 */
@Composable
fun PowerWithPrView(
    power: Int?,
    pr: Int?,
    dataAlignment: ViewConfig.Alignment
) {
    // Determine text color - green if beating PR, black otherwise
    val textColor = if (power != null && pr != null && power >= pr) {
        ColorProvider(Color(0xFF2E7D32)) // Darker green when beating PR
    } else {
        ColorProvider(Color.Black) // Black otherwise
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        // Main power value (centered based on dataAlignment)
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            horizontalAlignment = when (dataAlignment) {
                ViewConfig.Alignment.LEFT -> Alignment.Horizontal.Start
                ViewConfig.Alignment.CENTER -> Alignment.Horizontal.CenterHorizontally
                ViewConfig.Alignment.RIGHT -> Alignment.Horizontal.End
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (power != null) "${power} W" else "--",
                style = TextStyle(
                    color = textColor,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        // PR comparison (if available) - positioned in top right corner
        if (pr != null) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.TopEnd
            ) {
                val prColor = if (power != null && power >= pr) {
                    ColorProvider(Color(0xFF4CAF50)) // Green when beating PR
                } else {
                    ColorProvider(Color(0xFF757575)) // Gray otherwise
                }

                Text(
                    text = "${pr} W",
                    style = TextStyle(
                        color = prColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                )
            }
        }
    }
}
