package app.picacgmd.feature.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.picacgmd.feature.reader.ReaderBackground
import app.picacgmd.feature.reader.ReaderChapter
import app.picacgmd.feature.reader.ReaderConfig
import app.picacgmd.feature.reader.ReaderMode
import app.picacgmd.feature.reader.SliderPosition
import app.picacgmd.feature.reader.TapMode
import app.picacgmd.feature.reader.logic.ForwardSwipe
import app.picacgmd.feature.reader.logic.PagingAxis

@Preview(name = "加载中", widthDp = 360, heightDp = 520)
@Composable
private fun PageLoadingPreview() {
    ReaderTheme(ReaderBackground.Black) {
        PageLoadingBlock(pageNumber = 15, reduceMotion = true, modifier = Modifier.fillMaxWidth().height(520.dp))
    }
}

@Preview(name = "加载中 · 有进度", widthDp = 360, heightDp = 520)
@Composable
private fun PageLoadingProgressPreview() {
    ReaderTheme(ReaderBackground.Black) {
        PageLoadingBlock(
            pageNumber = 15,
            reduceMotion = true,
            progress = { 0.62f },
            modifier = Modifier.fillMaxWidth().height(520.dp),
        )
    }
}

@Preview(name = "加载失败", widthDp = 360, heightDp = 520)
@Composable
private fun PageFailurePreview() {
    ReaderTheme(ReaderBackground.Black) {
        PageFailureBlock(
            pageNumber = 15,
            failure = PageFailure.Timeout,
            onRetry = {},
            onSwitchServer = {},
            modifier = Modifier.fillMaxWidth().height(520.dp),
        )
    }
}

@Preview(name = "页码", widthDp = 200, heightDp = 80)
@Composable
private fun PageBadgePreview() {
    ReaderTheme(ReaderBackground.Paper) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(ReaderBackground.Paper.palette().background),
        ) {
            PageBadge(page = 14, pageCount = 22, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
    }
}

@Preview(name = "双页页码", widthDp = 200, heightDp = 80)
@Composable
private fun SpreadBadgePreview() {
    ReaderTheme(ReaderBackground.Black) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(ReaderBackground.Black.palette().background),
        ) {
            PageBadge(page = 14, lastPage = 15, pageCount = 22, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
    }
}

private val PreviewActions = ReaderControlActions(
    onBack = {},
    onOpenChapters = {},
    onOpenSettings = {},
    onFullscreen = {},
    onPreviousChapter = {},
    onNextChapter = {},
    onSeek = {},
    onReloadChapter = {},
    onPreviewZones = {},
    onOpenComments = {},
    onOpenAllSettings = {},
)

@Composable
private fun ControlsPreview(background: ReaderBackground, position: SliderPosition) {
    ReaderTheme(background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background.palette().background),
        ) {
            ReaderControls(
                visible = true,
                chapterTitle = "第 12 话 星屑海",
                comicTitle = "夜行列车",
                page = { 13 },
                pageCount = 22,
                sliderPosition = position,
                rightToLeft = false,
                hasPreviousChapter = true,
                hasNextChapter = true,
                reduceMotion = true,
                actions = PreviewActions,
            )
        }
    }
}

@Preview(name = "控制层", widthDp = 412, heightDp = 915)
@Composable
private fun ControlsDarkPreview() {
    ControlsPreview(ReaderBackground.Black, SliderPosition.Bottom)
}

@Preview(name = "滑块在右侧", widthDp = 412, heightDp = 915)
@Composable
private fun ControlsSidePreview() {
    ControlsPreview(ReaderBackground.Gray, SliderPosition.Right)
}

@Preview(name = "纸色控制层", widthDp = 412, heightDp = 915)
@Composable
private fun ControlsPaperPreview() {
    ControlsPreview(ReaderBackground.Paper, SliderPosition.Bottom)
}

@Preview(name = "章节结束", widthDp = 412, heightDp = 560)
@Composable
private fun ChapterEndPreview() {
    ReaderTheme(ReaderBackground.Black) {
        val pull = remember { ChapterPullState(threshold = 1f, flickVelocity = 1f, onLeave = {}, onArmed = {}) }
        Box(Modifier.background(ReaderBackground.Black.palette().background)) {
            ChapterEndBlock(
                info = ChapterEndInfo(
                    finishedTitle = "第 12 话 星屑海",
                    next = ReaderChapter(id = "13", title = "第 13 话 夜班乘务员", isDownloaded = true),
                    nextPageCount = 24,
                    cover = null,
                    commentCount = 326,
                ),
                pull = pull,
                swipe = ForwardSwipe.Up,
                onNext = {},
                onComments = {},
                onFinish = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(name = "三区域", widthDp = 412, heightDp = 915)
@Composable
private fun TapZonesPreview() {
    ReaderTheme(ReaderBackground.Black) {
        TapZoneOverlay(
            visible = true,
            tapMode = TapMode.ThreeZones,
            axis = PagingAxis.Horizontal,
            rightToLeft = true,
            alwaysRightIsNext = false,
            stripLabels = false,
            onDismiss = {},
        )
    }
}

@Preview(name = "阅读设置", widthDp = 412, heightDp = 915)
@Composable
private fun SettingsSheetPreview() {
    ReaderTheme(ReaderBackground.Black) {
        ReaderSettingsSheet(
            config = ReaderConfig(volumeKeyPaging = true, autoTurn = true),
            onConfigChange = {},
            onDismiss = {},
            onOpenAllSettings = {},
            onPreviewZones = {},
        )
    }
}

@Preview(name = "阅读设置 · 双页", widthDp = 412, heightDp = 915)
@Composable
private fun SettingsSheetDoublePagePreview() {
    ReaderTheme(ReaderBackground.Black) {
        ReaderSettingsSheet(
            config = ReaderConfig(mode = ReaderMode.DoublePage, twoPageCoverAlone = true),
            onConfigChange = {},
            onDismiss = {},
            onOpenAllSettings = {},
            onPreviewZones = {},
        )
    }
}
