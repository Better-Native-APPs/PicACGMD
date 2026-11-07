package app.picacgmd.core.domain.session

import app.picacgmd.core.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow

internal class MemorySessionPreferences(initial: AppSettings) : SessionPreferences {
    override val settings = MutableStateFlow(initial)
    override suspend fun setAccountEmail(value: String) { settings.value = settings.value.copy(accountEmail = value) }
    override suspend fun setAuthToken(token: String?, updatedAt: Long) {
        settings.value = settings.value.copy(authToken = token, authTokenUpdatedAt = updatedAt.takeIf { token != null })
    }
}

internal class MemoryCredentialStore(var credentials: Credentials? = null) : CredentialStore {
    override suspend fun read(): Credentials? = credentials
    override suspend fun save(credentials: Credentials) { this.credentials = credentials }
    override suspend fun clear() { credentials = null }
}
