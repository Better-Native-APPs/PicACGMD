package app.picacgmd.feature.reader.ui

import app.picacgmd.core.designsystem.window.securePopupProperties

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.picacgmd.feature.reader.R
import app.picacgmd.core.designsystem.component.BackButton
import app.picacgmd.feature.reader.SliderPosition
import app.picacgmd.feature.reader.logic.pageToSlider
import app.picacgmd.feature.reader.logic.sliderToPage

private val ChromeMaxWidth = 640.dp
private val CapsuleShadow = 3.dp
private val SideSliderMaxHeight = 495.dp
private val ChromeIconButtonSize = 48.dp

/** Height of the top capsule, for what must stay clear of it. */
internal val TopCapsuleHeight = 56.dp
internal val TopCapsuleGap = 8.dp

/** What the buttons of the control layer do. Null means that there is nowhere to go. */
@Stable
internal class ReaderControlActions(
    val onBack: () -> Unit,
    val onOpenChapters: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onFullscreen: () -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onSeek: (Int) -> Unit,
    val onReloadChapter: () -> Unit,
    val onPreviewZones: () -> Unit,
    val onOpenComments: (() -> Unit)?,
    val onOpenAllSettings: (() -> Unit)?,
)

/** The bars can come and go underneath, so the chrome is laid out as if they were always there. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun readerChromeInsets(): WindowInsets =
    WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)

/**
 * 控制层: three floating controls instead of bars across the screen. A capsule on top, the progress
 * slider (below, or on either side) and a floating toolbar at the bottom.
 *
 * @param page the current page, read lazily so that page changes only touch the slider.
 * @param pageCount pages in the open chapter; 0 hides the slider while there is nothing to seek in.
 * @param rightToLeft the bottom slider runs the way the pages do.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BoxScope.ReaderControls(
    visible: Boolean,
    chapterTitle: String,
    comicTitle: String,
    page: () -> Int,
    pageCount: Int,
    sliderPosition: SliderPosition,
    rightToLeft: Boolean,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    reduceMotion: Boolean,
    actions: ReaderControlActions,
) {
    val insets = readerChromeInsets()
    val spec = MaterialTheme.motionScheme
    val fade = if (reduceMotion) EnterTransition.None else fadeIn(spec.slowEffectsSpec())
    val fadeAway = if (reduceMotion) ExitTransition.None else fadeOut(spec.slowEffectsSpec())

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = if (reduceMotion) fade else fade + slideInVertically(spec.slowEffectsSpec()) { -it / 2 },
        exit = if (reduceMotion) fadeAway else fadeAway + slideOutVertically(spec.slowEffectsSpec()) { -it / 2 },
    ) {
        TopCapsule(
            chapterTitle = chapterTitle,
            comicTitle = comicTitle,
            actions = actions,
            canReload = pageCount > 0,
            modifier = Modifier
                .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 12.dp, end = 12.dp, top = TopCapsuleGap)
                .widthIn(max = ChromeMaxWidth)
                .fillMaxWidth(),
        )
    }

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = if (reduceMotion) fade else fade + slideInVertically(spec.slowEffectsSpec()) { it / 2 },
        exit = if (reduceMotion) fadeAway else fadeAway + slideOutVertically(spec.slowEffectsSpec()) { it / 2 },
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                .widthIn(max = ChromeMaxWidth)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (pageCount > 1 && sliderPosition == SliderPosition.Bottom) {
                BottomSlider(page, pageCount, rightToLeft, actions.onSeek)
            }
            ChromeToolbar(hasPreviousChapter, hasNextChapter, actions)
        }
    }

    if (pageCount > 1 && sliderPosition != SliderPosition.Bottom) {
        val onLeft = sliderPosition == SliderPosition.Left
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
            enter = if (reduceMotion) fade else fade + slideInHorizontally(spec.slowEffectsSpec()) { if (onLeft) -it else it },
            exit = if (reduceMotion) fadeAway else fadeAway + slideOutHorizontally(spec.slowEffectsSpec()) { if (onLeft) -it else it },
        ) {
            Box(
                modifier = Modifier
                    .windowInsetsPadding(insets)
                    // Clear of the top capsule and of the toolbar, whatever the screen's height.
                    .padding(start = 12.dp, end = 12.dp, top = TopCapsuleGap + TopCapsuleHeight + 16.dp, bottom = 92.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                SideSlider(page, pageCount, actions.onSeek)
            }
        }
    }
}

@Composable
private fun TopCapsule(
    chapterTitle: String,
    comicTitle: String,
    actions: ReaderControlActions,
    canReload: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(TopCapsuleHeight),
        shape = RoundedCornerShape(TopCapsuleHeight / 2),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = CapsuleShadow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(actions.onBack, Modifier.size(ChromeIconButtonSize))
            Column(Modifier.weight(1f)) {
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = comicTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = actions.onOpenChapters, modifier = Modifier.size(ChromeIconButtonSize)) {
                Icon(
                    imageVector = Icons.Rounded.FormatListNumbered,
                    contentDescription = stringResource(R.string.reader_chapters),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoreMenu(actions, canReload)
        }
    }
}

@Composable
private fun MoreMenu(actions: ReaderControlActions, canReload: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(ChromeIconButtonSize)) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.reader_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, properties = securePopupProperties(focusable = true)) {
            if (canReload) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_reload_all)) },
                    leadingIcon = { Icon(Icons.Rounded.RestartAlt, contentDescription = null) },
                    onClick = {
                        expanded = false
                        actions.onReloadChapter()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reader_preview_zones)) },
                leadingIcon = { Icon(Icons.Rounded.TouchApp, contentDescription = null) },
                onClick = {
                    expanded = false
                    actions.onPreviewZones()
                },
            )
            actions.onOpenComments?.let { open ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_comments)) },
                    leadingIcon = { Icon(Icons.Rounded.Forum, contentDescription = null) },
                    onClick = {
                        expanded = false
                        open()
                    },
                )
            }
            actions.onOpenAllSettings?.let { open ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_all_settings)) },
                    leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    onClick = {
                        expanded = false
                        open()
                    },
                )
            }
        }
    }
}

/** 设置 is the widest and the only filled button, because it is the one used most. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChromeToolbar(hasPrevious: Boolean, hasNext: Boolean, actions: ReaderControlActions) {
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            toolbarContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        IconButton(
            onClick = actions.onPreviousChapter,
            enabled = hasPrevious,
            modifier = Modifier.size(ChromeIconButtonSize),
        ) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.reader_previous_chapter))
        }
        IconButton(onClick = actions.onOpenChapters, modifier = Modifier.size(ChromeIconButtonSize)) {
            Icon(Icons.Rounded.FormatListNumbered, contentDescription = stringResource(R.string.reader_chapters))
        }
        FilledIconButton(
            onClick = actions.onOpenSettings,
            modifier = Modifier.size(width = 64.dp, height = ChromeIconButtonSize),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.reader_settings))
        }
        IconButton(onClick = actions.onFullscreen, modifier = Modifier.size(ChromeIconButtonSize)) {
            Icon(Icons.Rounded.Fullscreen, contentDescription = stringResource(R.string.reader_fullscreen))
        }
        IconButton(
            onClick = actions.onNextChapter,
            enabled = hasNext,
            modifier = Modifier.size(ChromeIconButtonSize),
        ) {
            Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.reader_next_chapter))
        }
    }
}

/**
 * A slider that follows the current page and only asks for the page once the finger lets go: moving
 * across a long chapter does not start loading every page on the way. The number beside it follows
 * the finger straight away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberPageSlider(page: () -> Int, pageCount: Int): SliderState {
    val slider = remember(pageCount) {
        SliderState(value = pageToSlider(Snapshot.withoutReadObservation(page), pageCount))
    }
    LaunchedEffect(slider) {
        snapshotFlow(page).collect { current ->
            if (!slider.isDragging) slider.value = pageToSlider(current, pageCount)
        }
    }
    return slider
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageNumber(slider: SliderState, pageCount: Int, modifier: Modifier = Modifier) {
    Text(
        text = (sliderToPage(slider.value, pageCount) + 1).toString(),
        style = MaterialTheme.typography.labelMedium.withTabularFigures(),
        modifier = modifier,
    )
}

@Composable
private fun PageTotal(pageCount: Int) {
    Text(
        text = pageCount.toString(),
        style = MaterialTheme.typography.labelMedium.withTabularFigures(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomSlider(page: () -> Int, pageCount: Int, rightToLeft: Boolean, onSeek: (Int) -> Unit) {
    val slider = rememberPageSlider(page, pageCount)
    val label = stringResource(R.string.reader_slider)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = CapsuleShadow,
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageNumber(slider, pageCount, Modifier.widthIn(min = 18.dp))
                Slider(
                    state = slider,
                    onValueChange = { slider.value = it },
                    onValueChangeFinished = { onSeek(sliderToPage(slider.value, pageCount)) },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = label },
                )
                PageTotal(pageCount)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SideSlider(page: () -> Int, pageCount: Int, onSeek: (Int) -> Unit) {
    val slider = rememberPageSlider(page, pageCount)
    val label = stringResource(R.string.reader_slider)
    Surface(
        modifier = Modifier
            .width(44.dp)
            .heightIn(max = SideSliderMaxHeight)
            .fillMaxHeight(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = CapsuleShadow,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            PageNumber(slider, pageCount)
            VerticalSlider(
                state = slider,
                onValueChange = { slider.value = it },
                onValueChangeFinished = { onSeek(sliderToPage(slider.value, pageCount)) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = label },
            )
            PageTotal(pageCount)
        }
    }
}
