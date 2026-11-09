package app.picacgmd.feature.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.picacgmd.core.designsystem.window.secureDialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.R
import app.picacgmd.feature.reader.logic.CropHandle
import app.picacgmd.feature.reader.logic.CropRect
import app.picacgmd.feature.reader.logic.DefaultCropRect
import app.picacgmd.feature.reader.logic.cropHandleAt
import app.picacgmd.feature.reader.logic.decodeEdge
import app.picacgmd.feature.reader.logic.dragged
import app.picacgmd.feature.reader.logic.fitSize
import app.picacgmd.feature.reader.logic.rememberReaderMemoryBudget
import app.picacgmd.feature.reader.media.PageLabel
import app.picacgmd.feature.reader.media.rememberGallerySavePermission
import app.picacgmd.feature.reader.media.saveCropToGallery
import app.picacgmd.feature.reader.media.shareCrop
import kotlinx.coroutines.launch
import coil3.size.Size as CoilSize

private const val PREVIEW_MAX_ZOOM = 5f
private const val PREVIEW_DOUBLE_TAP_ZOOM = 2.5f

private val CropScrim = Color(0x8C000000)

/**
 * 全屏预览: the page alone on black, free to zoom. 裁剪 puts a frame on it, and the framed part can be
 * saved or shared; it is cut from the original file, so it is sharper than the screen.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PagePreviewDialog(
    image: PageImage,
    label: PageLabel,
    colorFilter: ColorFilter?,
    reduceMotion: Boolean,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = secureDialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Always on black, whatever the reader's background: the bars' icons stay light.
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, dialogView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
        MaterialTheme(colorScheme = ReaderDarkScheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
                PreviewContent(image, label, colorFilter, reduceMotion, onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewContent(
    image: PageImage,
    label: PageLabel,
    colorFilter: ColorFilter?,
    reduceMotion: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val memoryBudget = rememberReaderMemoryBudget()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val gallerySavePermission = rememberGallerySavePermission(snackbar)
    val zoom = remember {
        ZoomState(scope).apply { maxScale = PREVIEW_MAX_ZOOM }
    }
    var cropping by remember { mutableStateOf(false) }
    var crop by remember { mutableStateOf(DefaultCropRect) }
    var busy by remember { mutableStateOf(false) }
    val latestCropping by rememberUpdatedState(cropping)
    val savedText = stringResource(R.string.reader_saved)
    val saveFailedText = stringResource(R.string.reader_save_failed)
    val shareFailedText = stringResource(R.string.reader_share_failed)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val width = widthPx.toFloat()
        val height = heightPx.toFloat()
        val platformContext = LocalPlatformContext.current
        val request = remember(image, widthPx, heightPx, memoryBudget) {
            val factor = memoryBudget.previewDecodeFactor
            val size = CoilSize(decodeEdge(widthPx * factor), decodeEdge(heightPx * factor))
            pageRequest(platformContext, image, size)
        }
        val painter = rememberAsyncImagePainter(model = request)
        val state by painter.state.collectAsState()
        val loaded = state is AsyncImagePainter.State.Success

        when (val current = state) {
            is AsyncImagePainter.State.Success -> {
                val intrinsic = current.painter.intrinsicSize
                val (fitWidth, fitHeight) = if (intrinsic.isSpecified) {
                    fitSize(intrinsic.width, intrinsic.height, width, height)
                } else {
                    width to height
                }
                SideEffect { zoom.setGeometry(width, height, fitWidth, fitHeight) }
                Image(
                    painter = current.painter,
                    contentDescription = stringResource(R.string.reader_page_number, label.pageNumber),
                    contentScale = ContentScale.Fit,
                    colorFilter = colorFilter,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(zoom) {
                            detectPageZoomGestures(zoom, horizontalPaging = true) { !latestCropping }
                        }
                        .pointerInput(zoom) {
                            detectTapGestures(
                                onDoubleTap = { position ->
                                    if (!latestCropping) {
                                        zoom.toggle(
                                            focusX = position.x - size.width / 2f,
                                            focusY = position.y - size.height / 2f,
                                            doubleTapScale = PREVIEW_DOUBLE_TAP_ZOOM,
                                            animated = !reduceMotion,
                                        )
                                    }
                                },
                            )
                        }
                        .graphicsLayer { zoom.applyTo(this) },
                )
                if (cropping) {
                    CropOverlay(
                        imageLeft = (width - fitWidth) / 2f,
                        imageTop = (height - fitHeight) / 2f,
                        imageWidth = fitWidth,
                        imageHeight = fitHeight,
                        crop = crop,
                        onCropChange = { crop = it },
                    )
                }
            }
            is AsyncImagePainter.State.Error -> PageFailureBlock(
                pageNumber = label.pageNumber,
                failure = pageFailureOf(current.result.throwable, image),
                onRetry = painter::restart,
                onSwitchServer = null,
                modifier = Modifier.fillMaxSize(),
            )
            else -> {
                if (reduceMotion) {
                    LoadingIndicator(progress = { 0f }, modifier = Modifier.align(Alignment.Center).size(64.dp))
                } else {
                    LoadingIndicator(modifier = Modifier.align(Alignment.Center).size(64.dp))
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.reader_close))
            }
            HintPill(text = stringResource(R.string.reader_page_number, label.pageNumber))
        }

        val barModifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(16.dp)
        if (cropping) {
            Row(modifier = barModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { cropping = false }) {
                    Text(stringResource(R.string.reader_cancel))
                }
                FilledTonalButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val shared = shareCrop(context, image, label, crop)
                            busy = false
                            if (!shared) snackbar.showSnackbar(shareFailedText)
                        }
                    },
                ) {
                    ButtonLabel(Icons.Rounded.Share, stringResource(R.string.reader_action_share))
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            if (!gallerySavePermission()) {
                                busy = false
                                return@launch
                            }
                            val saved = saveCropToGallery(context, image, label, crop)
                            busy = false
                            snackbar.showSnackbar(if (saved) savedText else saveFailedText)
                        }
                    },
                ) {
                    ButtonLabel(Icons.Rounded.SaveAlt, stringResource(R.string.reader_save))
                }
            }
        } else if (loaded) {
            FilledTonalButton(
                modifier = barModifier,
                onClick = {
                    zoom.reset()
                    crop = DefaultCropRect
                    cropping = true
                },
            ) {
                ButtonLabel(Icons.Rounded.Crop, stringResource(R.string.reader_crop))
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 72.dp),
        )
    }
}

@Composable
private fun ButtonLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text)
}

/**
 * The crop frame over the image, in screen pixels. Dragging the corners or edges resizes it, dragging
 * inside moves it; touches elsewhere do nothing.
 */
@Composable
private fun CropOverlay(
    imageLeft: Float,
    imageTop: Float,
    imageWidth: Float,
    imageHeight: Float,
    crop: CropRect,
    onCropChange: (CropRect) -> Unit,
) {
    val latestCrop by rememberUpdatedState(crop)
    val latestOnCropChange by rememberUpdatedState(onCropChange)
    val touchRadius = with(LocalDensity.current) { 28.dp.toPx() }
    val cornerColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(imageLeft, imageTop, imageWidth, imageHeight, touchRadius) {
                var handle: CropHandle? = null
                // The frame as this drag has left it; several moves can arrive within one frame.
                var working = latestCrop
                detectDragGestures(
                    onDragStart = { start ->
                        working = latestCrop
                        handle = cropHandleAt(
                            x = start.x - imageLeft,
                            y = start.y - imageTop,
                            rect = working,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            touchRadius = touchRadius,
                        )
                    },
                    onDragEnd = { handle = null },
                    onDragCancel = { handle = null },
                ) { change, amount ->
                    val grabbed = handle ?: return@detectDragGestures
                    change.consume()
                    working = working.dragged(grabbed, amount.x / imageWidth, amount.y / imageHeight)
                    latestOnCropChange(working)
                }
            },
    ) {
        val left = imageLeft + crop.left * imageWidth
        val right = imageLeft + crop.right * imageWidth
        val top = imageTop + crop.top * imageHeight
        val bottom = imageTop + crop.bottom * imageHeight
        drawRect(CropScrim, Offset.Zero, Size(size.width, top))
        drawRect(CropScrim, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(CropScrim, Offset(0f, top), Size(left, bottom - top))
        drawRect(CropScrim, Offset(right, top), Size(size.width - right, bottom - top))
        drawRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCropCorners(left, top, right, bottom, cornerColor)
    }
}

private fun DrawScope.drawCropCorners(left: Float, top: Float, right: Float, bottom: Float, color: Color) {
    val width = 3.dp.toPx()
    val length = minOf(18.dp.toPx(), (right - left) / 2f, (bottom - top) / 2f)
    fun corner(x: Float, y: Float, towardsX: Float, towardsY: Float) {
        drawLine(color, Offset(x, y), Offset(x + towardsX * length, y), width)
        drawLine(color, Offset(x, y), Offset(x, y + towardsY * length), width)
    }
    corner(left, top, 1f, 1f)
    corner(right, top, -1f, 1f)
    corner(left, bottom, 1f, -1f)
    corner(right, bottom, -1f, -1f)
}
