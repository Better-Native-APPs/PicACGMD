package app.picacgmd.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SettingsRepositoryTest {
    @Test
    fun requiredTypedSettingsRoundTrip() = runTest {
        val repository = SettingsRepository(MemoryPreferencesStore())
        repository.setAccountEmail("reader@example.com")
        repository.setAuthToken("saved-token", 123L)
        repository.setApiRouting(RoutingMode.CUSTOM, "203.0.113.1")
        repository.setImageRouting(RoutingMode.CUSTOM, "images.example.com")
        repository.setProxyUrl("socks5://localhost:1080")
        repository.setImageQuality(ImageQuality.HIGH)
        repository.setTheme(AppTheme.GREY_BLUE)
        repository.setDarkMode(DarkMode.DARK)
        repository.setPureBlack(true)
        repository.setListLayout(ListLayout.COVER_AND_TITLE)
        repository.setListColumnCount(7)
        repository.setCategoryColumnCount(2)
        repository.setPaginationMode(PaginationMode.BUTTONS)
        repository.setShieldedCategories(listOf("分类一", "分类二"))
        repository.setCategoryShieldMode(CategoryShieldMode.WHITELIST)
        repository.setHiddenKeywords(listOf("关键词"))
        repository.setCategoryOrder(listOf("分类二", "分类一"))
        repository.setPinnedHomeCategories(listOf("分类二"))
        repository.setReaderMode(ReaderMode.TWO_PAGE_GALLERY)
        repository.setReaderDirection(ReaderDirection.RIGHT_TO_LEFT)
        repository.setReaderTapZones(ReaderTapZones.THREE_ZONES)
        repository.setVolumeKeyPaging(true)
        repository.setReaderBackground(ReaderBackground.PAPER)
        repository.setReaderFilter(ReaderFilter.SEPIA)
        repository.setKeepScreenOn(false)
        repository.setReaderFullScreen(true)
        repository.setDownloadThreads(16)
        repository.setDownloadWifiOnly(true)
        repository.setAutoDeleteDownloadOnUnfavourite(true)
        repository.setAutoPunchIn(false)
        repository.setAppLockEnabled(true)
        repository.setBiometricUnlockEnabled(false)
        repository.setAppLockTimeoutSeconds(60)
        repository.setPreventScreenshots(true)
        repository.setHistoryEnabled(false)
        repository.setAutoCleanCacheInterval(CacheCleanupInterval.WEEK)
        assertEquals(
            AppSettings.DEFAULT.copy(
                accountEmail = "reader@example.com", authToken = "saved-token", authTokenUpdatedAt = 123L,
                apiRoutingMode = RoutingMode.CUSTOM, customApiIp = "203.0.113.1",
                imageRoutingMode = RoutingMode.CUSTOM, customImageHost = "images.example.com",
                proxyUrl = "socks5://localhost:1080", imageQuality = ImageQuality.HIGH,
                theme = AppTheme.GREY_BLUE, darkMode = DarkMode.DARK, pureBlack = true,
                listLayout = ListLayout.COVER_AND_TITLE, listColumnCount = 7, categoryColumnCount = 2,
                paginationMode = PaginationMode.BUTTONS,
                shieldedCategories = listOf("分类一", "分类二"), categoryShieldMode = CategoryShieldMode.WHITELIST,
                hiddenKeywords = listOf("关键词"), categoryOrder = listOf("分类二", "分类一"),
                pinnedHomeCategories = listOf("分类二"), readerMode = ReaderMode.TWO_PAGE_GALLERY,
                readerDirection = ReaderDirection.RIGHT_TO_LEFT, readerTapZones = ReaderTapZones.THREE_ZONES,
                volumeKeyPaging = true, readerBackground = ReaderBackground.PAPER, readerFilter = ReaderFilter.SEPIA,
                keepScreenOn = false, readerFullScreen = true, downloadThreads = 16, downloadWifiOnly = true,
                autoDeleteDownloadOnUnfavourite = true, autoPunchIn = false, appLockEnabled = true,
                biometricUnlockEnabled = false, appLockTimeoutSeconds = 60, preventScreenshots = true,
                historyEnabled = false, autoCleanCacheInterval = CacheCleanupInterval.WEEK,
            ),
            repository.settings.first(),
        )
    }

    @Test
    fun remainingNativeSettingsRoundTrip() = runTest {
        val repository = SettingsRepository(MemoryPreferencesStore())
        repository.setConnectTimeoutSeconds(60)
        repository.setReadTimeoutSeconds(10)
        repository.setTimeOffsetHours(8)
        repository.setUseApiClientForImages(true)
        repository.setAppOrientation(AppOrientation.LANDSCAPE)
        repository.setDisplayModeId(2)
        repository.setFullScreenDisplay(FullScreenDisplay.HIDE_SYSTEM_BARS)
        repository.setDoubleBackToExit(true)
        repository.setStartupImageUri("content://images/startup")
        repository.setFailedReloadAction(FailedReloadAction.PULL_DOWN)
        repository.setHideViewedComics(true)
        repository.setHiddenCategories(listOf("隐藏分类"))
        repository.setShowAuthorSearch(false)
        repository.setShowSubscriptions(false)
        repository.setShowTagFavourites(false)
        repository.setShowOnlineFavourites(false)
        repository.setShowLocalFavourites(false)
        repository.setLastFavouriteFolderId(4)
        repository.setCopyNameTemplate("{title}")
        repository.setConfirmCopy(false)
        repository.setVolumeKeyChapterPaging(false)
        repository.setFullScreenOnForward(true)
        repository.setReaderPageAnimation(false)
        repository.setThreeZoneNextOnRight(true)
        repository.setReaderScrollMode(ReaderScrollMode.SCREEN_DISTANCE)
        repository.setScrollScreenFraction(0.5f)
        repository.setReaderSliderPosition(ReaderSliderPosition.LEFT)
        repository.setTwoPagePlacement(TwoPagePlacement.EACH_CENTERED)
        repository.setGestureSpeed(1.5f)
        repository.setReaderZoom(0.5f, 5f, 3f)
        repository.setLockDragBounds(true)
        repository.setEInkReduceAnimations(true)
        repository.setEInkScrollByScreen(true)
        repository.setEInkScrollAmount(0.4f)
        repository.setEInkTriggerDistanceCm(0.2f)
        repository.setAutoDownloadOnFavourite(true)
        repository.setAutoDownloadWifiOnly(false)
        repository.setShowCommentsInDownloads(true)
        repository.setRecordDetailViews(false)
        assertEquals(
            AppSettings.DEFAULT.copy(
                connectTimeoutSeconds = 60, readTimeoutSeconds = 10, timeOffsetHours = 8,
                useApiClientForImages = true, appOrientation = AppOrientation.LANDSCAPE, displayModeId = 2,
                fullScreenDisplay = FullScreenDisplay.HIDE_SYSTEM_BARS, doubleBackToExit = true,
                startupImageUri = "content://images/startup", failedReloadAction = FailedReloadAction.PULL_DOWN,
                hideViewedComics = true, hiddenCategories = listOf("隐藏分类"), showAuthorSearch = false,
                showSubscriptions = false, showTagFavourites = false, showOnlineFavourites = false,
                showLocalFavourites = false, lastFavouriteFolderId = 4, copyNameTemplate = "{title}", confirmCopy = false,
                volumeKeyChapterPaging = false, fullScreenOnForward = true, readerPageAnimation = false,
                threeZoneNextOnRight = true, readerScrollMode = ReaderScrollMode.SCREEN_DISTANCE,
                scrollScreenFraction = 0.5f, readerSliderPosition = ReaderSliderPosition.LEFT,
                twoPagePlacement = TwoPagePlacement.EACH_CENTERED, gestureSpeed = 1.5f,
                minZoom = 0.5f, maxZoom = 5f, doubleTapZoom = 3f, lockDragBounds = true,
                eInkReduceAnimations = true, eInkScrollByScreen = true, eInkScrollAmount = 0.4f,
                eInkTriggerDistanceCm = 0.2f, autoDownloadOnFavourite = true, autoDownloadWifiOnly = false,
                showCommentsInDownloads = true, recordDetailViews = false,
            ),
            repository.settings.first(),
        )
    }

    @Test
    fun pinsPreserveOrderSpellingAndExplicitEmptySelection() = runTest {
        val repository = SettingsRepository(MemoryPreferencesStore())
        repository.setPinnedHomeCategories(listOf("大濕推薦", "分类一", "大濕推薦", "大家都在看"))
        assertEquals(listOf("大濕推薦", "分类一", "大家都在看"), repository.settings.first().pinnedHomeCategories)
        assertFailsWith<IllegalArgumentException> { repository.setPinnedHomeCategories(listOf("")) }
        repository.setPinnedHomeCategories(emptyList())
        assertEquals(emptyList(), repository.settings.first().pinnedHomeCategories)
    }

    @Test
    fun everyAllowedThreadCountWorksAndInvalidCountsDoNotWrite() = runTest {
        val repository = SettingsRepository(MemoryPreferencesStore())
        for (count in 1..16) {
            repository.setDownloadThreads(count)
            assertEquals(count, repository.settings.first().downloadThreads)
        }
        for (count in listOf(-1, 0, 17)) {
            assertFailsWith<IllegalArgumentException> { repository.setDownloadThreads(count) }
        }
        assertEquals(16, repository.settings.first().downloadThreads)
    }

    @Test
    fun automaticAndTwoThroughSevenColumnsWorkButOneDoesNot() = runTest {
        val repository = SettingsRepository(MemoryPreferencesStore())
        for (count in listOf(0, 2, 3, 4, 5, 6, 7)) {
            repository.setListColumnCount(count)
            repository.setCategoryColumnCount(count)
            assertEquals(count, repository.settings.first().listColumnCount)
            assertEquals(count, repository.settings.first().categoryColumnCount)
        }
        assertFailsWith<IllegalArgumentException> { repository.setListColumnCount(1) }
        assertFailsWith<IllegalArgumentException> { repository.setCategoryColumnCount(8) }
    }

    @Test
    fun tokenClearingAlsoRemovesTimestampAndDiagnosticsNeverLogToken() = runTest {
        val store = MemoryPreferencesStore()
        val repository = SettingsRepository(store)
        repository.setAuthToken("secret-bearer-token", 42)
        assertFalse(repository.settings.first().toString().contains("secret-bearer-token"))
        repository.setAuthToken(null)
        assertNull(store.data.first()[SettingsKeys.authToken])
        assertNull(store.data.first()[SettingsKeys.authTokenUpdatedAt])
        assertNull(repository.settings.first().authToken)
    }

    @Test
    fun customRoutesRequireAddressesAndRememberThemWhenChangingRoutes() = runTest {
        val repository = SettingsRepository(MemoryPreferencesStore())
        assertFailsWith<IllegalArgumentException> { repository.setApiRouting(RoutingMode.CUSTOM) }
        assertFailsWith<IllegalArgumentException> { repository.setImageRouting(RoutingMode.CUSTOM) }
        repository.setApiRouting(RoutingMode.CUSTOM, "203.0.113.1")
        repository.setApiRouting(RoutingMode.NONE)
        repository.setApiRouting(RoutingMode.CUSTOM)
        repository.setImageRouting(RoutingMode.CUSTOM, "images.example.com")
        repository.setImageRouting(RoutingMode.ROUTE_2)
        repository.setImageRouting(RoutingMode.CUSTOM)
        assertEquals("203.0.113.1", repository.settings.first().customApiIp)
        assertEquals("images.example.com", repository.settings.first().customImageHost)
    }

    @Test
    fun zoomValidationIsAtomic() = runTest {
        val repository = SettingsRepository(MemoryPreferencesStore())
        assertFailsWith<IllegalArgumentException> { repository.setReaderZoom(2f, 2f, 1.5f) }
        assertFailsWith<IllegalArgumentException> { repository.setReaderZoom(Float.NaN, 3f, 2f) }
        assertEquals(AppSettings.DEFAULT, repository.settings.first())
    }

    @Test
    fun readFailurePropagatesInsteadOfReturningDefaults() = runTest {
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("Preferences unavailable") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("Preferences unavailable")
        }
        val error = assertFailsWith<IOException> { SettingsRepository(store).settings.first() }
        assertEquals("Preferences unavailable", error.message)
    }
}

/** JVM-only store: no Android Context, file APIs, instrumentation or Robolectric. */
private class MemoryPreferencesStore : DataStore<Preferences> {
    private val values = MutableStateFlow<Preferences>(emptyPreferences())
    private val mutex = Mutex()
    override val data: Flow<Preferences> = values

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
        transform(values.value).also { values.value = it }
    }
}
