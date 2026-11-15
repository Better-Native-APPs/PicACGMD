package app.picacgmd.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.picacgmd.core.domain.sync.HistorySyncActions
import app.picacgmd.core.domain.sync.HistorySyncActivity
import app.picacgmd.core.domain.sync.HistorySyncCounts
import app.picacgmd.core.domain.sync.HistorySyncDirection
import app.picacgmd.core.domain.sync.HistorySyncOutcome
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebDavAccountDraft(val address: String = "", val username: String = "", val folder: String = "PicACGMD") {
    override fun toString(): String = "WebDavAccountDraft()"
}

data class WebDavSyncResult(
    val successful: Boolean,
    val message: String,
    val counts: HistorySyncCounts,
    val localChanged: Boolean = false,
    val remoteWritten: Boolean = false,
)

data class WebDavSyncUiState(
    val account: WebDavAccountDraft = WebDavAccountDraft(),
    /** The stored account, untouched by edits: what test and sync use, and what [WebDavSyncViewModel.discardEdits] returns to. */
    val savedAccount: WebDavAccountDraft = WebDavAccountDraft(),
    val hasPassword: Boolean = false,
    val ready: Boolean = false,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    /** Goes up by one with every completed save, so a panel can tell a save from any other state change. */
    val accountSaves: Int = 0,
    val autoSync: Boolean = false,
    val wifiOnly: Boolean = true,
    val progress: HistorySyncActivity = HistorySyncActivity.Idle,
    /** Loaded from settings on restart; result counts and error text are session-only. */
    val lastSyncedAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastResult: WebDavSyncResult? = null,
    val configurationError: String? = null,
    val error: String? = null,
    val message: String? = null,
) {
    val busy: Boolean get() = saving || progress != HistorySyncActivity.Idle
}

/** The panel owns transient password input; this holder only exposes whether an encrypted password exists. */
class WebDavSyncViewModel(
    private val actions: HistorySyncActions,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scope ?: viewModelScope
    private val mutableState = MutableStateFlow(WebDavSyncUiState())
    val state: StateFlow<WebDavSyncUiState> = mutableState.asStateFlow()
    private var operation: Job? = null

    init {
        this.scope.launch {
            actions.state.collect { status ->
                mutableState.update {
                    val saved = WebDavAccountDraft(status.config.address, status.config.username, status.config.folder)
                    it.copy(
                        account = if (it.dirty) it.account else saved, savedAccount = saved,
                        hasPassword = status.hasPassword, ready = status.ready,
                        autoSync = status.config.autoSync, wifiOnly = status.config.wifiOnly,
                        progress = status.activity, lastSyncedAt = status.lastSuccessAt, lastAttemptAt = status.lastAttemptAt,
                        lastResult = status.lastResult?.toUiResult(), configurationError = status.configurationError?.message(),
                    )
                }
            }
        }
    }

    fun editAddress(value: String) = edit { copy(address = value) }
    fun editUsername(value: String) = edit { copy(username = value) }
    fun editFolder(value: String) = edit { copy(folder = value) }

    /** Drops unsaved edits and shows the stored account again; a save that is under way keeps its draft. */
    fun discardEdits() {
        mutableState.update { if (it.saving) it else it.copy(account = it.savedAccount, dirty = false, error = null, message = null) }
    }

    /** null/empty retains the saved password only when the address and username still identify that account. */
    fun saveAccount(password: String? = null) {
        val draft = state.value.account
        runOperation(saving = true) {
            when (val result = actions.saveAccount(draft.address, draft.username, draft.folder, password)) {
                is WebDavResult.Success -> mutableState.update {
                    val config = actions.state.value.config
                    it.copy(dirty = it.account != draft,
                        account = if (it.account == draft) WebDavAccountDraft(config.address, config.username, config.folder) else it.account,
                        accountSaves = it.accountSaves + 1, message = "已保存 WebDAV 账号")
                }
                is WebDavResult.Failure -> fail(result)
            }
        }
    }

    fun setAutoSync(enabled: Boolean) = runOperation { checkResult(actions.setAutoSync(enabled)) }
    fun setWifiOnly(enabled: Boolean) = runOperation { checkResult(actions.setWifiOnly(enabled)) }

    fun testConnection() = runOperation {
        when (val result = actions.testConnection()) {
            is WebDavResult.Success -> mutableState.update {
                it.copy(message = if (result.value.folderCreated) "连接成功，已创建同步文件夹" else "连接成功")
            }
            is WebDavResult.Failure -> fail(result)
        }
    }

    fun syncNow() = synchronize(HistorySyncDirection.MERGE)
    fun overwriteRemote() = synchronize(HistorySyncDirection.LOCAL_TO_REMOTE)
    fun overwriteLocal() = synchronize(HistorySyncDirection.REMOTE_TO_LOCAL)
    fun clearError() { mutableState.update { it.copy(error = null) } }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    private fun synchronize(direction: HistorySyncDirection) = runOperation {
        when (val outcome = actions.synchronize(direction, automatic = false)) {
            is HistorySyncOutcome.Failure -> mutableState.update { it.copy(error = outcome.toUiResult()?.message) }
            is HistorySyncOutcome.Success -> mutableState.update { it.copy(message = "同步完成") }
            HistorySyncOutcome.Skipped -> Unit
        }
    }

    private fun edit(transform: WebDavAccountDraft.() -> WebDavAccountDraft) {
        mutableState.update { it.copy(account = it.account.transform(), dirty = true, error = null, message = null) }
    }

    private fun runOperation(saving: Boolean = false, block: suspend () -> Unit) {
        if (!state.value.ready || state.value.busy || operation?.isActive == true) return
        mutableState.update { it.copy(saving = saving, error = null, message = null) }
        operation = scope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableState.update { it.copy(error = WebDavError.STORAGE.message()) }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    private fun checkResult(result: WebDavResult<Unit>) { if (result is WebDavResult.Failure) fail(result) }
    private fun fail(result: WebDavResult.Failure) { mutableState.update { it.copy(error = result.error.message(result.httpStatus)) } }
}

private fun HistorySyncOutcome.toUiResult(): WebDavSyncResult? = when (this) {
    is HistorySyncOutcome.Success -> WebDavSyncResult(true, "同步完成", counts)
    is HistorySyncOutcome.Failure -> WebDavSyncResult(false,
        error.message(httpStatus) + when {
            remoteWritten -> "。云端记录已更新，本机的后续处理未完成"
            localChanged -> "。本机记录已更新，云端未更新"
            else -> ""
        }, counts, localChanged, remoteWritten)
    HistorySyncOutcome.Skipped -> null
}

internal fun WebDavError.message(httpStatus: Int? = null): String = when (this) {
    WebDavError.INVALID_ADDRESS -> "请填写有效的 WebDAV 地址"
    WebDavError.HTTPS_REQUIRED -> "仅支持 HTTPS 地址"
    WebDavError.INVALID_FOLDER -> "请填写单个文件夹名称"
    WebDavError.INVALID_USERNAME -> "请填写有效的用户名"
    WebDavError.PASSWORD_REQUIRED -> "请输入密码"
    WebDavError.CONNECTION -> "无法连接服务器"
    WebDavError.TIMEOUT -> "连接超时，请重试"
    WebDavError.TLS -> "安全连接失败，请检查服务器证书"
    WebDavError.AUTHENTICATION -> "账号或密码不正确"
    WebDavError.PERMISSION -> "没有权限"
    WebDavError.PARENT_MISSING -> "服务器目录不存在，请检查地址"
    WebDavError.QUOTA -> "云端空间不足"
    WebDavError.NOT_WEBDAV -> "服务器不支持 WebDAV"
    WebDavError.FILE_TOO_LARGE -> "同步文件超过 20 MB"
    WebDavError.NEWER_FORMAT -> "云端记录格式较新，请更新应用"
    WebDavError.DAMAGED_FILE -> "云端记录已损坏，未覆盖记录"
    WebDavError.INVALID_TIMESTAMP -> "云端记录的时间不正常，请检查各设备的系统时间"
    WebDavError.CHANGED_ELSEWHERE -> "云端记录已在其他设备更改，请重试"
    WebDavError.UNSAFE_REDIRECT -> "服务器跳转地址不安全，请检查地址"
    WebDavError.HTTP_ERROR -> "服务器请求失败" + (httpStatus?.let { "（HTTP $it）" } ?: "")
    WebDavError.STORAGE -> "读写同步数据失败，请重试"
}
