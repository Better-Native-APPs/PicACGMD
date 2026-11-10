package app.picacgmd.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsPreferencesMapperTest {
    @Test
    fun deviceDefaultsChangeOnlyTheDownloadThreadCount() {
        assertEquals(AppSettings.DEFAULT, AppSettings.defaultsForDevice(isLowRamDevice = false))
        assertEquals(AppSettings.DEFAULT.copy(downloadThreads = 2), AppSettings.defaultsForDevice(isLowRamDevice = true))
    }

    @Test
    fun missingDownloadThreadPreferenceUsesDeviceDefaults() {
        val defaults = AppSettings.defaultsForDevice(isLowRamDevice = true)
        assertEquals(defaults, SettingsPreferencesMapper.decode(emptyPreferences(), defaults))
    }

    @Test
    fun everySavedThreadCountOverridesTheLowRamDefault() {
        val defaults = AppSettings.defaultsForDevice(isLowRamDevice = true)
        for (threads in 1..16) {
            val preferences = mutablePreferencesOf(SettingsKeys.downloadThreads to threads)
            assertEquals(threads, SettingsPreferencesMapper.decode(preferences, defaults).downloadThreads)
        }
    }

    @Test
    fun unsupportedThreadCountsUseTheDeviceDefaultWithoutDroppingOtherPreferences() {
        val defaults = AppSettings.defaultsForDevice(isLowRamDevice = true)
        for (threads in listOf(0, 17)) {
            val preferences = mutablePreferencesOf(SettingsKeys.downloadThreads to threads, SettingsKeys.pureBlack to true)
            assertEquals(defaults.copy(pureBlack = true), SettingsPreferencesMapper.decode(preferences, defaults))
        }
    }

    @Test
    fun everyLegacyApiModeKeepsItsEffectiveAddress() {
        val addresses = listOf(null, "172.67.7.24", "104.20.180.50", "172.67.208.169")
        assertEquals(addresses.size, RoutingMode.entries.size)
        RoutingMode.entries.zip(addresses).forEach { (mode, expected) ->
            val preferences = mutablePreferencesOf(SettingsKeys.apiRoutingMode to mode.name)
            assertEquals(expected, SettingsPreferencesMapper.decode(preferences).apiRouteAddress, mode.name)
        }
    }

    @Test
    fun removedCustomRoutesFallBackToTheDefaultRoutes() {
        val custom = "203.0.113.7"
        val legacyMode = mutablePreferencesOf(
            SettingsKeys.apiRoutingMode to "CUSTOM",
            SettingsKeys.legacyCustomApiIp to custom,
            SettingsKeys.imageRoutingMode to "CUSTOM",
            stringPreferencesKey("custom_image_host") to "images.example.com",
        )
        val decoded = SettingsPreferencesMapper.decode(legacyMode)
        assertEquals("172.67.7.24", decoded.apiRouteAddress)
        assertEquals(RoutingMode.NONE, decoded.imageRoutingMode)

        val selected = mutablePreferencesOf(
            SettingsKeys.apiRouteAddress to custom,
            SettingsKeys.legacyCustomApiIp to custom,
        )
        assertEquals("172.67.7.24", SettingsPreferencesMapper.decode(selected).apiRouteAddress)
        // Another route chosen after the custom one was saved is not touched.
        selected[SettingsKeys.apiRouteAddress] = "104.19.53.76"
        assertEquals("104.19.53.76", SettingsPreferencesMapper.decode(selected).apiRouteAddress)
        selected[SettingsKeys.apiRouteAddress] = ""
        assertNull(SettingsPreferencesMapper.decode(selected).apiRouteAddress)
    }

    @Test
    fun formerCustomAddressStaysSelectedWhileTheRouteListStillOffersIt() {
        val builtIn = mutablePreferencesOf(
            SettingsKeys.apiRouteAddress to "104.20.180.50",
            SettingsKeys.legacyCustomApiIp to "104.20.180.50",
        )
        assertEquals("104.20.180.50", SettingsPreferencesMapper.decode(builtIn).apiRouteAddress)
        val synced = mutablePreferencesOf(
            SettingsKeys.apiRouteAddress to "104.19.53.76",
            SettingsKeys.legacyCustomApiIp to "104.19.53.76",
            SettingsKeys.syncedApiAddresses to "[\"104.19.53.76\"]",
        )
        assertEquals("104.19.53.76", SettingsPreferencesMapper.decode(synced).apiRouteAddress)
    }

    @Test
    fun addressSelectionOverridesLegacyModeIncludingExplicitSystemDns() {
        val preferences = mutablePreferencesOf(
            SettingsKeys.apiRoutingMode to RoutingMode.ROUTE_3.name,
            SettingsKeys.apiRouteAddress to "104.19.53.76",
        )
        assertEquals("104.19.53.76", SettingsPreferencesMapper.decode(preferences).apiRouteAddress)
        preferences[SettingsKeys.apiRouteAddress] = ""
        assertNull(SettingsPreferencesMapper.decode(preferences).apiRouteAddress)
        assertEquals("172.67.7.24", SettingsPreferencesMapper.decode(emptyPreferences()).apiRouteAddress)
    }

    @Test
    fun obsoleteFolderPreferencesAreIgnored() {
        val preferences = mutablePreferencesOf(
            booleanPreferencesKey("show_local_favourites") to false,
            longPreferencesKey("last_favourite_folder_id") to 4L,
            SettingsKeys.downloadThreads to 8,
        )
        assertEquals(AppSettings.DEFAULT.copy(downloadThreads = 8), SettingsPreferencesMapper.decode(preferences))
    }

    @Test
    fun missingReaderAdditionsMatchReaderDefaults() {
        val settings = SettingsPreferencesMapper.decode(emptyPreferences())
        assertFalse(settings.twoPageCoverAlone)
        assertEquals(2, settings.readerPreloadAhead)
        assertEquals(6, settings.readerAutoTurnSeconds)
        assertEquals(3, settings.readerAutoScrollSpeed)
        assertTrue(settings.readerAutoScrollPauseOnTouch)
        assertFalse(settings.readerAutoNextChapter)
    }

    @Test
    fun readerAdditionsDecodeWithoutChangingExistingPreferences() {
        val preferences = mutablePreferencesOf(
            SettingsKeys.twoPageCoverAlone to true,
            SettingsKeys.readerPreloadAhead to 5,
            SettingsKeys.readerAutoTurnSeconds to 30,
            SettingsKeys.readerAutoScrollSpeed to 5,
            SettingsKeys.readerAutoScrollPauseOnTouch to false,
            SettingsKeys.readerAutoNextChapter to true,
            SettingsKeys.readerBackground to ReaderBackground.PAPER.name,
        )
        assertEquals(
            AppSettings.DEFAULT.copy(
                twoPageCoverAlone = true,
                readerPreloadAhead = 5,
                readerAutoTurnSeconds = 30,
                readerAutoScrollSpeed = 5,
                readerAutoScrollPauseOnTouch = false,
                readerAutoNextChapter = true,
                readerBackground = ReaderBackground.PAPER,
            ),
            SettingsPreferencesMapper.decode(preferences),
        )
    }

    @Test
    fun readerAdditionsRejectUnsupportedStoredNumbers() {
        for ((preload, interval, speed) in listOf(Triple(0, 1, 0), Triple(6, 31, 6))) {
            val preferences = mutablePreferencesOf(
                SettingsKeys.readerPreloadAhead to preload,
                SettingsKeys.readerAutoTurnSeconds to interval,
                SettingsKeys.readerAutoScrollSpeed to speed,
                SettingsKeys.twoPageCoverAlone to true,
            )
            assertEquals(
                AppSettings.DEFAULT.copy(twoPageCoverAlone = true),
                SettingsPreferencesMapper.decode(preferences),
            )
        }
    }

    @Test
    fun emptyPreferencesMatchEveryDocumentedDefault() {
        assertEquals(AppSettings.DEFAULT, SettingsPreferencesMapper.decode(emptyPreferences()))
        val defaults = AppSettings.DEFAULT
        assertEquals(listOf("大家都在看", "大濕推薦"), defaults.pinnedHomeCategories)
        assertEquals(4, defaults.downloadThreads)
        assertEquals(PaginationMode.INFINITE, defaults.paginationMode)
        assertTrue(defaults.autoPunchIn)
        assertTrue(defaults.historyEnabled)
        assertFalse(defaults.appLockEnabled)
        assertNull(defaults.authToken)
    }

    @Test
    fun unsupportedStoredValuesUseDefaultsWithoutDroppingOtherSettings() {
        val preferences = mutablePreferencesOf(
            SettingsKeys.theme to "unknown_future_theme",
            SettingsKeys.downloadThreads to 17,
            SettingsKeys.listColumnCount to 1,
            SettingsKeys.connectTimeoutSeconds to 0,
            SettingsKeys.appLockTimeoutSeconds to -1,
            SettingsKeys.pinnedHomeCategories to "{not json}",
            SettingsKeys.hiddenKeywords to "{}",
            SettingsKeys.minZoom to 2f,
            SettingsKeys.maxZoom to 2f,
            SettingsKeys.doubleTapZoom to 1.5f,
            SettingsKeys.readerBackground to ReaderBackground.PAPER.name,
            SettingsKeys.pureBlack to true,
        )
        assertEquals(
            AppSettings.DEFAULT.copy(readerBackground = ReaderBackground.PAPER, pureBlack = true),
            SettingsPreferencesMapper.decode(preferences),
        )
    }

    @Test
    fun emptyPinsAreDistinctFromAnUnsetPreference() {
        val preferences = mutablePreferencesOf(SettingsKeys.pinnedHomeCategories to "[]")
        assertEquals(emptyList(), SettingsPreferencesMapper.decode(preferences).pinnedHomeCategories)
    }

    @Test
    fun blankTokenDoesNotExposeAStaleTokenTimestamp() {
        val preferences = mutablePreferencesOf(
            SettingsKeys.authToken to "",
            SettingsKeys.authTokenUpdatedAt to 123L,
        )
        val settings = SettingsPreferencesMapper.decode(preferences)
        assertNull(settings.authToken)
        assertNull(settings.authTokenUpdatedAt)
    }
}
