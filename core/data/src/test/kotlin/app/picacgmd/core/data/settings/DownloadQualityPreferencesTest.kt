package app.picacgmd.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class DownloadQualityPreferencesTest {
    @Test
    fun missingDownloadQualityFollowsTheSavedOnlineQuality() {
        val preferences = mutablePreferencesOf(SettingsKeys.imageQuality to ImageQuality.LOW.name)
        val decoded = SettingsPreferencesMapper.decode(preferences)

        assertEquals(DownloadQuality.SAME_AS_IMAGE, AppSettings.DEFAULT.downloadQuality)
        assertEquals(DownloadQuality.SAME_AS_IMAGE, decoded.downloadQuality)
        assertEquals(ImageQuality.LOW, decoded.imageQuality)
    }

    @Test
    fun unknownDownloadQualityUsesTheDefaultWithoutChangingOtherSettings() {
        val preferences = mutablePreferencesOf(
            SettingsKeys.downloadQuality to "unknown_future_quality",
            SettingsKeys.imageQuality to ImageQuality.LOW.name,
            SettingsKeys.downloadWifiOnly to true,
        )
        val decoded = SettingsPreferencesMapper.decode(preferences)

        assertEquals(DownloadQuality.SAME_AS_IMAGE, decoded.downloadQuality)
        assertEquals(ImageQuality.LOW, decoded.imageQuality)
        assertEquals(true, decoded.downloadWifiOnly)
    }

    @Test
    fun bothDownloadChoicesRoundTripIndependentlyOfOnlineQuality() = runTest {
        val store = DownloadQualityPreferencesStore()
        val repository = SettingsRepository(store)
        repository.setImageQuality(ImageQuality.LOW)

        for (choice in DownloadQuality.entries) {
            repository.setDownloadQuality(choice)

            assertEquals(choice.name, store.data.value[SettingsKeys.downloadQuality])
            val restored = SettingsRepository(store).settings.first()
            assertEquals(choice, restored.downloadQuality)
            assertEquals(ImageQuality.LOW, restored.imageQuality)
        }
    }
}

private class DownloadQualityPreferencesStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
}
