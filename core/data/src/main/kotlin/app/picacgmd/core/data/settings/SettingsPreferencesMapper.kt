package app.picacgmd.core.data.settings

import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import app.picacgmd.core.data.settings.SettingsKeys as Keys

/** Pure mapping, suitable for JVM tests. Missing/unsupported stored values use documented defaults. */
internal object SettingsPreferencesMapper {
    fun decode(preferences: Preferences): AppSettings {
        val d = AppSettings.DEFAULT
        val token = preferences[Keys.authToken]?.takeIf { it.isNotBlank() }
        val minimum = preferences.readFloat(Keys.minZoom, d.minZoom, 0.5f..2f)
        val maximum = preferences.readFloat(Keys.maxZoom, d.maxZoom, 2f..5f)
        val doubleTap = preferences.readFloat(Keys.doubleTapZoom, d.doubleTapZoom, 1.5f..3f)
        val validZoom = minimum <= doubleTap && doubleTap <= maximum
        return AppSettings(
            accountEmail = preferences[Keys.accountEmail] ?: d.accountEmail,
            authToken = token,
            authTokenUpdatedAt = if (token != null) preferences[Keys.authTokenUpdatedAt] else null,
            apiRouteAddress = if (preferences[Keys.apiRouteAddress] != null) {
                preferences[Keys.apiRouteAddress]?.takeIf(String::isNotEmpty)
            } else {
                when (preferences.readEnum(Keys.apiRoutingMode, RoutingMode.ROUTE_1)) {
                    RoutingMode.NONE -> null
                    RoutingMode.ROUTE_1 -> defaultApiAddresses[0]
                    RoutingMode.ROUTE_2 -> defaultApiAddresses[1]
                    RoutingMode.ROUTE_3 -> defaultApiAddresses[2]
                    RoutingMode.CUSTOM -> preferences[Keys.customApiIp]?.trim().orEmpty()
                }
            },
            customApiIp = preferences[Keys.customApiIp] ?: d.customApiIp,
            syncedApiAddresses = preferences.readList(Keys.syncedApiAddresses, emptyList()),
            apiRoutesSyncedAt = preferences[Keys.apiRoutesSyncedAt],
            imageRoutingMode = preferences.readEnum(Keys.imageRoutingMode, d.imageRoutingMode),
            customImageHost = preferences[Keys.customImageHost] ?: d.customImageHost,
            proxyUrl = preferences[Keys.proxyUrl] ?: d.proxyUrl,
            imageQuality = preferences.readEnum(Keys.imageQuality, d.imageQuality),
            connectTimeoutSeconds = preferences.readInt(Keys.connectTimeoutSeconds, d.connectTimeoutSeconds, SettingsValidation::isTimeout),
            readTimeoutSeconds = preferences.readInt(Keys.readTimeoutSeconds, d.readTimeoutSeconds, SettingsValidation::isTimeout),
            timeOffsetHours = preferences.readInt(Keys.timeOffsetHours, d.timeOffsetHours) { it in -12..12 },
            useApiClientForImages = preferences[Keys.useApiClientForImages] ?: d.useApiClientForImages,
            theme = preferences.readEnum(Keys.theme, d.theme),
            darkMode = preferences.readEnum(Keys.darkMode, d.darkMode),
            pureBlack = preferences[Keys.pureBlack] ?: d.pureBlack,
            appOrientation = preferences.readEnum(Keys.appOrientation, d.appOrientation),
            displayModeId = preferences[Keys.displayModeId]?.takeIf { it > 0 },
            fullScreenDisplay = preferences.readEnum(Keys.fullScreenDisplay, d.fullScreenDisplay),
            doubleBackToExit = preferences[Keys.doubleBackToExit] ?: d.doubleBackToExit,
            startupImageUri = preferences[Keys.startupImageUri],
            lastSeenAnnouncementId = preferences[Keys.lastSeenAnnouncementId],
            listLayout = preferences.readEnum(Keys.listLayout, d.listLayout),
            listColumnCount = preferences.readInt(Keys.listColumnCount, d.listColumnCount, SettingsValidation::isColumnCount),
            categoryColumnCount = preferences.readInt(Keys.categoryColumnCount, d.categoryColumnCount, SettingsValidation::isColumnCount),
            paginationMode = preferences.readEnum(Keys.paginationMode, d.paginationMode),
            failedReloadAction = preferences.readEnum(Keys.failedReloadAction, d.failedReloadAction),
            shieldedCategories = preferences.readList(Keys.shieldedCategories, d.shieldedCategories),
            categoryShieldMode = preferences.readEnum(Keys.categoryShieldMode, d.categoryShieldMode),
            hiddenKeywords = preferences.readList(Keys.hiddenKeywords, d.hiddenKeywords),
            hideViewedComics = preferences[Keys.hideViewedComics] ?: d.hideViewedComics,
            hideAiComics = preferences[Keys.hideAiComics] ?: d.hideAiComics,
            hiddenCategories = preferences.readList(Keys.hiddenCategories, d.hiddenCategories),
            categoryOrder = preferences.readList(Keys.categoryOrder, d.categoryOrder),
            pinnedHomeCategories = preferences.readList(Keys.pinnedHomeCategories, d.pinnedHomeCategories),
            showAuthorSearch = preferences[Keys.showAuthorSearch] ?: d.showAuthorSearch,
            showSubscriptions = preferences[Keys.showSubscriptions] ?: d.showSubscriptions,
            showTagFavourites = preferences[Keys.showTagFavourites] ?: d.showTagFavourites,
            showOnlineFavourites = preferences[Keys.showOnlineFavourites] ?: d.showOnlineFavourites,
            copyNameTemplate = preferences[Keys.copyNameTemplate] ?: d.copyNameTemplate,
            confirmCopy = preferences[Keys.confirmCopy] ?: d.confirmCopy,
            readerMode = preferences.readEnum(Keys.readerMode, d.readerMode),
            readerDirection = preferences.readEnum(Keys.readerDirection, d.readerDirection),
            readerTapZones = preferences.readEnum(Keys.readerTapZones, d.readerTapZones),
            volumeKeyPaging = preferences[Keys.volumeKeyPaging] ?: d.volumeKeyPaging,
            volumeKeyChapterPaging = preferences[Keys.volumeKeyChapterPaging] ?: d.volumeKeyChapterPaging,
            readerBackground = preferences.readEnum(Keys.readerBackground, d.readerBackground),
            readerFilter = preferences.readEnum(Keys.readerFilter, d.readerFilter),
            keepScreenOn = preferences[Keys.keepScreenOn] ?: d.keepScreenOn,
            readerFullScreen = preferences[Keys.readerFullScreen] ?: d.readerFullScreen,
            fullScreenOnForward = preferences[Keys.fullScreenOnForward] ?: d.fullScreenOnForward,
            readerPageAnimation = preferences[Keys.readerPageAnimation] ?: d.readerPageAnimation,
            threeZoneNextOnRight = preferences[Keys.threeZoneNextOnRight] ?: d.threeZoneNextOnRight,
            readerScrollMode = preferences.readEnum(Keys.readerScrollMode, d.readerScrollMode),
            scrollScreenFraction = preferences.readFloat(Keys.scrollScreenFraction, d.scrollScreenFraction, 0.1f..1f),
            readerSliderPosition = preferences.readEnum(Keys.readerSliderPosition, d.readerSliderPosition),
            twoPagePlacement = preferences.readEnum(Keys.twoPagePlacement, d.twoPagePlacement),
            twoPageCoverAlone = preferences[Keys.twoPageCoverAlone] ?: d.twoPageCoverAlone,
            readerPreloadAhead = preferences.readInt(Keys.readerPreloadAhead, d.readerPreloadAhead) { it in 1..5 },
            readerAutoTurnSeconds = preferences.readInt(Keys.readerAutoTurnSeconds, d.readerAutoTurnSeconds) { it in 2..30 },
            gestureSpeed = preferences.readFloat(Keys.gestureSpeed, d.gestureSpeed, 0.5f..3f),
            minZoom = if (validZoom) minimum else d.minZoom,
            maxZoom = if (validZoom) maximum else d.maxZoom,
            doubleTapZoom = if (validZoom) doubleTap else d.doubleTapZoom,
            lockDragBounds = preferences[Keys.lockDragBounds] ?: d.lockDragBounds,
            eInkReduceAnimations = preferences[Keys.eInkReduceAnimations] ?: d.eInkReduceAnimations,
            eInkScrollByScreen = preferences[Keys.eInkScrollByScreen] ?: d.eInkScrollByScreen,
            eInkScrollAmount = preferences.readFloat(Keys.eInkScrollAmount, d.eInkScrollAmount, 0.1f..1f),
            eInkTriggerDistanceCm = preferences.readFloat(Keys.eInkTriggerDistanceCm, d.eInkTriggerDistanceCm, 0.1f..2f),
            downloadThreads = preferences.readInt(Keys.downloadThreads, d.downloadThreads, SettingsValidation::isDownloadThreads),
            downloadWifiOnly = preferences[Keys.downloadWifiOnly] ?: d.downloadWifiOnly,
            autoDownloadOnFavourite = preferences[Keys.autoDownloadOnFavourite] ?: d.autoDownloadOnFavourite,
            autoDownloadWifiOnly = preferences[Keys.autoDownloadWifiOnly] ?: d.autoDownloadWifiOnly,
            autoDeleteDownloadOnUnfavourite = preferences[Keys.autoDeleteDownloadOnUnfavourite] ?: d.autoDeleteDownloadOnUnfavourite,
            showCommentsInDownloads = preferences[Keys.showCommentsInDownloads] ?: d.showCommentsInDownloads,
            autoPunchIn = preferences[Keys.autoPunchIn] ?: d.autoPunchIn,
            appLockEnabled = preferences[Keys.appLockEnabled] ?: d.appLockEnabled,
            biometricUnlockEnabled = preferences[Keys.biometricUnlockEnabled] ?: d.biometricUnlockEnabled,
            appLockTimeoutSeconds = preferences.readInt(Keys.appLockTimeoutSeconds, d.appLockTimeoutSeconds, SettingsValidation::isLockTimeout),
            preventScreenshots = preferences[Keys.preventScreenshots] ?: d.preventScreenshots,
            historyEnabled = preferences[Keys.historyEnabled] ?: d.historyEnabled,
            recordDetailViews = preferences[Keys.recordDetailViews] ?: d.recordDetailViews,
            autoCleanCacheInterval = preferences.readEnum(Keys.autoCleanCacheInterval, d.autoCleanCacheInterval),
        )
    }

    private inline fun <reified T : Enum<T>> Preferences.readEnum(key: Preferences.Key<String>, default: T): T =
        this[key]?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: default

    private fun Preferences.readInt(key: Preferences.Key<Int>, default: Int, valid: (Int) -> Boolean): Int =
        this[key]?.takeIf(valid) ?: default

    private fun Preferences.readFloat(key: Preferences.Key<Float>, default: Float, range: ClosedFloatingPointRange<Float>): Float =
        this[key]?.takeIf { it in range } ?: default

    private fun Preferences.readList(key: Preferences.Key<String>, default: List<String>): List<String> {
        val stored = this[key] ?: return default
        return try {
            Json.decodeFromString<List<String>>(stored).takeIf { values -> values.all { it.isNotBlank() } }
                ?.distinct() ?: default
        } catch (_: SerializationException) {
            default
        }
    }
}
