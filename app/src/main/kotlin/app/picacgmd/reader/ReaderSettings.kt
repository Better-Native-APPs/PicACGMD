package app.picacgmd.reader

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.FullScreenDisplay
import app.picacgmd.core.data.settings.ReaderScrollMode
import app.picacgmd.core.data.settings.ReaderSliderPosition
import app.picacgmd.core.data.settings.ReaderTapZones
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.data.settings.TwoPagePlacement
import app.picacgmd.feature.reader.ImageQuality
import app.picacgmd.feature.reader.ReaderBackground
import app.picacgmd.feature.reader.ReaderConfig
import app.picacgmd.feature.reader.ReaderDirection
import app.picacgmd.feature.reader.ReaderFilter
import app.picacgmd.feature.reader.ReaderMode
import app.picacgmd.feature.reader.SliderPosition
import app.picacgmd.feature.reader.StripPaging
import app.picacgmd.feature.reader.TapMode
import app.picacgmd.feature.reader.TwoPageArrangement
import app.picacgmd.core.data.settings.ImageQuality as StoredImageQuality
import app.picacgmd.core.data.settings.ReaderBackground as StoredReaderBackground
import app.picacgmd.core.data.settings.ReaderDirection as StoredReaderDirection
import app.picacgmd.core.data.settings.ReaderFilter as StoredReaderFilter
import app.picacgmd.core.data.settings.ReaderMode as StoredReaderMode

/** Keep the auto-turn switch session-local when stored settings change. */
internal fun AppSettings.toReaderConfig(base: ReaderConfig = ReaderConfig()): ReaderConfig = base.copy(
    mode = when (readerMode) {
        StoredReaderMode.WEBTOON, StoredReaderMode.WEBTOON_ZOOM -> ReaderMode.Strip
        StoredReaderMode.GALLERY -> ReaderMode.Paged
        StoredReaderMode.WEBTOON_FREE_ZOOM -> ReaderMode.FreeZoom
        StoredReaderMode.TWO_PAGE_GALLERY -> ReaderMode.DoublePage
    },
    direction = when (readerDirection) {
        StoredReaderDirection.TOP_TO_BOTTOM -> ReaderDirection.TopToBottom
        StoredReaderDirection.LEFT_TO_RIGHT -> ReaderDirection.LeftToRight
        StoredReaderDirection.RIGHT_TO_LEFT -> ReaderDirection.RightToLeft
    },
    tapMode = when (readerTapZones) {
        ReaderTapZones.SINGLE_TAP -> TapMode.TapToToggle
        ReaderTapZones.CONTROLLER -> TapMode.Controller
        ReaderTapZones.DOUBLE_TAP -> TapMode.DoubleTapToToggle
        ReaderTapZones.DOUBLE_TAP_NEXT -> TapMode.DoubleTapToggleTapNext
        ReaderTapZones.THREE_ZONES -> TapMode.ThreeZones
    },
    alwaysRightIsNext = threeZoneNextOnRight,
    stripPaging = when (readerScrollMode) {
        ReaderScrollMode.IMAGE -> StripPaging.ByImage
        ReaderScrollMode.SCREEN_DISTANCE -> StripPaging.ByScreen
    },
    scrollFraction = scrollScreenFraction,
    background = when (readerBackground) {
        StoredReaderBackground.BLACK -> ReaderBackground.Black
        StoredReaderBackground.GREY -> ReaderBackground.Gray
        StoredReaderBackground.PAPER -> ReaderBackground.Paper
        StoredReaderBackground.WHITE -> ReaderBackground.White
    },
    filter = when (readerFilter) {
        StoredReaderFilter.NORMAL -> ReaderFilter.Normal
        StoredReaderFilter.GRAYSCALE -> ReaderFilter.Grayscale
        StoredReaderFilter.SEPIA -> ReaderFilter.Sepia
    },
    quality = when (imageQuality) {
        StoredImageQuality.ORIGINAL -> ImageQuality.Original
        StoredImageQuality.HIGH -> ImageQuality.High
        StoredImageQuality.MEDIUM -> ImageQuality.Medium
        StoredImageQuality.LOW -> ImageQuality.Low
    },
    sliderPosition = when (readerSliderPosition) {
        ReaderSliderPosition.BOTTOM -> SliderPosition.Bottom
        ReaderSliderPosition.LEFT -> SliderPosition.Left
        ReaderSliderPosition.RIGHT -> SliderPosition.Right
    },
    twoPageArrangement = when (twoPagePlacement) {
        TwoPagePlacement.CLOSE_TO -> TwoPageArrangement.CloseTo
        TwoPagePlacement.PULL_APART -> TwoPageArrangement.PullAway
        TwoPagePlacement.EACH_CENTERED -> TwoPageArrangement.EachCentered
    },
    twoPageCoverAlone = twoPageCoverAlone,
    preloadAhead = readerPreloadAhead,
    autoTurnSeconds = readerAutoTurnSeconds,
    autoScrollSpeed = readerAutoScrollSpeed,
    autoScrollPauseOnTouch = readerAutoScrollPauseOnTouch,
    autoNextChapter = readerAutoNextChapter,
    lockDragBounds = lockDragBounds,
    volumeKeyPaging = volumeKeyPaging,
    volumeKeyChapterJump = volumeKeyChapterPaging,
    fullscreen = fullScreenDisplay != FullScreenDisplay.OFF,
    autoFullscreen = readerFullScreen,
    autoFullscreenOnForward = fullScreenOnForward,
    keepScreenOn = keepScreenOn,
    noPageAnimation = !readerPageAnimation,
    reduceMotion = eInkReduceAnimations,
    minZoom = minZoom,
    maxZoom = maxZoom,
    doubleTapZoom = doubleTapZoom,
    gestureSpeed = gestureSpeed,
).normalized()

/** Only write changed, represented preferences; unsupported reader options remain session-local. */
internal suspend fun SettingsRepository.saveReaderConfig(previous: ReaderConfig, next: ReaderConfig) {
    if (previous.mode != next.mode) setReaderMode(
        when (next.mode) {
            ReaderMode.Strip -> StoredReaderMode.WEBTOON
            ReaderMode.Paged -> StoredReaderMode.GALLERY
            ReaderMode.FreeZoom -> StoredReaderMode.WEBTOON_FREE_ZOOM
            ReaderMode.DoublePage -> StoredReaderMode.TWO_PAGE_GALLERY
        },
    )
    if (previous.direction != next.direction) setReaderDirection(
        when (next.direction) {
            ReaderDirection.TopToBottom -> StoredReaderDirection.TOP_TO_BOTTOM
            ReaderDirection.LeftToRight -> StoredReaderDirection.LEFT_TO_RIGHT
            ReaderDirection.RightToLeft -> StoredReaderDirection.RIGHT_TO_LEFT
        },
    )
    if (previous.tapMode != next.tapMode) setReaderTapZones(
        when (next.tapMode) {
            TapMode.TapToToggle -> ReaderTapZones.SINGLE_TAP
            TapMode.Controller -> ReaderTapZones.CONTROLLER
            TapMode.DoubleTapToToggle -> ReaderTapZones.DOUBLE_TAP
            TapMode.DoubleTapToggleTapNext -> ReaderTapZones.DOUBLE_TAP_NEXT
            TapMode.ThreeZones -> ReaderTapZones.THREE_ZONES
        },
    )
    if (previous.alwaysRightIsNext != next.alwaysRightIsNext) setThreeZoneNextOnRight(next.alwaysRightIsNext)
    if (previous.stripPaging != next.stripPaging) setReaderScrollMode(
        when (next.stripPaging) {
            StripPaging.ByImage -> ReaderScrollMode.IMAGE
            StripPaging.ByScreen -> ReaderScrollMode.SCREEN_DISTANCE
        },
    )
    if (previous.scrollFraction != next.scrollFraction) setScrollScreenFraction(next.scrollFraction)
    if (previous.background != next.background) setReaderBackground(
        when (next.background) {
            ReaderBackground.Black -> StoredReaderBackground.BLACK
            ReaderBackground.Gray -> StoredReaderBackground.GREY
            ReaderBackground.Paper -> StoredReaderBackground.PAPER
            ReaderBackground.White -> StoredReaderBackground.WHITE
        },
    )
    if (previous.filter != next.filter) setReaderFilter(
        when (next.filter) {
            ReaderFilter.Normal -> StoredReaderFilter.NORMAL
            ReaderFilter.Grayscale -> StoredReaderFilter.GRAYSCALE
            ReaderFilter.Sepia -> StoredReaderFilter.SEPIA
        },
    )
    if (previous.quality != next.quality) setImageQuality(
        when (next.quality) {
            ImageQuality.Original -> StoredImageQuality.ORIGINAL
            ImageQuality.High -> StoredImageQuality.HIGH
            ImageQuality.Medium -> StoredImageQuality.MEDIUM
            ImageQuality.Low -> StoredImageQuality.LOW
        },
    )
    if (previous.sliderPosition != next.sliderPosition) setReaderSliderPosition(
        when (next.sliderPosition) {
            SliderPosition.Bottom -> ReaderSliderPosition.BOTTOM
            SliderPosition.Left -> ReaderSliderPosition.LEFT
            SliderPosition.Right -> ReaderSliderPosition.RIGHT
        },
    )
    if (previous.twoPageArrangement != next.twoPageArrangement) setTwoPagePlacement(
        when (next.twoPageArrangement) {
            TwoPageArrangement.CloseTo -> TwoPagePlacement.CLOSE_TO
            TwoPageArrangement.PullAway -> TwoPagePlacement.PULL_APART
            TwoPageArrangement.EachCentered -> TwoPagePlacement.EACH_CENTERED
        },
    )
    if (previous.twoPageCoverAlone != next.twoPageCoverAlone) setTwoPageCoverAlone(next.twoPageCoverAlone)
    if (previous.preloadAhead != next.preloadAhead) setReaderPreloadAhead(next.preloadAhead)
    if (previous.autoTurnSeconds != next.autoTurnSeconds) setReaderAutoTurnSeconds(next.autoTurnSeconds)
    if (previous.autoScrollSpeed != next.autoScrollSpeed) setReaderAutoScrollSpeed(next.autoScrollSpeed)
    if (previous.autoScrollPauseOnTouch != next.autoScrollPauseOnTouch) {
        setReaderAutoScrollPauseOnTouch(next.autoScrollPauseOnTouch)
    }
    if (previous.autoNextChapter != next.autoNextChapter) setReaderAutoNextChapter(next.autoNextChapter)
    if (previous.lockDragBounds != next.lockDragBounds) setLockDragBounds(next.lockDragBounds)
    if (previous.volumeKeyPaging != next.volumeKeyPaging) setVolumeKeyPaging(next.volumeKeyPaging)
    if (previous.volumeKeyChapterJump != next.volumeKeyChapterJump) setVolumeKeyChapterPaging(next.volumeKeyChapterJump)
    if (previous.fullscreen != next.fullscreen) {
        setFullScreenDisplay(if (next.fullscreen) FullScreenDisplay.HIDE_SYSTEM_BARS else FullScreenDisplay.OFF)
    }
    if (previous.autoFullscreen != next.autoFullscreen) setReaderFullScreen(next.autoFullscreen)
    if (previous.autoFullscreenOnForward != next.autoFullscreenOnForward) setFullScreenOnForward(next.autoFullscreenOnForward)
    if (previous.keepScreenOn != next.keepScreenOn) setKeepScreenOn(next.keepScreenOn)
    if (previous.noPageAnimation != next.noPageAnimation) setReaderPageAnimation(!next.noPageAnimation)
    if (previous.reduceMotion != next.reduceMotion) setEInkReduceAnimations(next.reduceMotion)
    if (previous.minZoom != next.minZoom || previous.maxZoom != next.maxZoom || previous.doubleTapZoom != next.doubleTapZoom) {
        setReaderZoom(next.minZoom, next.maxZoom, next.doubleTapZoom)
    }
    if (previous.gestureSpeed != next.gestureSpeed) setGestureSpeed(next.gestureSpeed)
}
