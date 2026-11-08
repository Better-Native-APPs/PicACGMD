package app.picacgmd.feature.reader.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import app.picacgmd.feature.reader.R
import app.picacgmd.feature.reader.TapMode
import app.picacgmd.feature.reader.logic.PagingAxis
import app.picacgmd.feature.reader.logic.TapZone
import app.picacgmd.feature.reader.logic.TapZoneArea
import app.picacgmd.feature.reader.logic.threeZoneAreas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val OVERLAY_SHOW_MILLIS = 4_000L

private object ZoneColors {
    val Next = Color(0x9EE8467C)
    val Previous = Color(0x9E2F8F83)
    val Menu = Color(0x9E3C3C46)
}

/** Remembers which tap modes have already shown their overlay. One-time hints are not settings. */
internal class TapOverlayMemory(context: Context) {
    private val appContext = context.applicationContext

    private fun preferences() = appContext.getSharedPreferences("reader_tap_overlay", Context.MODE_PRIVATE)

    suspend fun isSeen(mode: TapMode): Boolean = withContext(Dispatchers.IO) {
        preferences().getBoolean(mode.name, false)
    }

    suspend fun markSeen(mode: TapMode) = withContext(Dispatchers.IO) {
        preferences().edit { putBoolean(mode.name, true) }
    }
}

/**
 * 分区图: shows what each part of the screen does. It appears once for each tap mode and when asked
 * for from the settings sheet, and goes away on a tap or by itself.
 *
 * @param stripLabels the vertical zones scroll a strip by a screen, rather than turning a page.
 */
@Composable
internal fun TapZoneOverlay(
    visible: Boolean,
    tapMode: TapMode,
    axis: PagingAxis,
    rightToLeft: Boolean,
    alwaysRightIsNext: Boolean,
    stripLabels: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(visible) {
        if (visible) {
            delay(OVERLAY_SHOW_MILLIS)
            latestOnDismiss()
        }
    }
    AnimatedVisibility(visible = visible, modifier = modifier, enter = readerFadeIn(), exit = readerFadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { latestOnDismiss() } },
        ) {
            if (tapMode == TapMode.ThreeZones) {
                val areas = remember(axis, rightToLeft, alwaysRightIsNext) {
                    threeZoneAreas(axis, rightToLeft, alwaysRightIsNext)
                }
                if (axis == PagingAxis.Horizontal) {
                    Row(Modifier.fillMaxSize()) {
                        areas.forEachIndexed { position, area -> HorizontalZone(area, position, stripLabels) }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        areas.forEachIndexed { position, area -> VerticalZone(area, position, stripLabels) }
                    }
                }
            } else {
                GestureLegend(tapMode)
            }
        }
    }
}

@Composable
private fun RowScope.HorizontalZone(area: TapZoneArea, position: Int, stripLabels: Boolean) {
    val icon = when (position) {
        0 -> Icons.Rounded.ChevronLeft
        1 -> Icons.Rounded.Menu
        else -> Icons.Rounded.ChevronRight
    }
    ZoneContent(area.zone, icon, stripLabels, Modifier.weight(area.right - area.left).fillMaxHeight())
}

@Composable
private fun ColumnScope.VerticalZone(area: TapZoneArea, position: Int, stripLabels: Boolean) {
    val icon = when (position) {
        0 -> Icons.Rounded.KeyboardArrowUp
        1 -> Icons.Rounded.Menu
        else -> Icons.Rounded.KeyboardArrowDown
    }
    ZoneContent(area.zone, icon, stripLabels, Modifier.weight(area.bottom - area.top).fillMaxWidth())
}

@Composable
private fun ZoneContent(zone: TapZone, icon: ImageVector, stripLabels: Boolean, modifier: Modifier) {
    val (color, label) = when (zone) {
        TapZone.Next -> ZoneColors.Next to if (stripLabels) R.string.reader_next_screen else R.string.reader_next_page
        TapZone.Menu -> ZoneColors.Menu to R.string.reader_menu
        TapZone.Previous -> ZoneColors.Previous to if (stripLabels) R.string.reader_previous_screen else R.string.reader_previous_page
    }
    Column(
        modifier = modifier.background(color),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
        Text(text = stringResource(label), style = zoneLabelStyle(), textAlign = TextAlign.Center)
    }
}

/** Modes without zones treat the whole screen alike, so a short legend says what the fingers do. */
@Composable
private fun GestureLegend(tapMode: TapMode) {
    val lines = when (tapMode) {
        TapMode.TapToToggle -> listOf(R.string.reader_legend_tap_menu, R.string.reader_legend_double_zoom)
        TapMode.Controller -> listOf(R.string.reader_legend_badge_menu, R.string.reader_legend_double_zoom)
        TapMode.DoubleTapToToggle -> listOf(R.string.reader_legend_double_menu, R.string.reader_legend_pinch_zoom)
        TapMode.DoubleTapToggleTapNext -> listOf(R.string.reader_legend_tap_next, R.string.reader_legend_double_menu)
        TapMode.ThreeZones -> emptyList()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZoneColors.Menu),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Rounded.TouchApp,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(36.dp),
        )
        lines.forEach { line ->
            Text(text = stringResource(line), style = zoneLabelStyle(), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun zoneLabelStyle(): TextStyle = MaterialTheme.typography.labelLarge.copy(
    color = Color.White,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
    shadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 6f),
)
