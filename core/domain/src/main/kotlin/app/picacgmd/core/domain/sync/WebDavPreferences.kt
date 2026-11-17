package app.picacgmd.core.domain.sync

import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.data.settings.WebDavSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface WebDavPreferences {
    val settings: Flow<WebDavSettings>
    suspend fun saveAccount(address: String, username: String, folder: String)
    suspend fun setAutoSync(enabled: Boolean)
    suspend fun setWifiOnly(enabled: Boolean)
    suspend fun setLastSyncedAt(timestamp: Long)
}

class SettingsWebDavPreferences(private val repository: SettingsRepository) : WebDavPreferences {
    override val settings: Flow<WebDavSettings> = repository.settings.map { it.webDav }.distinctUntilChanged()
    override suspend fun saveAccount(address: String, username: String, folder: String) =
        repository.setWebDavAccount(address, username, folder)
    override suspend fun setAutoSync(enabled: Boolean) = repository.setWebDavAutoSync(enabled)
    override suspend fun setWifiOnly(enabled: Boolean) = repository.setWebDavWifiOnly(enabled)
    override suspend fun setLastSyncedAt(timestamp: Long) = repository.setWebDavLastSyncedAt(timestamp)
}
