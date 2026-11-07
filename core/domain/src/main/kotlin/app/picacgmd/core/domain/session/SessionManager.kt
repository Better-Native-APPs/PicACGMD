package app.picacgmd.core.domain.session

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.network.PicaApi
import app.picacgmd.core.network.PicaResult
import app.picacgmd.core.network.PunchStatus
import app.picacgmd.core.network.UnauthorizedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small persistence boundary so session behavior can be tested without Android or a real DataStore. */
interface SessionPreferences {
    val settings: Flow<AppSettings>
    suspend fun setAccountEmail(value: String)
    suspend fun setAuthToken(token: String?, updatedAt: Long)
}

class SettingsSessionPreferences(private val repository: SettingsRepository) : SessionPreferences {
    override val settings: Flow<AppSettings> = repository.settings
    override suspend fun setAccountEmail(value: String) = repository.setAccountEmail(value)
    override suspend fun setAuthToken(token: String?, updatedAt: Long) = repository.setAuthToken(token, updatedAt)
}

sealed interface SessionState {
    data object Initializing : SessionState
    data object SignedOut : SessionState
    data object SigningIn : SessionState
    data class SignedIn(val email: String) : SessionState
    data class Expired(val message: String) : SessionState
}

sealed interface PunchInState {
    data object Idle : PunchInState
    data object Punching : PunchInState
    data class Done(val status: PunchStatus) : PunchInState
    data class Failed(val message: String) : PunchInState
}

class SessionManager(
    private val preferences: SessionPreferences,
    private val credentialStore: CredentialStore,
    private val apiProvider: suspend () -> PicaApi,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        settingsRepository: SettingsRepository,
        credentialStore: CredentialStore,
        apiProvider: suspend () -> PicaApi,
        scope: CoroutineScope,
    ) : this(SettingsSessionPreferences(settingsRepository), credentialStore, apiProvider, scope)

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Initializing)
    private val mutablePunchInState = MutableStateFlow<PunchInState>(PunchInState.Idle)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    val punchInState: StateFlow<PunchInState> = mutablePunchInState.asStateFlow()

    @Volatile private var currentToken: String? = null
    @Volatile private var sessionVersion: Long = 0
    val token: String? get() = currentToken

    private val initialization = scope.async {
        val settings = preferences.settings.first()
        currentToken = settings.authToken
        mutableState.value = if (currentToken == null) SessionState.SignedOut else SessionState.SignedIn(settings.accountEmail)
    }

    suspend fun awaitReady() {
        initialization.await()
    }

    suspend fun login(email: String, password: String, rememberCredentials: Boolean = true) {
        require(email.isNotBlank() && password.isNotEmpty()) { "登录账号和密码不能为空" }
        awaitReady()
        mutex.withLock { signIn(Credentials(email.trim(), password), rememberCredentials) }
        autoPunchIn()
    }

    suspend fun logout() {
        awaitReady()
        mutex.withLock {
            currentToken = null
            sessionVersion++
            mutableState.value = SessionState.SignedOut
            mutablePunchInState.value = PunchInState.Idle
            try {
                preferences.setAuthToken(null, now())
            } finally {
                credentialStore.clear()
            }
        }
    }

    /** Invoke once when opening the app; a saved token also gets the enabled automatic punch-in. */
    suspend fun onAppOpen() {
        awaitReady()
        val signedIn = mutex.withLock {
            if (currentToken == null) {
                credentialStore.read()?.let { signIn(it, rememberCredentials = null) }
            }
            currentToken != null
        }
        if (signedIn) autoPunchIn()
    }

    /** Retries a request exactly once after 401; concurrent failures share one re-login. */
    suspend fun <T> withApi(block: suspend (PicaApi) -> PicaResult<T>): T = request(block, punchAfterRelogin = true)

    suspend fun punchIn(): PunchStatus {
        mutablePunchInState.value = PunchInState.Punching
        try {
            val result = request({ it.punchIn() }, punchAfterRelogin = false)
            mutablePunchInState.value = PunchInState.Done(result)
            return result
        } catch (exception: CancellationException) {
            mutablePunchInState.value = PunchInState.Idle
            throw exception
        } catch (exception: Exception) {
            mutablePunchInState.value = PunchInState.Failed(exception.message ?: "打卡失败")
            throw exception
        }
    }

    /** Called only after the server has accepted a password change; do not opt into remembering it. */
    suspend fun updateStoredPassword(newPassword: String) {
        awaitReady()
        mutex.withLock {
            val saved = credentialStore.read() ?: return@withLock
            val email = (mutableState.value as? SessionState.SignedIn)?.email ?: return@withLock
            if (saved.email == email) credentialStore.save(saved.copy(password = newPassword))
        }
    }

    private suspend fun <T> request(block: suspend (PicaApi) -> PicaResult<T>, punchAfterRelogin: Boolean): T {
        awaitReady()
        val attemptedToken = currentToken
        val attemptedVersion = sessionVersion
        try {
            return block(apiProvider()).getOrThrow()
        } catch (unauthorized: UnauthorizedException) {
            val didRelogin = mutex.withLock {
                // A logout while this request was in flight must not silently sign the user in again.
                if (sessionVersion != attemptedVersion && currentToken == null) throw unauthorized
                if (currentToken != attemptedToken) return@withLock false
                val saved = try {
                    credentialStore.read()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    expire(unauthorized)
                    throw exception
                }
                if (saved == null) {
                    expire(unauthorized)
                    throw unauthorized
                }
                try {
                    signIn(saved, rememberCredentials = null)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    expire(unauthorized)
                    throw exception
                }
                true
            }
            val retryToken = currentToken
            val result = try {
                block(apiProvider()).getOrThrow()
            } catch (secondUnauthorized: UnauthorizedException) {
                mutex.withLock {
                    if (currentToken == retryToken) expire(secondUnauthorized)
                }
                throw secondUnauthorized
            }
            if (didRelogin && punchAfterRelogin) autoPunchIn()
            return result
        }
    }

    /** null keeps existing encrypted credentials during automatic re-login. Caller holds mutex. */
    private suspend fun signIn(credentials: Credentials, rememberCredentials: Boolean?) {
        val previous = mutableState.value
        mutableState.value = SessionState.SigningIn
        try {
            val newToken = apiProvider().login(credentials.email, credentials.password).getOrThrow()
            when (rememberCredentials) {
                true -> credentialStore.save(credentials)
                false -> credentialStore.clear()
                null -> Unit
            }
            preferences.setAccountEmail(credentials.email)
            preferences.setAuthToken(newToken, now())
            currentToken = newToken
            sessionVersion++
            mutableState.value = SessionState.SignedIn(credentials.email)
        } catch (exception: Exception) {
            mutableState.value = previous
            throw exception
        }
    }

    private suspend fun expire(exception: UnauthorizedException) {
        currentToken = null
        sessionVersion++
        mutableState.value = SessionState.Expired(exception.message ?: "登录已失效，请重新登录")
        preferences.setAuthToken(null, now())
    }

    private suspend fun autoPunchIn() {
        try {
            if (preferences.settings.first().autoPunchIn && currentToken != null) punchIn()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // Authentication succeeds even when this optional action fails; the failure is observable.
            mutablePunchInState.value = PunchInState.Failed(exception.message ?: "打卡失败")
        }
    }
}
