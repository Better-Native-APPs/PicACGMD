package app.picacgmd.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class WebDavSettingsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun automaticSyncDefaultsOffAndWifiOnlyDefaultsOn() = runTest {
        val repository = SettingsRepository(MemoryStore())
        assertFalse(repository.settings.first().webDav.autoSync)
        assertTrue(repository.settings.first().webDav.wifiOnly)
        assertEquals("PicACGMD", repository.settings.first().webDav.folder)
        assertNull(repository.settings.first().webDav.lastSyncedAt)
    }

    @Test fun accountAndSwitchesSurviveRepositoryRecreationWithoutAPasswordKey() = runTest {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
        repository.setWebDavAccount("https://localhost/dav/", "test-reader", "阅读记录")
        assertEquals(1, store.writes)
        repository.setWebDavAutoSync(true)
        repository.setWebDavWifiOnly(false)
        repository.setWebDavLastSyncedAt(123)
        assertEquals(WebDavSettings("https://localhost/dav/", "test-reader", "阅读记录", true, false, lastSyncedAt = 123),
            SettingsRepository(store).settings.first().webDav)
        assertTrue(store.data.value.asMap().keys.none { it.name.contains("password", ignoreCase = true) })
        assertEquals(setOf("webdav_address", "webdav_username", "webdav_folder", "webdav_auto_sync", "webdav_wifi_only", "webdav_last_synced_at"),
            store.data.value.asMap().keys.map { it.name }.toSet())
    }

    @Test fun lastSuccessfulTimeSurvivesClosingAndReopeningTheSettingsFile() = runTest {
        val file = File(temporaryFolder.root, "webdav.preferences_pb")
        val firstJob = Job()
        val firstStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(firstJob + StandardTestDispatcher(testScheduler)), produceFile = { file },
        )
        try {
            val repository = SettingsRepository(firstStore)
            repository.setWebDavAccount("https://localhost/dav/", "test-reader", "阅读记录")
            repository.setWebDavLastSyncedAt(456)
        } finally {
            firstJob.cancelAndJoin()
        }
        val secondJob = Job()
        val secondStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(secondJob + StandardTestDispatcher(testScheduler)), produceFile = { file },
        )
        try {
            assertEquals(WebDavSettings("https://localhost/dav/", "test-reader", "阅读记录", lastSyncedAt = 456),
                SettingsRepository(secondStore).settings.first().webDav)
            assertEquals(setOf("webdav_address", "webdav_username", "webdav_folder", "webdav_last_synced_at"),
                secondStore.data.first().asMap().keys.map { it.name }.toSet())
        } finally {
            secondJob.cancelAndJoin()
        }
    }

    @Test fun storedTimeIsClearedInTheAccountWriteOnlyWhenCollectionOrUsernameChanges() = runTest {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
        repository.setWebDavAccount("https://localhost/dav/", "test-reader", "first")
        repository.setWebDavLastSyncedAt(123)
        repository.setWebDavAccount("https://localhost/dav/", "test-reader", "second")
        repository.setWebDavAutoSync(true)
        repository.setWebDavWifiOnly(false)
        assertEquals(123L, repository.settings.first().webDav.lastSyncedAt)
        var writes = store.writes
        repository.setWebDavAccount("https://localhost/other/", "test-reader", "second")
        assertEquals(writes + 1, store.writes)
        assertNull(SettingsRepository(store).settings.first().webDav.lastSyncedAt)
        repository.setWebDavLastSyncedAt(456)
        writes = store.writes
        repository.setWebDavAccount("https://localhost/other/", "other-reader", "second")
        assertEquals(writes + 1, store.writes)
        assertNull(SettingsRepository(store).settings.first().webDav.lastSyncedAt)
        assertNull(store.data.value[SettingsKeys.webDavLastSyncedAt])
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        var writes = 0
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { writes++; data.value = it }
    }
}
