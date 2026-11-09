package app.picacgmd.feature.reader

import androidx.compose.runtime.Immutable

/**
 * Reader preferences. The app maps its stored settings onto this class and persists whatever comes
 * back through `onConfigChange`. Every value is clamped by [normalized] before use, so the app may
 * pass raw stored values.
 */
@Immutable
data class ReaderConfig(
    /** 模式: 条漫 / 翻页 / 双页 / 自由缩放. */
    val mode: ReaderMode = ReaderMode.Strip,
    /** 方向. The strip always scrolls top to bottom; paged modes use all three directions. */
    val direction: ReaderDirection = ReaderDirection.TopToBottom,
    /** 操控方式: what single and double taps do. */
    val tapMode: TapMode = TapMode.TapToToggle,
    /** 三区域「始终右侧下一页」: keep the right zone as 下一页 even when reading right to left. */
    val alwaysRightIsNext: Boolean = false,
    /** How 下一页 / 上一页 move the strip: by one image, or by [scrollFraction] of the screen. */
    val stripPaging: StripPaging = StripPaging.ByScreen,
    /** 按距离翻页长度: share of the screen height one strip step scrolls, 0.1–1.0. */
    val scrollFraction: Float = 0.8f,
    /** 背景: 黑 / 灰 / 纸 / 白. */
    val background: ReaderBackground = ReaderBackground.Black,
    /** 滤镜: 正常 / 灰度 / 棕褐. */
    val filter: ReaderFilter = ReaderFilter.Normal,
    /** 画质 requested from the image server. Applied by the app's image loading, shown in settings. */
    val quality: ImageQuality = ImageQuality.Original,
    /** 滑块位置: 下方 / 左 / 右. */
    val sliderPosition: SliderPosition = SliderPosition.Bottom,
    /** 双页排列: 靠近 / 远离 / 各自居中. */
    val twoPageArrangement: TwoPageArrangement = TwoPageArrangement.CloseTo,
    /** 双页模式下第一页单独显示（封面单页）. */
    val twoPageCoverAlone: Boolean = false,
    /** 自由缩放「锁定拖动边界」: keep the zoomed page inside the screen while dragging. */
    val lockDragBounds: Boolean = false,
    /** 音量键翻页. */
    val volumeKeyPaging: Boolean = false,
    /** 双击音量键跳下一章 (needs [volumeKeyPaging]). */
    val volumeKeyChapterJump: Boolean = false,
    /** App-wide system bar policy, distinct from whether reader controls are visible. */
    val systemBars: SystemBarsMode = SystemBarsMode.HiddenAll,
    /** 进入时自动全屏: open with the controls hidden. */
    val autoFullscreen: Boolean = false,
    /** 往后翻页时自动全屏: hide the controls when moving forward. */
    val autoFullscreenOnForward: Boolean = false,
    /** Keep the screen awake while the reader is open. */
    val keepScreenOn: Boolean = true,
    /** 自动翻页; in the continuous strips (条漫 / 自由缩放) it is 自动滚动 instead. */
    val autoTurn: Boolean = false,
    /** 自动翻页间隔, 2–30 seconds. */
    val autoTurnSeconds: Int = 6,
    /** 自动滚动速度 step, 1 (很慢) – 5 (很快); see [app.picacgmd.feature.reader.logic.AutoScrollSpeed]. */
    val autoScrollSpeed: Int = 3,
    /** 手指触摸屏幕时暂停自动滚动. */
    val autoScrollPauseOnTouch: Boolean = true,
    /** 自动翻章: the strip opens the next chapter by itself when it reaches the end of one. */
    val autoNextChapter: Boolean = false,
    /** 取消翻页动画: jump instead of animating when turning pages by tap or key. */
    val noPageAnimation: Boolean = false,
    /** 减少动画 (e-ink): also drops loading animations. */
    val reduceMotion: Boolean = false,
    /** 最小缩放, 0.5–2.0. Continuous strips never go below 1.0. */
    val minZoom: Float = 1f,
    /** 最大缩放, 2.0–5.0. */
    val maxZoom: Float = 3f,
    /** 双击缩放, 1.5–3.0. */
    val doubleTapZoom: Float = 2f,
    /** 手势速度倍率, 0.5–3.0: multiplies drag distance while zoomed. */
    val gestureSpeed: Float = 1f,
    /** Pages preloaded ahead of the current one, 1–5. One page behind is always kept warm. */
    val preloadAhead: Int = 2,
) {
    /** Copy with every numeric value clamped to its allowed range. */
    fun normalized(): ReaderConfig {
        val min = minZoom.coerceIn(MIN_ZOOM_RANGE)
        val max = maxZoom.coerceIn(MAX_ZOOM_RANGE)
        return copy(
            scrollFraction = scrollFraction.coerceIn(SCROLL_FRACTION_RANGE),
            autoTurnSeconds = autoTurnSeconds.coerceIn(AUTO_TURN_SECONDS_RANGE),
            autoScrollSpeed = autoScrollSpeed.coerceIn(AUTO_SCROLL_SPEED_RANGE),
            minZoom = min,
            maxZoom = max,
            doubleTapZoom = doubleTapZoom.coerceIn(DOUBLE_TAP_ZOOM_RANGE).coerceIn(min, max),
            gestureSpeed = gestureSpeed.coerceIn(GESTURE_SPEED_RANGE),
            preloadAhead = preloadAhead.coerceIn(PRELOAD_AHEAD_RANGE),
        )
    }

    companion object {
        val SCROLL_FRACTION_RANGE: ClosedFloatingPointRange<Float> = 0.1f..1f
        val AUTO_TURN_SECONDS_RANGE: IntRange = 2..30
        val AUTO_SCROLL_SPEED_RANGE: IntRange = 1..5
        val MIN_ZOOM_RANGE: ClosedFloatingPointRange<Float> = 0.5f..2f
        val MAX_ZOOM_RANGE: ClosedFloatingPointRange<Float> = 2f..5f
        val DOUBLE_TAP_ZOOM_RANGE: ClosedFloatingPointRange<Float> = 1.5f..3f
        val GESTURE_SPEED_RANGE: ClosedFloatingPointRange<Float> = 0.5f..3f
        val PRELOAD_AHEAD_RANGE: IntRange = 1..5

        /** Pages kept warm behind the current page. */
        const val PRELOAD_BEHIND: Int = 1
    }
}

/** Whether [mode] scrolls as one continuous strip, where 自动翻页 becomes 自动滚动. */
val ReaderMode.isContinuous: Boolean
    get() = this == ReaderMode.Strip || this == ReaderMode.FreeZoom

/** 阅读模式. */
enum class ReaderMode {
    /** 条漫: one continuous, seamless strip. */
    Strip,

    /** 翻页: one page per screen. */
    Paged,

    /** 双页: two pages side by side (landscape). */
    DoublePage,

    /** 自由缩放: a strip that can be zoomed and dragged freely. */
    FreeZoom,
}

enum class SystemBarsMode { Visible, HiddenNavigation, HiddenAll }

/** 翻页方向. */
enum class ReaderDirection {
    TopToBottom,
    LeftToRight,
    RightToLeft,
}

/** 操控方式. */
enum class TapMode {
    /** 单击全屏: a tap anywhere shows or hides the controls; double tap zooms. */
    TapToToggle,

    /** 控制器: taps on the image do nothing; the page badge shows or hides the controls. */
    Controller,

    /** 双击全屏: a double tap shows or hides the controls; zoom with two fingers. */
    DoubleTapToToggle,

    /** 双击全屏 + 单击下一页. */
    DoubleTapToggleTapNext,

    /** 三区域: 30% 上一页 / 40% 菜单 / 30% 下一页; double tap zooms. */
    ThreeZones,
}

/** How the strip moves on 下一页 / 上一页 (tap zones, volume keys). */
enum class StripPaging {
    /** 按张: jump to the next or previous image. */
    ByImage,

    /** 按屏幕: scroll by [ReaderConfig.scrollFraction] of the screen. */
    ByScreen,
}

/** 阅读器背景. */
enum class ReaderBackground {
    /** 黑 #000000. */
    Black,

    /** 灰 #3A3A3A. */
    Gray,

    /** 纸 #F4EEDF. */
    Paper,

    /** 白 #FFFFFF. */
    White,
}

/** 图片滤镜. */
enum class ReaderFilter {
    /** 正常. */
    Normal,

    /** 灰度. */
    Grayscale,

    /** 棕褐. */
    Sepia,
}

/** 画质. */
enum class ImageQuality {
    Original,
    High,
    Medium,
    Low,
}

/** 滑块位置. */
enum class SliderPosition {
    Bottom,
    Left,
    Right,
}

/** 双页排列. */
enum class TwoPageArrangement {
    /** 靠近: both pages lean towards the spine. */
    CloseTo,

    /** 远离: both pages lean towards the outer edges. */
    PullAway,

    /** 各自居中: each page centred in its half. */
    EachCentered,
}
