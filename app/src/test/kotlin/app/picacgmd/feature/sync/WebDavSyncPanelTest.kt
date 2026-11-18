package app.picacgmd.feature.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import app.picacgmd.core.domain.sync.HistorySyncCounts
import app.picacgmd.core.domain.sync.HistorySyncDirection
import app.picacgmd.core.domain.sync.HistorySyncOutcome
import app.picacgmd.core.domain.sync.HistorySyncStatus
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavResult
import app.picacgmd.testing.ComposeHostRule
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real panel on the tested view model, driven the way a user drives it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1600dp-xhdpi")
class WebDavSyncPanelTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val actions = FakeSyncActions()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var saves = 0
    private var backs = 0
    private var shown by mutableStateOf(true)
    private lateinit var viewModel: WebDavSyncViewModel

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun anAccountIsSetUpSavedTestedAndSynced() {
        compose(configured = false)
        composeRule.onNodeWithText("尚未设置").assertExists()
        composeRule.onAllNodesWithText("立即同步").assertCountEquals(0)
        composeRule.onAllNodesWithText("用本机覆盖云端").assertCountEquals(0)
        composeRule.onNodeWithText("仅支持 https:// 开头的地址").assertExists()
        composeRule.onNodeWithText("只同步浏览记录、阅读进度和置顶，其他数据都留在本机。").assertExists()

        field(WebDavPanelTags.Address).performTextInput("https://localhost/dav")
        field(WebDavPanelTags.Username).performTextInput("test-reader")
        field(WebDavPanelTags.Password).performTextInput("private-panel-password")
        assertEquals("PicACGMD", text(WebDavPanelTags.Folder))
        click("保存")

        assertEquals(1, saves)
        assertEquals("private-panel-password", actions.savedPassword)
        assertEquals("https://localhost/dav", actions.state.value.config.address)
        composeRule.onNodeWithText("已保存 WebDAV 账号").assertExists()
        composeRule.onNodeWithText("已就绪").assertExists()
        composeRule.onNodeWithText("还没有同步过").assertExists()
        composeRule.onAllNodesWithTag(WebDavPanelTags.Password).assertCountEquals(0)
        composeRule.onNode(hasText("https://localhost/dav") and hasText("修改")).assertExists()

        click("测试连接")
        assertEquals(1, actions.connections)
        composeRule.onNodeWithText("连接成功").assertExists()

        click("立即同步")
        assertEquals(listOf(HistorySyncDirection.MERGE), actions.directions)
        composeRule.onNodeWithText("已同步").assertExists()
        composeRule.onNodeWithTag(WebDavPanelTags.Counts).assertExists()
        assertEquals("传到云端 2 条，存到本机 3 条，删除 4 条", description(composeRule.onNodeWithTag(WebDavPanelTags.Counts)))
        // The card already says it; no second sentence for the same success.
        composeRule.onAllNodesWithText("同步完成").assertCountEquals(0)
    }

    @Test
    fun httpIsRefusedWhileTypingAndASaveWithMissingFieldsNamesThemWithoutSaving() {
        compose(configured = false)
        composeRule.onAllNodesWithText("仅支持 HTTPS 地址").assertCountEquals(0)
        field(WebDavPanelTags.Address).performTextInput("http://localhost/dav")
        composeRule.onNodeWithText("仅支持 HTTPS 地址").assertExists()
        composeRule.onAllNodesWithText("请填写有效的用户名").assertCountEquals(0)

        click("保存")
        assertEquals(0, saves)
        composeRule.onNodeWithText("请填写有效的用户名").assertExists()
        composeRule.onNodeWithText("请输入密码").assertExists()
        field(WebDavPanelTags.Address).assertIsFocused()

        field(WebDavPanelTags.Address).performTextReplacement("https://localhost/dav")
        field(WebDavPanelTags.Username).performTextInput("test-reader")
        field(WebDavPanelTags.Password).performTextInput("secret")
        composeRule.onAllNodesWithText("仅支持 HTTPS 地址").assertCountEquals(0)
        composeRule.onAllNodesWithText("请输入密码").assertCountEquals(0)
        click("保存")
        assertEquals(1, saves)
    }

    @Test
    fun anAddressTheServiceRefusesIsShownUnderItsFieldAndAStorageFailureInADismissibleBanner() {
        compose(configured = false)
        field(WebDavPanelTags.Address).performTextInput("https://localhost/dav")
        field(WebDavPanelTags.Username).performTextInput("test-reader")
        field(WebDavPanelTags.Password).performTextInput("secret")
        actions.saveResult = WebDavResult.Failure(WebDavError.INVALID_ADDRESS)
        click("保存")
        composeRule.onNodeWithText("请填写有效的 WebDAV 地址").assertExists()
        composeRule.onAllNodesWithContentDescription("关闭提示").assertCountEquals(0)
        assertEquals("secret".length, text(WebDavPanelTags.Password).length)

        actions.saveResult = WebDavResult.Failure(WebDavError.STORAGE)
        click("保存")
        composeRule.onNodeWithText("读写同步数据失败，请重试").assertExists()
        composeRule.onNodeWithContentDescription("关闭提示").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("读写同步数据失败，请重试").assertCountEquals(0)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun theStoredPasswordIsNeverPutBackIntoItsField() {
        compose()
        composeRule.onAllNodesWithTag(WebDavPanelTags.Password).assertCountEquals(0)
        click("修改")
        assertEquals("", text(WebDavPanelTags.Password))
        composeRule.onNodeWithText("已保存，留空则不修改").assertExists()
        assertTrue(SemanticsProperties.Password in field(WebDavPanelTags.Password).fetchSemanticsNode().config)

        composeRule.onNodeWithContentDescription("显示密码").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("隐藏密码").assertExists()
        assertEquals("", text(WebDavPanelTags.Password))

        field(WebDavPanelTags.Password).performTextInput("new-private-password")
        click("保存")
        assertEquals("new-private-password", actions.savedPassword)
        composeRule.onAllNodesWithTag(WebDavPanelTags.Password).assertCountEquals(0)
        click("修改")
        assertEquals("", text(WebDavPanelTags.Password))
        assertFalse(viewModel.state.value.toString().contains("new-private-password"))

        // Saving with the field left empty keeps the stored password.
        click("保存")
        assertNull(actions.savedPassword)
        assertEquals(2, saves)
    }

    @Test
    fun aChangedAddressAsksForThePasswordAgain() {
        compose()
        click("修改")
        field(WebDavPanelTags.Address).performTextReplacement("https://localhost/other/")
        composeRule.onNodeWithText("地址或用户名变了，请重新输入密码").assertExists()
        click("保存")
        assertEquals(0, saves)
        composeRule.onNodeWithText("请输入密码").assertExists()
        field(WebDavPanelTags.Password).assertIsFocused()
    }

    @Test
    fun unsavedEditsDisableEveryActionUntilTheyAreSavedOrCancelled() {
        compose()
        composeRule.onNodeWithText("立即同步").assertIsEnabled()
        click("修改")
        composeRule.onNodeWithText("立即同步").assertIsEnabled()
        field(WebDavPanelTags.Folder).performTextReplacement("阅读记录")
        assertTrue(viewModel.state.value.dirty)

        composeRule.onNodeWithText("账号的修改还没保存，保存或取消后才能同步").assertExists()
        listOf("立即同步", "测试连接", "用本机覆盖云端", "用云端覆盖本机").forEach { composeRule.onNodeWithText(it).assertIsNotEnabled() }
        click("立即同步"); click("测试连接"); click("用本机覆盖云端")
        assertEquals(emptyList(), actions.directions); assertEquals(0, actions.connections)
        composeRule.onAllNodesWithText("用本机覆盖云端？").assertCountEquals(0)

        click("取消")
        assertFalse(viewModel.state.value.dirty)
        assertEquals("PicACGMD", viewModel.state.value.account.folder)
        composeRule.onAllNodesWithTag(WebDavPanelTags.Folder).assertCountEquals(0)
        composeRule.onNodeWithText("立即同步").assertIsEnabled()

        // A password typed over the stored one is an unsaved edit as well.
        click("修改")
        field(WebDavPanelTags.Password).performTextInput("typed")
        assertFalse(viewModel.state.value.dirty)
        composeRule.onNodeWithText("立即同步").assertIsNotEnabled()
    }

    @Test
    fun leavingWithUnsavedEditsAsksFirst() {
        compose()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        assertEquals(1, backs)

        click("修改")
        field(WebDavPanelTags.Username).performTextReplacement("someone-else")
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("放弃未保存的修改？").assertExists()
        assertEquals(1, backs)
        click("继续编辑")
        assertEquals(1, backs)
        assertEquals("someone-else", text(WebDavPanelTags.Username))

        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        click("放弃")
        assertEquals(2, backs)
        assertFalse(viewModel.state.value.dirty)
        assertEquals("test-reader", viewModel.state.value.account.username)
        assertEquals(0, saves)
    }

    @Test
    fun eachOverwriteNeedsAConfirmationThatNamesWhatIsReplacedAndThatItCannotBeUndone() {
        compose()
        click("用本机覆盖云端")
        assertEquals(emptyList(), actions.directions)
        composeRule.onNodeWithText("用本机覆盖云端？").assertExists()
        composeRule.onNodeWithText("云端的浏览记录、阅读进度和置顶会全部换成本机的，其他设备下次同步时也会跟着变。覆盖后无法撤销。").assertExists()
        click("取消")
        assertEquals(emptyList(), actions.directions)
        composeRule.onAllNodesWithText("用本机覆盖云端？").assertCountEquals(0)

        click("用本机覆盖云端")
        click("覆盖云端")
        assertEquals(listOf(HistorySyncDirection.LOCAL_TO_REMOTE), actions.directions)

        click("用云端覆盖本机")
        assertEquals(1, actions.directions.size)
        composeRule.onNodeWithText("本机的浏览记录、阅读进度和置顶会全部换成云端的，只在本机有的记录会被删除。覆盖后无法撤销。").assertExists()
        click("覆盖本机")
        assertEquals(listOf(HistorySyncDirection.LOCAL_TO_REMOTE, HistorySyncDirection.REMOTE_TO_LOCAL), actions.directions)
        assertTrue(actions.automatic.none { it })
    }

    @Test
    fun aFailureIsShownInWordsAndCanBeDismissed() {
        compose()
        actions.connectionResult = WebDavResult.Failure(WebDavError.AUTHENTICATION)
        click("测试连接")
        composeRule.onNodeWithText("账号或密码不正确").assertExists()
        composeRule.onNodeWithContentDescription("关闭提示").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("账号或密码不正确").assertCountEquals(0)
        assertNull(viewModel.state.value.error)

        actions.beforeTest = { throw IOException("private-diagnostic-value") }
        click("测试连接")
        composeRule.onNodeWithText("读写同步数据失败，请重试").assertExists()
        composeRule.onAllNodesWithText("private-diagnostic-value", substring = true).assertCountEquals(0)
        actions.syncResult = HistorySyncOutcome.Failure(WebDavError.INVALID_TIMESTAMP)
        click("立即同步")
        composeRule.onNodeWithText("云端记录的时间不正常，请检查各设备的系统时间").assertExists()
        composeRule.onAllNodesWithTag(WebDavPanelTags.Counts).assertCountEquals(0)
    }

    @Test
    fun aHalfDoneSyncSaysWhatHappenedAndStaysOnTheCardAfterItsBannerIsClosed() {
        compose()
        actions.syncResult = HistorySyncOutcome.Failure(WebDavError.CONNECTION, counts = HistorySyncCounts(0, 2, 1), localChanged = true)
        click("立即同步")
        val sentence = "无法连接服务器。本机记录已更新，云端未更新"
        composeRule.onNodeWithText("同步失败").assertExists()
        composeRule.onNodeWithText(sentence).assertExists()
        composeRule.onNodeWithText("再同步一次即可补齐").assertExists()
        composeRule.onAllNodesWithTag(WebDavPanelTags.Counts).assertCountEquals(0)

        composeRule.onNodeWithContentDescription("关闭提示").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(sentence).assertCountEquals(0)
        composeRule.onNodeWithText(sentence, substring = true).assertExists()
        composeRule.onNodeWithText("立即同步").assertIsEnabled()
    }

    @Test
    fun aRunShowsItsStepCannotBeStartedTwiceAndGoesOnAfterThePageIsLeft() {
        compose()
        val gate = CompletableDeferred<Unit>()
        actions.beforeSync = { gate.await() }
        click("立即同步")

        composeRule.onNodeWithText("正在同步").assertExists()
        composeRule.onNodeWithText("正在读取云端记录").assertExists()
        composeRule.onNodeWithText("离开此页不会中断").assertExists()
        val progress = composeRule.onNodeWithTag(WebDavPanelTags.Progress).fetchSemanticsNode().config
        assertEquals("正在读取云端记录", progress.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(SemanticsProperties.ProgressBarRangeInfo in progress)
        assertTrue(SemanticsProperties.LiveRegion in composeRule.onNodeWithTag(WebDavPanelTags.Status).fetchSemanticsNode().config)

        listOf("立即同步", "测试连接", "用本机覆盖云端", "用云端覆盖本机", "修改").forEach { composeRule.onNodeWithText(it).assertExists() }
        listOf("立即同步", "测试连接", "用本机覆盖云端", "用云端覆盖本机").forEach { composeRule.onNodeWithText(it).assertIsNotEnabled() }
        switch("自动同步").assertIsNotEnabled()
        click("立即同步"); click("测试连接")
        assertEquals(1, actions.directions.size); assertEquals(0, actions.connections)

        // Back is not held up by a run, and the run is not ended by it.
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        assertEquals(1, backs)
        shown = false
        composeRule.waitForIdle()
        composeRule.runOnIdle { gate.complete(Unit) }
        composeRule.waitForIdle()
        assertTrue(viewModel.state.value.lastResult!!.successful)
        assertFalse(viewModel.state.value.busy)

        shown = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已同步").assertExists()
        composeRule.onNodeWithText("立即同步").assertIsEnabled()
    }

    @Test
    fun unmeteredOnlyCanBeChangedOnlyWhileAutomaticSyncIsOn() {
        compose()
        switch("自动同步").assertIsOff().assertIsEnabled()
        switch("仅在不计流量的网络下自动同步").assertIsOn().assertIsNotEnabled()
        composeRule.onNodeWithText("开启自动同步后可用", substring = true).assertExists()

        switch("自动同步").performClick()
        composeRule.waitForIdle()
        assertTrue(actions.state.value.config.autoSync)
        switch("自动同步").assertIsOn()
        switch("仅在不计流量的网络下自动同步").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertFalse(actions.state.value.config.wifiOnly)
        switch("仅在不计流量的网络下自动同步").assertIsOff()
    }

    @Test
    fun keyboardsMatchTheirFieldsAndTheirActionsWalkThroughTheFormToASave() {
        compose(configured = false)
        fun config(tag: String) = field(tag).fetchSemanticsNode().config
        assertEquals(ImeAction.Next, config(WebDavPanelTags.Address).getOrNull(SemanticsProperties.ImeAction))
        assertEquals(ImeAction.Next, config(WebDavPanelTags.Username).getOrNull(SemanticsProperties.ImeAction))
        assertEquals(ImeAction.Next, config(WebDavPanelTags.Password).getOrNull(SemanticsProperties.ImeAction))
        assertEquals(ImeAction.Done, config(WebDavPanelTags.Folder).getOrNull(SemanticsProperties.ImeAction))
        assertEquals(ContentType.Username, config(WebDavPanelTags.Username).getOrNull(SemanticsProperties.ContentType))
        assertEquals(ContentType.Password, config(WebDavPanelTags.Password).getOrNull(SemanticsProperties.ContentType))

        field(WebDavPanelTags.Address).performTextInput("https://localhost/dav")
        field(WebDavPanelTags.Address).performImeAction()
        field(WebDavPanelTags.Username).assertIsFocused().performTextInput("test-reader")
        field(WebDavPanelTags.Username).performImeAction()
        field(WebDavPanelTags.Password).assertIsFocused().performTextInput("secret")
        field(WebDavPanelTags.Password).performImeAction()
        field(WebDavPanelTags.Folder).assertIsFocused().performImeAction()
        composeRule.waitForIdle()
        assertEquals(1, saves)
        assertEquals("secret", actions.savedPassword)
    }

    @Test
    fun typedTextExceptThePasswordComesBackAfterTheProcessIsRecreated() {
        val restoration = StateRestorationTester(composeRule)
        prepare(configured = true)
        restoration.setContent { MaterialTheme { WebDavSyncPanel(viewModel, onBack = { backs++ }) } }
        click("修改")
        field(WebDavPanelTags.Address).performTextReplacement("https://localhost/typed/")
        field(WebDavPanelTags.Folder).performTextReplacement("阅读记录")
        field(WebDavPanelTags.Password).performTextInput("private-panel-password")

        // A new process has a new holder that knows the stored account only.
        viewModel = WebDavSyncViewModel(actions, scope)
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertEquals("https://localhost/typed/", text(WebDavPanelTags.Address))
        assertEquals("test-reader", text(WebDavPanelTags.Username))
        assertEquals("阅读记录", text(WebDavPanelTags.Folder))
        assertEquals("", text(WebDavPanelTags.Password))
        assertTrue(viewModel.state.value.dirty)
        assertEquals("https://localhost/dav/", actions.state.value.config.address)
        composeRule.onNodeWithText("立即同步").assertIsNotEnabled()
    }

    @Test
    fun aStoredLastSyncTimeSurvivesPanelAndViewModelRecreationWithoutRestoringCounts() {
        val restoration = StateRestorationTester(composeRule)
        prepare(configured = true)
        restoration.setContent { MaterialTheme { WebDavSyncPanel(viewModel, onBack = { backs++ }) } }
        composeRule.onNodeWithText("还没有同步过").assertExists()
        click("立即同步")
        composeRule.onNodeWithTag(WebDavPanelTags.Counts).assertExists()
        val restartedActions = FakeSyncActions(actions.state.value.config)
        viewModel = WebDavSyncViewModel(restartedActions, scope)
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals(123L, viewModel.state.value.lastSyncedAt)
        assertNull(viewModel.state.value.lastResult)
        assertNull(viewModel.state.value.lastAttemptAt)
        assertNull(viewModel.state.value.error)
        composeRule.onNodeWithText("已就绪").assertExists()
        composeRule.onNodeWithText("上次同步：", substring = true).assertExists()
        composeRule.onAllNodesWithText("还没有同步过").assertCountEquals(0)
        composeRule.onAllNodesWithTag(WebDavPanelTags.Counts).assertCountEquals(0)
    }

    private fun prepare(configured: Boolean) {
        if (!configured) actions.state.value = HistorySyncStatus(ready = true)
        actions.beforeSave = { saves++ }
        viewModel = WebDavSyncViewModel(actions, scope)
    }

    private fun compose(configured: Boolean = true) {
        prepare(configured)
        composeRule.setContent { MaterialTheme { if (shown) WebDavSyncPanel(viewModel, onBack = { backs++ }) } }
        composeRule.waitForIdle()
    }

    private fun click(label: String) {
        composeRule.onNode(hasText(label) and hasClickAction()).performClick()
        composeRule.waitForIdle()
    }

    private fun switch(title: String): SemanticsNodeInteraction = composeRule.onNode(hasText(title) and hasClickAction())

    private fun field(tag: String): SemanticsNodeInteraction = composeRule.onNodeWithTag(tag)

    private fun text(tag: String): String =
        field(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun description(node: SemanticsNodeInteraction): String? =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
}
