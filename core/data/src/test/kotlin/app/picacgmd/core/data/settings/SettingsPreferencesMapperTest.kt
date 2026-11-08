package app.picacgmd.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsPreferencesMapperTest {
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
    }

    @Test
    fun readerAdditionsDecodeWithoutChangingExistingPreferences() {
        val preferences = mutablePreferencesOf(
            SettingsKeys.twoPageCoverAlone to true,
            SettingsKeys.readerPreloadAhead to 5,
            SettingsKeys.readerAutoTurnSeconds to 30,
            SettingsKeys.readerBackground to ReaderBackground.PAPER.name,
        )
        assertEquals(
            AppSettings.DEFAULT.copy(
                twoPageCoverAlone = true,
                readerPreloadAhead = 5,
                readerAutoTurnSeconds = 30,
                readerBackground = ReaderBackground.PAPER,
            ),
            SettingsPreferencesMapper.decode(preferences),
        )
    }

    @Test
    fun readerAdditionsRejectUnsupportedStoredNumbers() {
        for ((preload, interval) in listOf(0 to 1, 6 to 31)) {
            val preferences = mutablePreferencesOf(
                SettingsKeys.readerPreloadAhead to preload,
                SettingsKeys.readerAutoTurnSeconds to interval,
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
