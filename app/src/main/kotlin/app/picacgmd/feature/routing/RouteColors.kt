package app.picacgmd.feature.routing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.domain.session.RouteLatencyGrade

/**
 * Latency tones. Green and orange are the theme's success / warning roles, red is the error role;
 * amber sits between them and is tuned per surface brightness so it keeps text contrast in both
 * light (dark ochre) and dark (soft gold) themes.
 */
@Composable
@ReadOnlyComposable
internal fun latencyColor(grade: RouteLatencyGrade): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (grade) {
        RouteLatencyGrade.GOOD -> PikapikaTheme.extendedColors.success
        RouteLatencyGrade.OK -> if (dark) Color(0xFFE9C46A) else Color(0xFF735C00)
        RouteLatencyGrade.SLOW -> PikapikaTheme.extendedColors.warning
        RouteLatencyGrade.FAILED -> MaterialTheme.colorScheme.error
    }
}

/** Three signal bars, filled by grade, so the result never relies on colour alone. */
@Composable
internal fun SignalBars(grade: RouteLatencyGrade, color: Color, modifier: Modifier = Modifier) {
    val filled = when (grade) {
        RouteLatencyGrade.GOOD -> 3
        RouteLatencyGrade.OK -> 2
        RouteLatencyGrade.SLOW -> 1
        RouteLatencyGrade.FAILED -> 0
    }
    val empty = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.size(width = 13.dp, height = 12.dp)) {
        val gap = 2.dp.toPx()
        val barWidth = (size.width - gap * 2) / 3
        val radius = CornerRadius(barWidth / 2, barWidth / 2)
        repeat(3) { index ->
            val height = size.height * (index + 1) / 3f
            drawRoundRect(
                color = if (index < filled) color else empty,
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
                cornerRadius = radius,
            )
        }
    }
}
