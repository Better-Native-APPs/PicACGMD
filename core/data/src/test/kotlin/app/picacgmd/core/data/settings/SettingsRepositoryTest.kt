package app.picacgmd.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
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
import kotlin.test.assertTrue

class SettingsRepositoryTest {
    @Test
    fun automaticRouteSyncDefaultsOnAndItsChoicesSurviveNewRepositoryInstances() = runTest {
        val store = MemoryPreferencesStore()
        val repository = SettingsRepository(store)
        val key = booleanPreferencesKey("auto_sync_api_routes")
        assertTrue(repository.settings.first().autoSyncApiRoutes)
        assertNull(store.data.first()[key])
        repository.setSyncedApiAddresses(defaultApiAddresses, syncedAt = 1234L)
        val before = repository.settings.first()
        for (enabled in listOf(false, true)) {
            repository.setAutoSyncApiRoutes(enabled)
            assertEquals(enabled, store.data.first()[key])
            assertEquals(before.copy(autoSyncApiRoutes = enabled), SettingsRepository(store).settings.first())
        }
    }

    @Test
    fun allFullScreenModesRoundTripByNameAcrossRepositoryInstances() = runTest {
        val store = MemoryPreferencesStore()
        val repository = SettingsRepository(store)
        assertEquals(
            listOf("OFF", "HIDE_STATUS_BAR", "HIDE_NAVIGATION", "HIDE_SYSTEM_BARS"),
            FullScreenDisplay.entries.map { it.name },
        )
        FullScreenDisplay.entries.forEach { mode ->
            repository.setFullScreenDisplay(mode)
            assertEquals(mode.name, store.data.first()[SettingsKeys.fullScreenDisplay])
            assertEquals(mode, SettingsRepository(store).settings.first().fullScreenDisplay)
        }
    }

    @Test
    fun deviceDefaultIsNotPersistedAndAnExplicitChoiceSurvivesDifferentDefaults() = runTest {
        val store = MemoryPreferencesStore()
        val defaults = AppSettings.defaultsForDevice(isLowRamDevice = true)
        val repository = SettingsRepository(store, defaults)
        assertEquals(2, repository.settings.first().downloadThreads)
        assertNull(store.data.first()[SettingsKeys.downloadThreads])
        repository.setPureBlack(true)
        assertEquals(2, repository.settings.first().downloadThreads)
        assertNull(store.data.first()[SettingsKeys.downloadThreads])

        repository.setDownloadThreads(16)
        assertEquals(16, SettingsRepository(store).settings.first().downloadThreads)
        assertEquals(16, SettingsRepository(store, defaults).settings.first().downloadThreads)
    }

    @Test
    fun aiFilterDefaultsOffAndPersistsAcrossRepositoryInstances() = runTest {
        val store = MemoryPreferencesStore()
        val repository = SettingsRepository(store)
        assertFalse(repository.settings.first().hideAiComics)
        repository.setHideAiComics(true)
        assertEquals(true, store.data.first()[SettingsKeys.hideAiComics])
        assertTrue(SettingsRepository(store).settings.first().hideAiComics)
        repository.setHideAiComics(false)
        assertFalse(SettingsRepository(store).settings.first().hideAiComics)
    }

    @Test
    fun readerAdditionsRoundTripAndSurviveNewRepositoryInstances() = runTest {
        val store = MemoryPreferencesStore()
        val repository = SettingsRepository(store)
        repository.setTwoPageCoverAlone(true)
        repository.setReaderPreloadAhead(4)
        repository.setReaderAutoTurnSeconds(12)
        repository.setReaderFilter(ReaderFilter.SEPIA)
        assertEquals(
            AppSettings.DEFAULT.copy(
                twoPageCoverAlone = true,
                readerPreloadAhead = 4,
                readerAutoTurnSeconds = 12,
                readerFilter = ReaderFilter.SEPIA,
            ),
            SettingsRepository(store).settings.first(),
        )
        repository.setTwoPageCoverAlone(false)
        assertFalse(repository.settings.first().twoPageCoverAlone)
    }

    @Test
    fun readerAdditionSettersCoerceToSliderRanges() = runTest {
        val repository = SettingsRepository(MemoryPreferencesStore())
        for (count in 1..5) {
            repository.setReaderPreloadAhead(count)
            assertEquals(count, repository.settings.first().readerPreloadAhead)
        }
        for (seconds in listOf(2, 6, 30)) {
            repository.setReaderAutoTurnSeconds(seconds)
            assertEquals(seconds, repository.settings.first().readerAutoTurnSeconds)
        }
        repository.setReaderPreloadAhead(Int.MIN_VALUE)
        repository.setReaderAutoTurnSeconds(Int.MIN_VALUE)
        assertEquals(1, repository.settings.first().readerPreloadAhead)
        assertEquals(2, repository.settings.first().readerAutoTurnSeconds)
        repository.setReaderPreloadAhead(Int.MAX_VALUE)
        repository.setReaderAutoTurnSeconds(Int.MAX_VALUE)
        assertEquals(5, repository.settings.first().readerPreloadAhead)
        assertEquals(30, repository.settings.first().readerAutoTurnSeconds)
    }

    @Test
    fun requiredTypedSettingsRoundTrip() = runTest {
        val repository = SettingsRepository(MemoryPreferencesStore())
        repository.setAccountEmail("reader@example.com")
        repository.setAuthToken("saved-token", 123L)
        repository.setApiRouteAddress("203.0.113.1")
        repository.setImageRouting(RoutingMode.ROUTE_2)
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
                apiRouteAddress = "203.0.113.1", imageRoutingMode = RoutingMode.ROUTE_2,
                imageQuality = ImageQuality.HIGH,
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
        repository.setFailedReloadAction(FailedReloadAction.PULL_DOWN)
        repository.setHideViewedComics(true)
        repository.setHiddenCategories(listOf("隐藏分类"))
        repository.setShowAuthorSearch(false)
        repository.setShowSubscriptions(false)
        repository.setShowTagFavourites(false)
        repository.setShowOnlineFavourites(false)
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
        repository.setLastSeenAnnouncementId("announcement-1")
        assertEquals(
            AppSettings.DEFAULT.copy(
                connectTimeoutSeconds = 60, readTimeoutSeconds = 10, timeOffsetHours = 8,
                useApiClientForImages = true, appOrientation = AppOrientation.LANDSCAPE, displayModeId = 2,
                fullScreenDisplay = FullScreenDisplay.HIDE_SYSTEM_BARS,
                failedReloadAction = FailedReloadAction.PULL_DOWN,
                hideViewedComics = true, hiddenCategories = listOf("隐藏分类"), showAuthorSearch = false,
                showSubscriptions = false, showTagFavourites = false, showOnlineFavourites = false,
                copyNameTemplate = "{title}", confirmCopy = false,
                volumeKeyChapterPaging = false, fullScreenOnForward = true, readerPageAnimation = false,
                threeZoneNextOnRight = true, readerScrollMode = ReaderScrollMode.SCREEN_DISTANCE,
                scrollScreenFraction = 0.5f, readerSliderPosition = ReaderSliderPosition.LEFT,
                twoPagePlacement = TwoPagePlacement.EACH_CENTERED, gestureSpeed = 1.5f,
                minZoom = 0.5f, maxZoom = 5f, doubleTapZoom = 3f, lockDragBounds = true,
                eInkReduceAnimations = true, eInkScrollByScreen = true, eInkScrollAmount = 0.4f,
                eInkTriggerDistanceCm = 0.2f, autoDownloadOnFavourite = true, autoDownloadWifiOnly = false,
                showCommentsInDownloads = true, recordDetailViews = false,
                lastSeenAnnouncementId = "announcement-1",
            ),
            repository.settings.first(),
        )
    }

    @Test
    fun legacyLaunchPictureIsRemovedOnReadWithoutChangingOtherSettings() = runTest {
        val store = MemoryPreferencesStore()
        store.updateData {
            mutablePreferencesOf(
                SettingsKeys.legacyStartupImageUri to "content://images/startup",
                SettingsKeys.theme to "BOOK",
            )
        }
        val repository = SettingsRepository(store)
        val expected = AppSettings.DEFAULT.copy(theme = AppTheme.BOOK)
        assertEquals(expected, repository.settings.first())
        assertNull(store.data.first()[SettingsKeys.legacyStartupImageUri])
        assertEquals(setOf(SettingsKeys.theme), store.data.first().asMap().keys)
        assertEquals(expected, SettingsRepository(store).settings.first())
        assertEquals(setOf(SettingsKeys.theme), store.data.first().asMap().keys)
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
    fun builtInHomePinsDefaultToBothAndRoundTripIndependentlyOfCategoryPins() = runTest {
        val store = MemoryPreferencesStore()
        val repository = SettingsRepository(store)
        assertEquals(setOf("random", "games"), repository.settings.first().pinnedHomeChannels)
        assertNull(store.data.first()[SettingsKeys.pinnedHomeChannels])
        repository.setPinnedHomeCategories(listOf("随机", "游戏专区"))
        for (selected in listOf(setOf("random"), setOf("games"), emptySet(), setOf("games", "random"))) {
            repository.setPinnedHomeChannels(selected)
            assertEquals(selected, store.data.first()[SettingsKeys.pinnedHomeChannels])
            val restored = SettingsRepository(store).settings.first()
            assertEquals(selected, restored.pinnedHomeChannels)
            assertEquals(listOf("随机", "游戏专区"), restored.pinnedHomeCategories)
        }
    }

    @Test
    fun invalidBuiltInHomePinIdsAreRejectedWithoutWriting() = runTest {
        val store = MemoryPreferencesStore()
        val repository = SettingsRepository(store)
        repository.setPinnedHomeChannels(setOf("random"))
        val before = store.data.first().asMap()
        for (invalid in listOf("unknown", "rankings", "Random", "", "随机")) {
            assertFailsWith<IllegalArgumentException> { repository.setPinnedHomeChannels(setOf("games", invalid)) }
            assertEquals(before, store.data.first().asMap())
        }
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
    fun downloadTreeSelectionSurvivesRecreationAndCanReturnToInternalStorage() = runTest {
        val store = MemoryPreferencesStore()
        val repository = SettingsRepository(store)
        val tree = "content://documents/tree/primary%3AComics"
        assertNull(repository.settings.first().downloadTreeUri)
        repository.setDownloadTreeUri(tree)
        assertEquals(tree, SettingsRepository(store).settings.first().downloadTreeUri)
        repository.clearDownloadTreeUriIfMatches("content://documents/tree/old")
        assertEquals(tree, repository.settings.first().downloadTreeUri)
        repository.clearDownloadTreeUriIfMatches(tree)
        assertNull(repository.settings.first().downloadTreeUri)
        repository.setDownloadTreeUri(tree)
        repository.setDownloadTreeUri(null)
        assertNull(repository.settings.first().downloadTreeUri)
        assertFailsWith<IllegalArgumentException> { repository.setDownloadTreeUri("/storage/emulated/0") }
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
    fun routeSelectionsRejectBlankAddressesAndKeepSystemDnsDistinctFromTheDefault() = runTest {
        val repository = SettingsRepository(MemoryPreferencesStore())
        assertFailsWith<IllegalArgumentException> { repository.setApiRouteAddress(" ") }
        assertEquals(AppSettings.DEFAULT, repository.settings.first())
        repository.setApiRouteAddress(null)
        assertNull(repository.settings.first().apiRouteAddress)
        repository.setApiRouteAddress("104.20.180.50")
        repository.setImageRouting(RoutingMode.ROUTE_3)
        val settings = repository.settings.first()
        assertEquals("104.20.180.50", settings.apiRouteAddress)
        assertEquals(RoutingMode.ROUTE_3, settings.imageRoutingMode)
    }

    @Test
    fun zoomValidationIsAtomic() = runTest {
        val repository = SettingsRepository(MemoryPreferencesStore())
        assertFailsWith<IllegalArgumentException> { repository.setReaderZoom(2f, 2f, 1.5f) }
        assertFailsWith<IllegalArgumentException> { repository.setReaderZoom(Float.NaN, 3f, 2f) }
        assertEquals(AppSettings.DEFAULT, repository.settings.first())
    }

    @Test
    fun syncPersistsAddressesAndTimeWithoutChangingSelection() = runTest {
        val store = MemoryPreferencesStore()
        val repository = SettingsRepository(store)
        repository.setApiRouteAddress("104.19.53.76")
        repository.setSyncedApiAddresses(listOf("104.19.53.76", "104.19.53.76", "172.67.7.24"), 1234L)
        val synced = SettingsRepository(store).settings.first()
        assertEquals(listOf("104.19.53.76", "172.67.7.24"), synced.syncedApiAddresses)
        assertEquals(1234L, synced.apiRoutesSyncedAt)
        assertEquals("104.19.53.76", synced.apiRouteAddress)
        repository.setSyncedApiAddresses(listOf("172.67.7.24"), 5678L)
        val later = SettingsRepository(store).settings.first()
        assertEquals(listOf("172.67.7.24"), later.syncedApiAddresses)
        assertEquals(5678L, later.apiRoutesSyncedAt)
        assertEquals("104.19.53.76", later.apiRouteAddress)
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
