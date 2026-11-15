package app.picacgmd.feature.sync

import app.picacgmd.core.data.settings.WebDavSettings
import app.picacgmd.core.domain.sync.HistorySyncActivity
import app.picacgmd.core.domain.sync.HistorySyncCounts
import app.picacgmd.core.domain.sync.HistorySyncDirection
import app.picacgmd.core.domain.sync.HistorySyncOutcome
import app.picacgmd.core.domain.sync.HistorySyncPhase
import app.picacgmd.core.domain.sync.HistorySyncStatus
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.feature.routing.formatSyncTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavSyncPresentationTest {
    private val now = 1_000_000L
    private val saved = HistorySyncStatus(config = WebDavSettings("https://localhost/dav/", "test-reader"), hasPassword = true, ready = true)

    @Test fun theSettingsRowSaysNothingUntilTheAccountIsReadAndThenNamesTheState() {
        assertNull(webDavRowSummary(HistorySyncStatus(), now))
        assertEquals(WebDavRowSummary("未设置", "同步浏览记录"), webDavRowSummary(HistorySyncStatus(ready = true), now))
        assertEquals(WebDavRowSummary("手动同步", "同步浏览记录"), webDavRowSummary(saved, now))
        assertEquals("自动同步", webDavRowSummary(saved.copy(config = saved.config.copy(autoSync = true)), now)!!.value)
        val syncing = HistorySyncActivity.Syncing(HistorySyncDirection.MERGE, HistorySyncPhase.UPLOADING, automatic = true)
        assertEquals("同步中", webDavRowSummary(saved.copy(activity = syncing), now)!!.value)
    }

    @Test fun theSettingsRowShowsTheLastSuccessfulTimeAndAFailureAfterIt() {
        val synced = saved.copy(lastSuccessAt = now - 60_000, lastAttemptAt = now - 60_000,
            lastResult = HistorySyncOutcome.Success(HistorySyncCounts(1, 0, 0)))
        assertEquals(WebDavRowSummary("手动同步", "上次同步：${formatSyncTime(now - 60_000, now)}"), webDavRowSummary(synced, now))
        assertEquals(WebDavRowSummary("手动同步", "上次同步：${formatSyncTime(now - 60_000, now)}"),
            webDavRowSummary(synced.copy(lastAttemptAt = null, lastResult = null), now))
        val failed = synced.copy(lastAttemptAt = now, lastResult = HistorySyncOutcome.Failure(WebDavError.TIMEOUT))
        assertEquals(WebDavRowSummary("同步失败", "上次同步：${formatSyncTime(now - 60_000, now)}"), webDavRowSummary(failed, now))
        // A time kept from an account that is no longer usable is not shown.
        assertEquals(WebDavRowSummary("未设置", "同步浏览记录"), webDavRowSummary(synced.copy(hasPassword = false), now))
    }

    @Test fun theCardNamesEachRestingState() {
        val unset = WebDavSyncUiState(ready = true).statusSummary(now)
        assertEquals(WebDavTone.Unset, unset.tone); assertEquals("尚未设置", unset.headline)
        val ready = WebDavSyncUiState(ready = true, hasPassword = true).statusSummary(now)
        assertEquals(WebDavTone.Ready, ready.tone); assertEquals("已就绪", ready.headline)
        assertEquals(listOf("还没有同步过"), ready.lines)
        val restarted = WebDavSyncUiState(ready = true, hasPassword = true, lastSyncedAt = now - 60_000).statusSummary(now)
        assertEquals(WebDavTone.Ready, restarted.tone)
        assertEquals(listOf("上次同步：${formatSyncTime(now - 60_000, now)}"), restarted.lines)
        assertNull(restarted.counts)
        val broken = WebDavSyncUiState(ready = true, configurationError = "读写同步数据失败，请重试").statusSummary(now)
        assertEquals(WebDavTone.Failed, broken.tone); assertEquals(listOf("读写同步数据失败，请重试"), broken.lines)
    }

    @Test fun aSyncThatChangedSomethingShowsItsCountsAndOneThatDidNotSaysSo() {
        val done = WebDavSyncUiState(ready = true, hasPassword = true, lastSyncedAt = now, lastAttemptAt = now,
            lastResult = WebDavSyncResult(true, "同步完成", HistorySyncCounts(2, 3, 4)))
        val changed = done.statusSummary(now)
        assertEquals(WebDavTone.Done, changed.tone)
        assertEquals(HistorySyncCounts(2, 3, 4), changed.counts)
        assertEquals(listOf("上次同步：${formatSyncTime(now, now)}"), changed.lines)
        assertEquals("传到云端 2 条，存到本机 3 条，删除 4 条", changed.counts!!.spoken())

        val same = done.copy(lastResult = WebDavSyncResult(true, "同步完成", HistorySyncCounts())).statusSummary(now)
        assertNull(same.counts)
        assertEquals("两边的记录已经一致", same.lines.last())
    }

    @Test fun everyPhaseOfARunHasItsOwnWordsAndTheRunSaysItSurvivesLeaving() {
        HistorySyncPhase.entries.forEach { phase ->
            val running = WebDavSyncUiState(ready = true, hasPassword = true,
                progress = HistorySyncActivity.Syncing(HistorySyncDirection.MERGE, phase, automatic = false)).statusSummary(now)
            assertEquals(WebDavTone.Busy, running.tone)
            assertEquals(phase.label(), running.progress)
            assertEquals(listOf(phase.label(), "离开此页不会中断"), running.lines)
        }
        assertEquals(HistorySyncPhase.entries.size, HistorySyncPhase.entries.map { it.label() }.toSet().size)
        fun headline(direction: HistorySyncDirection, automatic: Boolean = false) = WebDavSyncUiState(ready = true, hasPassword = true,
            progress = HistorySyncActivity.Syncing(direction, HistorySyncPhase.CONNECTING, automatic)).statusSummary(now).headline
        assertEquals("正在同步", headline(HistorySyncDirection.MERGE))
        assertEquals("正在自动同步", headline(HistorySyncDirection.MERGE, automatic = true))
        assertEquals("正在用本机覆盖云端", headline(HistorySyncDirection.LOCAL_TO_REMOTE))
        assertEquals("正在用云端覆盖本机", headline(HistorySyncDirection.REMOTE_TO_LOCAL))
        val testing = WebDavSyncUiState(ready = true, hasPassword = true, progress = HistorySyncActivity.Testing).statusSummary(now)
        assertEquals("正在测试连接", testing.headline); assertEquals("正在连接服务器", testing.progress)
    }

    @Test fun aHalfDoneSyncSaysWhatToDoAndKeepsTheLastSuccessfulTime() {
        val message = "无法连接服务器。本机记录已更新，云端未更新"
        val failed = WebDavSyncUiState(ready = true, hasPassword = true, lastSyncedAt = now - 60_000, lastAttemptAt = now,
            lastResult = WebDavSyncResult(false, message, HistorySyncCounts(0, 2, 1), localChanged = true))
        val summary = failed.statusSummary(now)
        assertEquals(WebDavTone.Failed, summary.tone); assertEquals("同步失败", summary.headline)
        assertEquals(listOf("${formatSyncTime(now, now)} · $message", "再同步一次即可补齐", "上次成功：${formatSyncTime(now - 60_000, now)}"), summary.lines)
        assertNull(summary.counts)
        // The banner is showing the same sentence: the card keeps only the time.
        assertEquals(formatSyncTime(now, now), failed.statusSummary(now, shownError = message).lines.first())

        val uploaded = failed.copy(lastResult = WebDavSyncResult(false, "读写同步数据失败，请重试。云端记录已更新，本机的后续处理未完成",
            HistorySyncCounts(1, 2, 0), localChanged = true, remoteWritten = true)).statusSummary(now)
        assertTrue("再同步一次即可补齐" in uploaded.lines)
        val plain = failed.copy(lastResult = WebDavSyncResult(false, "连接超时，请重试", HistorySyncCounts())).statusSummary(now)
        assertFalse("再同步一次即可补齐" in plain.lines)
    }

    @Test fun theFormFindsEveryProblemASaveWouldRefuse() {
        val good = WebDavAccountDraft("https://localhost/dav", "test-reader", "PicACGMD")
        assertEquals(emptyMap(), validateWebDavAccount(good, "secret", passwordRequired = true))
        assertEquals(emptyMap(), validateWebDavAccount(good, "", passwordRequired = false))
        assertEquals(mapOf(WebDavField.Password to WebDavError.PASSWORD_REQUIRED), validateWebDavAccount(good, "", passwordRequired = true))
        assertEquals(mapOf(WebDavField.Address to WebDavError.HTTPS_REQUIRED),
            validateWebDavAccount(good.copy(address = "http://localhost/dav"), "secret", true))
        assertEquals(
            mapOf(WebDavField.Address to WebDavError.INVALID_ADDRESS, WebDavField.Username to WebDavError.INVALID_USERNAME,
                WebDavField.Folder to WebDavError.INVALID_FOLDER),
            validateWebDavAccount(WebDavAccountDraft("", " ", "a/b"), "secret", true),
        )
        assertEquals(mapOf(WebDavField.Username to WebDavError.INVALID_USERNAME),
            validateWebDavAccount(good.copy(username = "a:b"), "secret", true))
        // A bad address does not hide what is wrong with the folder.
        assertEquals(WebDavError.INVALID_FOLDER, validateWebDavAccount(WebDavAccountDraft("ftp://x", "u", ".."), "p", true)[WebDavField.Folder])
    }

    @Test fun aRefusedSaveIsShownUnderItsFieldAndOtherErrorsAreNot() {
        assertEquals(WebDavError.HTTPS_REQUIRED, webDavFieldError("仅支持 HTTPS 地址"))
        assertEquals(WebDavField.Password, webDavFieldError(WebDavError.PASSWORD_REQUIRED.message())!!.field())
        assertNull(webDavFieldError(WebDavError.CONNECTION.message()))
        assertNull(WebDavError.INVALID_TIMESTAMP.field())
        assertNull(webDavFieldError(WebDavError.INVALID_TIMESTAMP.message()))
        assertNull(webDavFieldError(null))
        val fieldMessages = WebDavError.entries.filter { it.field() != null }.map { it.message() }
        assertEquals(fieldMessages.size, fieldMessages.toSet().size)
    }

    @Test fun theStoredPasswordBelongsToTheSameServerFolderAndUserOnly() {
        val stored = WebDavAccountDraft("https://localhost/dav/", "test-reader")
        assertTrue(sameWebDavAccount(stored.copy(address = "https://LOCALHOST/dav", username = " test-reader "), stored))
        assertTrue(sameWebDavAccount(stored.copy(folder = "elsewhere"), stored))
        assertFalse(sameWebDavAccount(stored.copy(address = "https://localhost/other/"), stored))
        assertFalse(sameWebDavAccount(stored.copy(username = "someone-else"), stored))
        assertFalse(sameWebDavAccount(WebDavAccountDraft(), WebDavAccountDraft()))
    }
}
