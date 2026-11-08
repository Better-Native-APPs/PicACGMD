package app.picacgmd.feature.reader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.R
import app.picacgmd.feature.reader.logic.PageFraction
import app.picacgmd.feature.reader.logic.minimapSize
import coil3.size.Size as CoilSize

private val MinimapMaxWidth = 96.dp
private val MinimapMaxHeight = 160.dp
private val MinimapMinWidth = 24.dp
private val MinimapShape = RoundedCornerShape(8.dp)
private val MinimapBorder = 2.dp
private val ViewportStroke = 2.dp
private val ViewportCorner = 3.dp

/**
 * 自由缩放的小地图: the current page in miniature, with the part that is on screen outlined.
 * Touching or dragging on it moves the view to that spot.
 *
 * [visibleFraction] is read while drawing, so following a pinch or a drag only redraws the outline.
 * The page is loaded with the strip's own request ([requestSize]), so it comes from the memory cache.
 */
@Composable
internal fun PageMinimap(
    image: PageImage,
    aspect: Float,
    requestSize: CoilSize,
    colorFilter: ColorFilter?,
    visibleFraction: () -> PageFraction?,
    onDragStart: () -> Unit,
    onDrag: (u: Float, v: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (width, height) = minimapSize(aspect, MinimapMaxWidth.value, MinimapMaxHeight.value, MinimapMinWidth.value)
    val context = LocalPlatformContext.current
    val request = remember(image, requestSize) { pageRequest(context, image, requestSize) }
    val painter = rememberAsyncImagePainter(model = request)
    val outline = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.reader_minimap)
    val latestDragStart by rememberUpdatedState(onDragStart)
    val latestDrag by rememberUpdatedState(onDrag)
    val latestDragEnd by rememberUpdatedState(onDragEnd)

    Box(
        modifier = modifier
            .size(width.dp, height.dp)
            .shadow(4.dp, MinimapShape)
            .clip(MinimapShape)
            .background(PageBlockColors.Background)
            .border(MinimapBorder, Color.White, MinimapShape)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    latestDragStart()
                    try {
                        latestDrag(down.position.x / size.width, down.position.y / size.height)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            change.consume()
                            latestDrag(change.position.x / size.width, change.position.y / size.height)
                        }
                    } finally {
                        latestDragEnd()
                    }
                }
            }
            .drawWithContent {
                drawContent()
                val fraction = visibleFraction() ?: return@drawWithContent
                val stroke = ViewportStroke.toPx()
                val half = stroke / 2f
                val left = (fraction.left * size.width).coerceIn(half, size.width - half)
                val top = (fraction.top * size.height).coerceIn(half, size.height - half)
                val right = (fraction.right * size.width).coerceIn(half, size.width - half)
                val bottom = (fraction.bottom * size.height).coerceIn(half, size.height - half)
                val corner = CornerRadius(ViewportCorner.toPx())
                val topLeft = Offset(left, top)
                val box = Size((right - left).coerceAtLeast(stroke), (bottom - top).coerceAtLeast(stroke))
                drawRoundRect(color = outline.copy(alpha = 0.2f), topLeft = topLeft, size = box, cornerRadius = corner)
                drawRoundRect(color = outline, topLeft = topLeft, size = box, cornerRadius = corner, style = Stroke(stroke))
            },
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = colorFilter,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
