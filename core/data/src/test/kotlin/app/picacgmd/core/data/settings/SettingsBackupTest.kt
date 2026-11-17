package app.picacgmd.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import app.picacgmd.core.data.settings.SettingsKeys as Keys

class SettingsBackupTest {
    @Test
    fun everyDefinedKeyHasAnExplicitBackupAndRestoreFixture() {
        val definedNames = Keys.javaClass.declaredFields.filter { it.type == Preferences.Key::class.java }.map {
            it.isAccessible = true
            (it.get(null) as Preferences.Key<*>).name
        }.toSet()
        val fixtureNames = fixture().asMap().keys.map { it.name }.toSet()
        assertEquals(
            definedNames, fixtureNames,
            "Added keys: ${definedNames - fixtureNames}; removed keys: ${fixtureNames - definedNames}. " +
                "Classify each key in SettingsRepository.portableBackupKeys, deviceBoundRestoreKeys or authenticationKeys; " +
                "update this fixture and expected backup/restore policy. Remove deleted keys from both. " +
                "Unknown keys are excluded from backup and removed on restore until audited.",
        )
    }

    @Test
    fun backupCarriesAuthenticationOnlyWhenRequestedAndNeverUnknownKeys() = runTest {
        for (includeAuthentication in listOf(false, true)) {
            val unknown = stringPreferencesKey("unreviewed_secret")
            val before = fixture().apply { this[unknown] = "must-not-travel" }
            val store = BackupPreferencesStore(before)
            SettingsRepository(store).filterForSystemBackup(includeAuthentication)
            val excluded = if (includeAuthentication) emptySet() else authenticationNames
            val expected = before.asMap().filterKeys { it.name !in excluded && it != unknown }
            assertEquals(expected, store.data.value.asMap(), policyUpdateMessage)
            assertTrue(store.data.value[Keys.appLockEnabled] == true, "Restore must know that the lock was enabled")
            assertEquals(if (includeAuthentication) "secret-token" else null, store.data.value[Keys.authToken])
        }
    }

    @Test
    fun restoreAtomicallyResetsDeviceBoundKeysKeepsPortableKeysAndIsIdempotent() = runTest {
        val unknown = stringPreferencesKey("unreviewed_setting")
        val before = fixture().apply { this[unknown] = "must-not-be-restored" }
        val store = BackupPreferencesStore(before)
        val repository = SettingsRepository(store)
        repository.resetAfterSystemRestore()
        val expected = before.asMap().filterKeys { it.name !in resetNames && it.name !in authenticationNames && it != unknown }
        assertEquals(expected, store.data.value.asMap(), policyUpdateMessage)
        val restored = repository.settings.first()
        assertEquals("", restored.accountEmail)
        assertNull(restored.authToken)
        assertNull(restored.authTokenUpdatedAt)
        assertEquals("203.0.113.1", restored.apiRouteAddress)
        assertEquals(listOf("203.0.113.1"), restored.syncedApiAddresses)
        assertEquals(456L, restored.apiRoutesSyncedAt)
        assertEquals(RoutingMode.ROUTE_2, restored.imageRoutingMode)
        assertEquals(8, restored.timeOffsetHours)
        assertNull(restored.displayModeId)
        assertNull(restored.startupImageUri)
        assertNull(restored.downloadTreeUri)
        assertFalse(restored.appLockEnabled)
        assertTrue(restored.biometricUnlockEnabled)
        assertEquals(0, restored.appLockTimeoutSeconds)
        assertTrue(restored.eInkReduceAnimations)
        assertTrue(restored.eInkScrollByScreen)
        assertEquals(0.4f, restored.eInkScrollAmount)
        assertEquals(0.2f, restored.eInkTriggerDistanceCm)
        assertEquals(16, restored.downloadThreads)
        assertTrue(restored.preventScreenshots)
        assertEquals(AppOrientation.LANDSCAPE, restored.appOrientation)
        assertEquals(listOf("分类二", "分类一"), restored.pinnedHomeCategories)
        assertEquals("https://backup.example.com/dav/", restored.webDav.address)
        assertEquals("sync-reader", restored.webDav.username)
        assertEquals("Library", restored.webDav.folder)
        assertTrue(restored.webDav.autoSync)
        assertFalse(restored.webDav.wifiOnly)
        assertNull(restored.webDav.lastSyncedAt)
        repository.resetAfterSystemRestore()
        assertEquals(expected, store.data.value.asMap())
    }

    @Test
    fun protectedRestoreKeepsAuthenticationWhileUnknownAndDeviceBoundKeysAreRemoved() = runTest {
        val unknown = stringPreferencesKey("unreviewed_secret")
        val before = fixture().apply { this[unknown] = "must-not-be-restored" }
        val store = BackupPreferencesStore(before)
        val repository = SettingsRepository(store)
        repository.resetAfterSystemRestore(keepAuthentication = true)
        val expected = before.asMap().filterKeys { it.name !in resetNames && it != unknown }
        assertEquals(expected, store.data.value.asMap(), policyUpdateMessage)
        repository.resetAfterSystemRestore(keepAuthentication = true)
        assertEquals(expected, store.data.value.asMap())
        assertEquals("reader@example.com", repository.settings.first().accountEmail)
        assertEquals("secret-token", repository.settings.first().authToken)
        assertEquals(123L, repository.settings.first().authTokenUpdatedAt)
    }

    @Test
    fun authenticationImportIsAtomicAndPreservesAnAbsentTokenTimestamp() = runTest {
        val store = BackupPreferencesStore(fixture())
        val repository = SettingsRepository(store)
        repository.restoreSystemBackupAuthentication("restored@example.com", "restored-token", null)
        assertEquals("restored@example.com", store.data.value[Keys.accountEmail])
        assertEquals("restored-token", store.data.value[Keys.authToken])
        assertNull(store.data.value[Keys.authTokenUpdatedAt])
        repository.restoreSystemBackupAuthentication("", null, 123L)
        assertNull(store.data.value[Keys.accountEmail])
        assertNull(store.data.value[Keys.authToken])
        assertNull(store.data.value[Keys.authTokenUpdatedAt])
    }

    @Test
    fun removedDoubleBackSettingIsDroppedWhenRestoringAnOldBackup() = runTest {
        val removed = booleanPreferencesKey("double_back_to_exit")
        for (keepAuthentication in listOf(false, true)) {
            val store = BackupPreferencesStore(mutablePreferencesOf(removed to true, Keys.theme to "BOOK"))
            val repository = SettingsRepository(store)
            repository.resetAfterSystemRestore(keepAuthentication)
            assertFalse(removed in store.data.value.asMap())
            assertEquals(AppTheme.BOOK, repository.settings.first().theme)
        }
    }

    private val policyUpdateMessage = "Update SettingsRepository's explicit key lists and this test's expected policy; " +
        "unknown keys must remain excluded from backup and removed on restore."
    private val authenticationNames = setOf("account_email", "auth_token", "auth_token_updated_at")
    private val resetNames = setOf(
        "custom_api_ip", "display_mode_id", "startup_image_uri", "download_tree_uri",
        "app_lock_enabled", "biometric_unlock_enabled", "app_lock_timeout_seconds", "webdav_last_synced_at",
    )

    private fun fixture() = mutablePreferencesOf(
        Keys.accountEmail to "reader@example.com", Keys.authToken to "secret-token", Keys.authTokenUpdatedAt to 123L,
        Keys.apiRoutingMode to "ROUTE_3", Keys.apiRouteAddress to "203.0.113.1",
        Keys.syncedApiAddresses to "[\"203.0.113.1\"]", Keys.apiRoutesSyncedAt to 456L,
        Keys.legacyCustomApiIp to "203.0.113.2", Keys.imageRoutingMode to "ROUTE_2", Keys.imageQuality to "HIGH",
        Keys.connectTimeoutSeconds to 60, Keys.readTimeoutSeconds to 10, Keys.timeOffsetHours to 8,
        Keys.useApiClientForImages to true, Keys.theme to "BOOK", Keys.darkMode to "DARK", Keys.pureBlack to true,
        Keys.appOrientation to "LANDSCAPE", Keys.displayModeId to 42, Keys.fullScreenDisplay to "HIDE_SYSTEM_BARS",
        Keys.startupImageUri to "content://images/startup",
        Keys.lastSeenAnnouncementId to "announcement-1", Keys.listLayout to "COVER_AND_TITLE", Keys.listColumnCount to 7,
        Keys.categoryColumnCount to 2, Keys.paginationMode to "BUTTONS", Keys.failedReloadAction to "PULL_DOWN",
        Keys.shieldedCategories to "[\"分类一\"]", Keys.categoryShieldMode to "BLACKLIST",
        Keys.hiddenKeywords to "[\"关键词\"]", Keys.hideViewedComics to true, Keys.hideAiComics to true,
        Keys.hiddenCategories to "[\"分类三\"]", Keys.categoryOrder to "[\"分类二\",\"分类一\"]",
        Keys.pinnedHomeCategories to "[\"分类二\",\"分类一\"]", Keys.showAuthorSearch to false,
        Keys.showSubscriptions to false, Keys.showTagFavourites to false, Keys.showOnlineFavourites to false,
        Keys.copyNameTemplate to "{title}", Keys.confirmCopy to false, Keys.readerMode to "TWO_PAGE_GALLERY",
        Keys.readerDirection to "RIGHT_TO_LEFT", Keys.readerTapZones to "THREE_ZONES", Keys.volumeKeyPaging to true,
        Keys.volumeKeyChapterPaging to false, Keys.readerBackground to "PAPER", Keys.readerFilter to "SEPIA",
        Keys.keepScreenOn to false, Keys.readerFullScreen to true, Keys.fullScreenOnForward to true,
        Keys.readerPageAnimation to false, Keys.threeZoneNextOnRight to false, Keys.readerScrollMode to "SCREEN_DISTANCE",
        Keys.scrollScreenFraction to 0.5f, Keys.readerSliderPosition to "LEFT", Keys.twoPagePlacement to "EACH_CENTERED",
        Keys.twoPageCoverAlone to true, Keys.readerPreloadAhead to 5, Keys.readerAutoTurnSeconds to 12,
        Keys.readerAutoScrollSpeed to 3, Keys.readerAutoScrollPauseOnTouch to true, Keys.readerAutoNextChapter to true,
        Keys.gestureSpeed to 1.5f, Keys.minZoom to 0.5f, Keys.maxZoom to 5f, Keys.doubleTapZoom to 3f,
        Keys.lockDragBounds to true, Keys.eInkReduceAnimations to true, Keys.eInkScrollByScreen to true,
        Keys.eInkScrollAmount to 0.4f, Keys.eInkTriggerDistanceCm to 0.2f, Keys.downloadThreads to 16,
        Keys.downloadWifiOnly to true, Keys.downloadTreeUri to "content://documents/tree/comics",
        Keys.autoDownloadOnFavourite to true, Keys.autoDownloadWifiOnly to false,
        Keys.autoDeleteDownloadOnUnfavourite to true, Keys.showCommentsInDownloads to true, Keys.autoPunchIn to false,
        Keys.appLockEnabled to true, Keys.biometricUnlockEnabled to false, Keys.appLockTimeoutSeconds to 60,
        Keys.preventScreenshots to true, Keys.historyEnabled to false, Keys.recordDetailViews to false,
        Keys.autoCleanCacheInterval to "WEEK",
        Keys.webDavAddress to "https://backup.example.com/dav/", Keys.webDavUsername to "sync-reader",
        Keys.webDavFolder to "Library", Keys.webDavAutoSync to true, Keys.webDavWifiOnly to false,
        Keys.webDavLastSyncedAt to 789L,
    )
}

private class BackupPreferencesStore(initial: Preferences) : DataStore<Preferences> {
    override val data = MutableStateFlow(initial)
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
}
