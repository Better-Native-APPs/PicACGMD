package app.picacgmd.feature.sync

import app.picacgmd.core.domain.sync.HistorySyncActivity
import app.picacgmd.core.domain.sync.HistorySyncCounts
import app.picacgmd.core.domain.sync.HistorySyncDirection
import app.picacgmd.core.domain.sync.HistorySyncOutcome
import app.picacgmd.core.domain.sync.HistorySyncPhase
import app.picacgmd.core.domain.sync.HistorySyncStatus
import app.picacgmd.core.network.webdav.WebDavEndpoint
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavResult
import app.picacgmd.feature.routing.formatSyncTime

/*
 * Everything the sync panel says that can be decided without a composition: the wording of each state,
 * the one-glance text of the settings row and the checks a field runs before anything is sent.
 */

/** The 「WebDAV 云同步」 row of 数据与存储: a state word short enough to sit beside the title, and a line under it. */
internal data class WebDavRowSummary(val value: String, val summary: String)

/** Null until the stored account has been read, so the row never flashes 「未设置」. */
internal fun webDavRowSummary(status: HistorySyncStatus, now: Long = System.currentTimeMillis()): WebDavRowSummary? {
    if (!status.ready) return null
    val value = when {
        !status.hasPassword -> "未设置"
        status.activity is HistorySyncActivity.Syncing -> "同步中"
        status.lastResult is HistorySyncOutcome.Failure -> "同步失败"
        status.config.autoSync -> "自动同步"
        else -> "手动同步"
    }
    val summary = status.lastSuccessAt?.takeIf { status.hasPassword }?.let { "上次同步：${formatSyncTime(it, now)}" } ?: "同步浏览记录"
    return WebDavRowSummary(value, summary)
}

internal enum class WebDavTone { Unset, Ready, Busy, Done, Failed }

/** The status card: one headline, the lines under it and, after a sync that changed something, its counts. */
internal data class WebDavStatusSummary(
    val tone: WebDavTone,
    val headline: String,
    val lines: List<String> = emptyList(),
    val counts: HistorySyncCounts? = null,
    val progress: String? = null,
)

/**
 * [shownError] is the error a banner is showing right now: the card leaves the same sentence out
 * instead of printing it twice.
 */
internal fun WebDavSyncUiState.statusSummary(now: Long, shownError: String? = null): WebDavStatusSummary {
    val activity = progress
    val result = lastResult
    return when {
        configurationError != null -> WebDavStatusSummary(WebDavTone.Failed, "读取同步设置失败", listOf(configurationError))
        activity is HistorySyncActivity.Testing -> WebDavStatusSummary(WebDavTone.Busy, "正在测试连接", progress = "正在连接服务器")
        activity is HistorySyncActivity.Syncing -> WebDavStatusSummary(
            WebDavTone.Busy,
            when (activity.direction) {
                HistorySyncDirection.MERGE -> if (activity.automatic) "正在自动同步" else "正在同步"
                HistorySyncDirection.LOCAL_TO_REMOTE -> "正在用本机覆盖云端"
                HistorySyncDirection.REMOTE_TO_LOCAL -> "正在用云端覆盖本机"
            },
            listOf(activity.phase.label(), "离开此页不会中断"),
            progress = activity.phase.label(),
        )
        !hasPassword -> WebDavStatusSummary(WebDavTone.Unset, "尚未设置", listOf("填写账号并保存后即可同步"))
        result == null -> WebDavStatusSummary(WebDavTone.Ready, "已就绪",
            listOf(lastSyncedAt?.let { "上次同步：${formatSyncTime(it, now)}" } ?: "还没有同步过"))
        result.successful -> {
            val changed = result.counts.run { uploaded + downloaded + removed } > 0
            WebDavStatusSummary(
                WebDavTone.Done, "已同步",
                listOfNotNull(
                    lastSyncedAt?.let { "上次同步：${formatSyncTime(it, now)}" },
                    "两边的记录已经一致".takeIf { !changed },
                ),
                counts = result.counts.takeIf { changed },
            )
        }
        else -> WebDavStatusSummary(
            WebDavTone.Failed, "同步失败",
            listOfNotNull(
                listOfNotNull(lastAttemptAt?.let { formatSyncTime(it, now) }, result.message.takeIf { it != shownError })
                    .joinToString(" · ").takeIf(String::isNotEmpty),
                "再同步一次即可补齐".takeIf { result.localChanged || result.remoteWritten },
                lastSyncedAt?.let { "上次成功：${formatSyncTime(it, now)}" },
            ),
        )
    }
}

internal fun HistorySyncPhase.label(): String = when (this) {
    HistorySyncPhase.CONNECTING -> "正在连接服务器"
    HistorySyncPhase.DOWNLOADING -> "正在读取云端记录"
    HistorySyncPhase.MERGING -> "正在合并记录"
    HistorySyncPhase.WRITING_LOCAL -> "正在更新本机记录"
    HistorySyncPhase.UPLOADING -> "正在上传到云端"
    HistorySyncPhase.RETRYING -> "云端刚有变化，正在重试"
}

/** One sentence for a screen reader, in the order the three numbers are shown. */
internal fun HistorySyncCounts.spoken(): String = "传到云端 $uploaded 条，存到本机 $downloaded 条，删除 $removed 条"

internal enum class WebDavField { Address, Username, Password, Folder }

/** The field an error belongs under; null for the ones that are about the server or the device. */
internal fun WebDavError.field(): WebDavField? = when (this) {
    WebDavError.INVALID_ADDRESS, WebDavError.HTTPS_REQUIRED -> WebDavField.Address
    WebDavError.INVALID_USERNAME -> WebDavField.Username
    WebDavError.PASSWORD_REQUIRED -> WebDavField.Password
    WebDavError.INVALID_FOLDER -> WebDavField.Folder
    else -> null
}

/** Finds the field of an error the view model has already turned into its sentence. */
internal fun webDavFieldError(message: String?): WebDavError? =
    if (message == null) null else WebDavError.entries.firstOrNull { it.field() != null && it.message() == message }

/**
 * The checks a save runs, done on the draft so each field can say what is wrong before anything is
 * sent. [passwordRequired] is false while the stored password still belongs to the drafted account.
 */
internal fun validateWebDavAccount(draft: WebDavAccountDraft, password: String, passwordRequired: Boolean): Map<WebDavField, WebDavError> = buildMap {
    (WebDavEndpoint.parse(draft.address) as? WebDavResult.Failure)?.let { put(WebDavField.Address, it.error) }
    val username = draft.username.trim()
    if (username.isEmpty() || username.any { it == ':' || it.isISOControl() }) put(WebDavField.Username, WebDavError.INVALID_USERNAME)
    if (passwordRequired && password.isEmpty()) put(WebDavField.Password, WebDavError.PASSWORD_REQUIRED)
    (WebDavEndpoint.parse(FOLDER_CHECK_ADDRESS, draft.folder) as? WebDavResult.Failure)?.let { put(WebDavField.Folder, it.error) }
}

/** Any well-formed address: only the folder name is under test. */
private const val FOLDER_CHECK_ADDRESS = "https://localhost/"

/**
 * True when both drafts name the same server folder and user, which is what a stored password is
 * bound to: a trailing slash or the case of the host does not make a different account.
 */
internal fun sameWebDavAccount(first: WebDavAccountDraft, second: WebDavAccountDraft): Boolean {
    fun WebDavAccountDraft.identity(): String? =
        (WebDavEndpoint.parse(address) as? WebDavResult.Success)?.value?.collection?.let { "$it\n${username.trim()}" }
    val identity = first.identity()
    return identity != null && identity == second.identity()
}
