package app.picacgmd.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.picacgmd.testing.ComposeHostRule
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** The settings search finds a setting by its name and by what it does, and opens the page that holds it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1400dp-xhdpi")
class SettingsSearchTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val opened = mutableListOf<SettingsPage>()

    @Test
    fun automaticRouteSyncIsFoundByNameAndOpensTheNetworkPage() {
        compose()
        search("自动同步分流")

        composeRule.onNode(hasText("自动同步分流") and hasText("网络 › 分流", substring = true)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(SettingsPage.Network), opened)
    }

    @Test
    fun automaticRouteSyncIsFoundByItsOpeningAnd24HourRule() {
        compose()
        listOf("分流窗口", "24 小时").forEach { query ->
            search(query)
            composeRule.onAllNodesWithText("自动同步分流").assertCountEquals(1)
        }
    }

    @Test
    fun theSyncPanelIsFoundByNameAndOpensTheDataPage() {
        compose()
        search("webdav")
        composeRule.onNode(hasText("WebDAV 云同步") and hasText("数据与存储 › 同步", substring = true)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(SettingsPage.Data), opened)
    }

    @Test
    fun theSyncPanelIsFoundByWhatItSyncsAndByAutomaticSync() {
        compose()
        listOf("浏览记录", "阅读进度", "自动同步").forEach { query ->
            search(query)
            composeRule.onAllNodesWithText("WebDAV 云同步").assertCountEquals(1)
        }
        search("稍后提供")
        composeRule.onAllNodesWithText("WebDAV 云同步").assertCountEquals(0)
    }

    @Test
    fun blockedKeywordsAreFoundByTheWordTheComicPageUsesAndOpenTheBrowsingPage() {
        compose()
        search("屏蔽")
        composeRule.onAllNodesWithText("屏蔽关键词").assertCountEquals(1)
        composeRule.onNode(hasText("封印与屏蔽") and hasText("列表与浏览 › 封印", substring = true)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(SettingsPage.Browsing), opened)
    }

    @Test
    fun blockedKeywordsAreFoundByUnblockingAndByTheirFormerName() {
        compose()
        listOf("取消屏蔽", "隐藏").forEach { query ->
            search(query)
            composeRule.onAllNodesWithText("屏蔽关键词").assertCountEquals(1)
        }
    }

    @Test
    fun unmeteredDownloadsAreFoundByWifiAndTrafficAndOpenTheDownloadsPage() {
        compose()
        listOf("Wi-Fi", "流量").forEach { query ->
            search(query)
            composeRule.onAllNodesWithText("仅在不计流量的网络下载").assertCountEquals(1)
            composeRule.onAllNodesWithText("手动下载也只用 Wi-Fi 等不计流量的网络", substring = true).assertCountEquals(1)
        }
        composeRule.onNode(hasText("仅在不计流量的网络下载") and hasText("下载 › 网络", substring = true)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(SettingsPage.Downloads), opened)
    }

    @Test
    fun automaticUnmeteredDownloadsAreFoundByWifiAndTrafficAndOpenTheDownloadsPage() {
        compose()
        listOf("Wi-Fi", "流量").forEach { query ->
            search(query)
            composeRule.onAllNodesWithText("自动下载仅限不计流量的网络").assertCountEquals(1)
        }
        composeRule.onNode(hasText("自动下载仅限不计流量的网络") and hasText("下载 › 自动化", substring = true)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(SettingsPage.Downloads), opened)
    }

    @Test
    fun downloadQualityIsFoundByNameAndQualityAndOpensTheDownloadsPage() {
        compose()
        listOf("下载画质", "画质").forEach { query ->
            search(query)
            composeRule.onAllNodes(hasText("下载画质") and hasText("下载 › 网络", substring = true)).assertCountEquals(1)
        }
        composeRule.onNode(hasText("下载画质") and hasText("下载 › 网络", substring = true)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(SettingsPage.Downloads), opened)
    }

    @Test
    fun onlineQualitySearchExplainsWhereDownloadQualityIsChosen() {
        compose()
        search("图片质量")

        composeRule.onAllNodesWithText("网络 › 看图 · 用于在线阅读；下载画质在下载设置中选择").assertCountEquals(1)
        composeRule.onAllNodesWithText("仅影响在线图片，下载为原图", substring = true).assertCountEquals(0)
    }

    private fun compose() {
        composeRule.setContent { MaterialTheme { SettingsScreen(onBack = {}, onOpenPage = { opened += it }) } }
    }

    private fun search(query: String) {
        composeRule.onNode(hasSetTextAction()).performTextReplacement(query)
        composeRule.waitForIdle()
    }
}
