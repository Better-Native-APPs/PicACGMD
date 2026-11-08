package app.picacgmd.core.data.settings

enum class RoutingMode { NONE, ROUTE_1, ROUTE_2, ROUTE_3, CUSTOM }

val defaultApiAddresses: List<String> = listOf("172.67.7.24", "104.20.180.50", "172.67.208.169")
enum class ImageQuality { ORIGINAL, LOW, MEDIUM, HIGH }
enum class AppTheme { DYNAMIC, BRAND_PINK, BOOK, GREY_BLUE }
enum class DarkMode { SYSTEM, LIGHT, DARK }
enum class ListLayout { DETAILS, COVER, COVER_AND_TITLE }
enum class PaginationMode { INFINITE, BUTTONS }
enum class CategoryShieldMode { BLACKLIST, WHITELIST }
enum class ReaderMode { WEBTOON, WEBTOON_ZOOM, GALLERY, WEBTOON_FREE_ZOOM, TWO_PAGE_GALLERY }
enum class ReaderDirection { TOP_TO_BOTTOM, LEFT_TO_RIGHT, RIGHT_TO_LEFT }
enum class ReaderTapZones { SINGLE_TAP, CONTROLLER, DOUBLE_TAP, DOUBLE_TAP_NEXT, THREE_ZONES }
enum class ReaderBackground { BLACK, GREY, PAPER, WHITE }
enum class ReaderFilter { NORMAL, GRAYSCALE, SEPIA }
enum class ReaderScrollMode { IMAGE, SCREEN_DISTANCE }
enum class ReaderSliderPosition { BOTTOM, RIGHT, LEFT }
enum class TwoPagePlacement { CLOSE_TO, PULL_APART, EACH_CENTERED }
enum class AppOrientation { SYSTEM, LANDSCAPE, PORTRAIT }
enum class FullScreenDisplay { OFF, HIDE_NAVIGATION, HIDE_SYSTEM_BARS }
enum class FailedReloadAction { TAP, PULL_DOWN }

enum class CacheCleanupInterval(val seconds: Long) {
    NEVER(0), DAY(86_400), WEEK(604_800), MONTH(2_592_000),
}

/**
 * Immutable app preferences. Defaults:
 * - No saved account/token/proxy/custom hosts. API uses the first default address; images use original hosts.
 * - Dynamic theme (brand-pink fallback on Android < 12), system dark mode, pure black off.
 * - Detail lists, automatic columns (0; explicit values 2–7), infinite pagination (B1).
 * - No shields/hidden words/categories; blacklist mode. Home pins are ordered server category names
 *   ["大家都在看", "大濕推薦"]. All bookshelf/search feature entries are visible.
 * - Webtoon, top-to-bottom, controller tap zones, black background, normal filter, keep screen on.
 *   Volume paging and automatic fullscreen are off; double-volume chapter paging is on.
 * - Download concurrency 4 (allowed 1–16). Manual downloads may use mobile data. Auto-download is
 *   off, restricted to Wi-Fi when enabled; auto-delete and download comments are off.
 * - Automatic punch-in is on (B9). App lock/screenshot prevention are off; biometric unlock is
 *   available when locking is enabled. History/detail recording is on; automatic cache cleanup off.
 * - Connection/read timeouts 15/30 seconds; time offset 0; API image loading off.
 * - All remaining opt-in behavior is off, except copy confirmation and reader page animations.
 *   Reader zoom defaults 1×/3×/2×; screen scroll 0.8, gesture speed 1, e-ink trigger 0.3 cm.
 *
 * No password is represented or persisted. Tokens are app-private Preferences values, not encrypted.
 */
data class AppSettings(
    val accountEmail: String = "",
    val authToken: String? = null,
    val authTokenUpdatedAt: Long? = null,
    /** Null uses system DNS; address identity is independent of the current list order. */
    val apiRouteAddress: String? = defaultApiAddresses.first(),
    val customApiIp: String = "",
    val syncedApiAddresses: List<String> = emptyList(),
    val apiRoutesSyncedAt: Long? = null,
    // Original file servers by default: with a working proxy they are the fastest and most reliable;
    // the image 分流 hosts stay available in 设置 › 网络 for networks where the originals are blocked.
    val imageRoutingMode: RoutingMode = RoutingMode.NONE,
    val customImageHost: String = "",
    val proxyUrl: String = "",
    val imageQuality: ImageQuality = ImageQuality.ORIGINAL,
    val connectTimeoutSeconds: Int = 15,
    val readTimeoutSeconds: Int = 30,
    val timeOffsetHours: Int = 0,
    val useApiClientForImages: Boolean = false,
    val theme: AppTheme = AppTheme.DYNAMIC,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val pureBlack: Boolean = false,
    val appOrientation: AppOrientation = AppOrientation.SYSTEM,
    val displayModeId: Int? = null,
    val fullScreenDisplay: FullScreenDisplay = FullScreenDisplay.OFF,
    val doubleBackToExit: Boolean = false,
    val startupImageUri: String? = null,
    /** Id of the newest 公告 the user has opened the announcement list for; drives the 我的 unread dot. */
    val lastSeenAnnouncementId: String? = null,
    val listLayout: ListLayout = ListLayout.DETAILS,
    val listColumnCount: Int = 0,
    val categoryColumnCount: Int = 0,
    val paginationMode: PaginationMode = PaginationMode.INFINITE,
    val failedReloadAction: FailedReloadAction = FailedReloadAction.TAP,
    val shieldedCategories: List<String> = emptyList(),
    val categoryShieldMode: CategoryShieldMode = CategoryShieldMode.BLACKLIST,
    val hiddenKeywords: List<String> = emptyList(),
    val hideViewedComics: Boolean = false,
    val hideAiComics: Boolean = false,
    val hiddenCategories: List<String> = emptyList(),
    val categoryOrder: List<String> = emptyList(),
    val pinnedHomeCategories: List<String> = listOf("大家都在看", "大濕推薦"),
    val showAuthorSearch: Boolean = true,
    val showSubscriptions: Boolean = true,
    val showTagFavourites: Boolean = true,
    val showOnlineFavourites: Boolean = true,
    val copyNameTemplate: String = "[{author}] {title}",
    val confirmCopy: Boolean = true,
    val readerMode: ReaderMode = ReaderMode.WEBTOON,
    val readerDirection: ReaderDirection = ReaderDirection.TOP_TO_BOTTOM,
    // A single tap shows/hides the reader controls (design default); 控制器 needs the 全屏 button to hide them.
    val readerTapZones: ReaderTapZones = ReaderTapZones.SINGLE_TAP,
    val volumeKeyPaging: Boolean = false,
    val volumeKeyChapterPaging: Boolean = true,
    val readerBackground: ReaderBackground = ReaderBackground.BLACK,
    val readerFilter: ReaderFilter = ReaderFilter.NORMAL,
    val keepScreenOn: Boolean = true,
    val readerFullScreen: Boolean = false,
    val fullScreenOnForward: Boolean = false,
    val readerPageAnimation: Boolean = true,
    val threeZoneNextOnRight: Boolean = false,
    val readerScrollMode: ReaderScrollMode = ReaderScrollMode.IMAGE,
    val scrollScreenFraction: Float = 0.8f,
    val readerSliderPosition: ReaderSliderPosition = ReaderSliderPosition.BOTTOM,
    val twoPagePlacement: TwoPagePlacement = TwoPagePlacement.CLOSE_TO,
    val twoPageCoverAlone: Boolean = false,
    val readerPreloadAhead: Int = 2,
    val readerAutoTurnSeconds: Int = 6,
    val readerAutoScrollSpeed: Int = 3,
    val readerAutoScrollPauseOnTouch: Boolean = true,
    val readerAutoNextChapter: Boolean = false,
    val gestureSpeed: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 3f,
    val doubleTapZoom: Float = 2f,
    val lockDragBounds: Boolean = false,
    val eInkReduceAnimations: Boolean = false,
    val eInkScrollByScreen: Boolean = false,
    val eInkScrollAmount: Float = 0.8f,
    val eInkTriggerDistanceCm: Float = 0.3f,
    val downloadThreads: Int = 4,
    val downloadWifiOnly: Boolean = false,
    val autoDownloadOnFavourite: Boolean = false,
    val autoDownloadWifiOnly: Boolean = true,
    val autoDeleteDownloadOnUnfavourite: Boolean = false,
    val showCommentsInDownloads: Boolean = false,
    val autoPunchIn: Boolean = true,
    val appLockEnabled: Boolean = false,
    val biometricUnlockEnabled: Boolean = true,
    val appLockTimeoutSeconds: Int = 0,
    val preventScreenshots: Boolean = false,
    val historyEnabled: Boolean = true,
    val recordDetailViews: Boolean = true,
    val autoCleanCacheInterval: CacheCleanupInterval = CacheCleanupInterval.NEVER,
) {
    /** Do not expose a saved bearer token through diagnostic logs. */
    override fun toString(): String =
        "AppSettings(theme=$theme, darkMode=$darkMode, downloadThreads=$downloadThreads, " +
            "hasAuthToken=${authToken != null})"

    companion object {
        val DEFAULT: AppSettings = AppSettings()
    }
}

internal object SettingsValidation {
    fun isColumnCount(value: Int): Boolean = value == 0 || value in 2..7
    fun isDownloadThreads(value: Int): Boolean = value in 1..16
    fun isTimeout(value: Int): Boolean = value in setOf(10, 15, 30, 60)
    fun isLockTimeout(value: Int): Boolean = value in setOf(0, 1, 10, 60, 180, 600, 3_600)

    fun requireColumns(value: Int) {
        require(isColumnCount(value)) { "Column count must be 0 (auto) or 2..7" }
    }

    fun requireThreads(value: Int) {
        require(isDownloadThreads(value)) { "Download threads must be 1..16" }
    }

    fun requireTimeout(value: Int) {
        require(isTimeout(value)) { "Timeout must be 10, 15, 30 or 60 seconds" }
    }

    fun requireLockTimeout(value: Int) {
        require(isLockTimeout(value)) { "Lock timeout must be 0, 1, 10, 60, 180, 600 or 3600 seconds" }
    }

    fun requireZoom(minimum: Float, maximum: Float, doubleTap: Float) {
        require(minimum in 0.5f..2f && maximum in 2f..5f && doubleTap in 1.5f..3f) {
            "Zoom must be within minimum 0.5..2, maximum 2..5 and double-tap 1.5..3"
        }
        require(minimum <= doubleTap && doubleTap <= maximum) { "Zoom must satisfy minimum <= double-tap <= maximum" }
    }
}
