package app.picacgmd.feature.routing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.RoutingMode
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.data.settings.defaultApiAddresses
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.domain.session.NetworkConfigProvider
import app.picacgmd.core.domain.session.RoutingRepository
import app.picacgmd.core.network.ApiRoute
import app.picacgmd.core.network.ImageServer
import app.picacgmd.testing.ComposeHostRule
import app.picacgmd.testing.MemoryPreferencesStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The dialog fills the whole screen of this host, while a phone keeps a margin around it, so these
// screens are as wide as the dialog itself: on a 360dp phone, and at the narrowest the dialog allows.
private const val PhoneDialog = "w320dp-h640dp-xhdpi"
private const val NarrowestDialog = "w280dp-h640dp-xhdpi"

/**
 * The real picker over the real route settings; only the two probes are replaced, so nothing leaves the machine.
 * Text is measured with real fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PhoneDialog)
class RoutePickerTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val settings = SettingsRepository(MemoryPreferencesStore(), AppSettings(autoPunchIn = false))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val network = NetworkConfigProvider(settings.settings, scope)
    private val routing = RoutingRepository(settings, network, fetchAddresses = { error("Unexpected route sync") })

    // 分流 1 is selected at first; 分流 2 answers fastest and 分流 3 does not answer.
    private var apiLatencies: Map<String?, Long?> = mapOf(null to 800L, Address1 to 620L, Address2 to 120L, Address3 to null)

    // 不分流 is selected at first; 分流 1 answers fastest and 分流 3 does not answer.
    private var imageLatencies: Map<ImageServer, Long?> = mapOf(
        ImageServer.Original to 900L, ImageServer.Split1 to 300L, ImageServer.Split2 to 1_500L, ImageServer.Split3 to null,
    )
    private val probesMayAnswer = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var probeGate: CompletableDeferred<Unit> = probesMayAnswer

    private var open by mutableStateOf(true)
    private var dismissals = 0

    @After
    fun tearDown() {
        scope.cancel()
        network.close()
    }

    @Test
    fun autoSelectSwitchesBothListsToTheirFastestRouteAndClosesThePicker() {
        showPicker()

        autoSelect().performClick()
        composeRule.waitUntil { dismissals == 1 }

        assertEquals(Address2, saved().apiRouteAddress)
        assertEquals(RoutingMode.ROUTE_1, saved().imageRoutingMode)
        composeRule.onAllNodesWithText("自动选择最快").assertCountEquals(0)
    }

    @Test
    fun autoSelectClosesThePickerWhenTheFastestRoutesAreAlreadyInUse() {
        runBlocking {
            settings.setApiRouteAddress(Address2)
            settings.setImageRouting(RoutingMode.ROUTE_1)
        }
        showPicker()

        autoSelect().performClick()
        composeRule.waitUntil { dismissals == 1 }

        assertEquals(Address2, saved().apiRouteAddress)
        assertEquals(RoutingMode.ROUTE_1, saved().imageRoutingMode)
    }

    @Test
    fun autoSelectClosesThePickerWhenOnlyOneListHasARouteThatAnswers() {
        imageLatencies = imageLatencies.mapValues { null }
        showPicker()

        autoSelect().performClick()
        composeRule.waitUntil { dismissals == 1 }

        assertEquals(Address2, saved().apiRouteAddress)
        assertEquals(RoutingMode.NONE, saved().imageRoutingMode)
    }

    @Test
    fun autoSelectCannotBeUsedAndThePickerStaysOpenWhenNoRouteAnswers() {
        apiLatencies = apiLatencies.mapValues { null }
        imageLatencies = imageLatencies.mapValues { null }
        showPicker()

        autoSelect().assertIsNotEnabled()
        composeRule.onNodeWithText("暂无可用分流，请检查网络后重新测速").assertExists()
        assertEquals(0, dismissals)
        assertEquals(Address1, saved().apiRouteAddress)
        assertEquals(RoutingMode.NONE, saved().imageRoutingMode)
    }

    @Test
    fun autoSelectCanOnlyBeUsedOnceEveryProbeHasAnswered() {
        probeGate = CompletableDeferred()
        showPicker()
        // While the probes run, the button itself says so and cannot be used.
        composeRule.onNodeWithText("正在测速…").assertIsNotEnabled()
        composeRule.onAllNodesWithText("自动选择最快").assertCountEquals(0)

        probeGate.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("正在测速…").assertCountEquals(0)
        composeRule.onAllNodesWithText("点按立即切换").assertCountEquals(0)
        autoSelect().assertIsEnabled().performClick()
        composeRule.waitUntil { dismissals == 1 }

        assertEquals(Address2, saved().apiRouteAddress)
    }

    @Test
    fun aTapOnARouteSwitchesToItAndKeepsThePickerOpen() {
        showPicker()

        apiRow("分流 2").performScrollTo().performClick()
        composeRule.waitUntil { saved().apiRouteAddress == Address2 }

        assertEquals(0, dismissals)
        apiRow("分流 2").assertIsSelected()
        assertEquals(RoutingMode.NONE, saved().imageRoutingMode)
    }

    @Test
    fun aSelectedRouteThatDoesNotAnswerIsReplacedByTheFastestOneAfterTheProbes() {
        apiLatencies = apiLatencies + (Address1 to null)
        showPicker()

        composeRule.waitUntil { saved().apiRouteAddress == Address2 }
        assertEquals(0, dismissals)
    }

    @Test
    fun aPickerClosedWhileProbingSelectsNothingAfterwards() {
        // The same probes as the test above, which end in a switch when the picker is still open.
        apiLatencies = apiLatencies + (Address1 to null)
        probeGate = CompletableDeferred()
        showPicker()

        open = false
        composeRule.waitForIdle()
        probeGate.complete(Unit)
        composeRule.waitForIdle()

        assertEquals(Address1, saved().apiRouteAddress)
        assertEquals(RoutingMode.NONE, saved().imageRoutingMode)
    }

    @Test
    fun aRowIsSpokenWithItsNameLatencyGradeAndSelection() {
        showPicker()

        // The order a screen reader goes through: name, tag, result.
        assertEquals(listOf("分流 2", "最快", "延迟 120 毫秒，快"), apiRow("分流 2").spokenTexts())
        assertEquals(listOf("分流 1", "延迟 620 毫秒，中等"), apiRow("分流 1").spokenTexts())
        apiRow("分流 1").assertIsSelected().assert(hasText("延迟 620 毫秒，中等"))
        apiRow("分流 2").assertIsNotSelected().assert(hasText("延迟 120 毫秒，快")).assert(hasText("最快"))
        apiRow("分流 3").assertIsNotSelected().assert(hasText("测速失败"))
        imageRow("分流 2").assertIsNotSelected().assert(hasText("延迟 1500 毫秒，慢"))
        imageRow("不分流").assertIsSelected().assert(hasText("延迟 900 毫秒，中等"))
    }

    @Test
    fun aRouteStillBeingProbedSaysSo() {
        probeGate = CompletableDeferred()
        showPicker()

        apiRow("分流 2").assert(hasText("测速中"))
    }

    @Test
    fun spokenLatencyNamesTheGradeAtItsBoundaries() {
        assertEquals("延迟 499 毫秒，快", routeLatencyDescription(499))
        assertEquals("延迟 500 毫秒，中等", routeLatencyDescription(500))
        assertEquals("延迟 1000 毫秒，中等", routeLatencyDescription(1_000))
        assertEquals("延迟 1001 毫秒，慢", routeLatencyDescription(1_001))
        assertEquals("测速失败", routeLatencyDescription(null))
    }

    @Test
    fun everyRowIsOneLineAtTheSmallestTouchHeight() {
        showWidestRows()

        assertEveryRowIsOneLine()
    }

    @Test
    fun everyRowStaysOneLineOnANarrowScreenWithALargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        showWidestRows()

        assertEveryRowIsOneLine()
    }

    @Test
    @Config(qualifiers = NarrowestDialog)
    fun aTagMovesUnderTheNameWhenTheLineIsFull() {
        RuntimeEnvironment.setFontScale(1.3f)
        showWidestRows()

        val oneLine = with(composeRule.density) { OneLineHeight.roundToPx() }
        assertTrue(apiRow("分流 12").fetchSemanticsNode().size.height > oneLine, "the tag did not wrap")
        assertEquals(oneLine, apiRow("分流 11").fetchSemanticsNode().size.height)
        assertNothingIsCutOff()
    }

    /** Twelve routes, the last one both the fastest and a four-digit latency: the fullest row the lists can produce. */
    private fun showWidestRows() {
        val synced = (1..9).map { "10.0.0.$it" }
        runBlocking { settings.setSyncedApiAddresses(synced, syncedAt = 0) }
        apiLatencies = (listOf(null) + synced + defaultApiAddresses).associateWith { 4_321L } + (Address3 to 1_234L)
        imageLatencies = imageLatencies + (ImageServer.Split1 to 1_234L) + (ImageServer.Split2 to 4_321L) + (ImageServer.Original to 4_321L)
        showPicker()
        apiRow("分流 12").assert(hasText("最快")).assert(hasText("延迟 1234 毫秒，慢"))
    }

    private fun assertEveryRowIsOneLine() {
        val rows = composeRule.onAllNodes(isSelectable()).fetchSemanticsNodes()
        assertEquals(17, rows.size)
        val oneLine = with(composeRule.density) { OneLineHeight.roundToPx() }
        rows.forEach { row ->
            assertEquals(oneLine, row.size.height, "row ${row.config.getOrNull(SemanticsProperties.Text)?.first()?.text} is taller than one line")
        }
        assertNothingIsCutOff()
    }

    /** No name or tag is shortened or broken to make a row fit. */
    private fun assertNothingIsCutOff() {
        val texts = composeRule.onAllNodes(isRouteNameOrTag(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(texts.size > 17)
        texts.forEach { node ->
            val layout = node.textLayout()
            assertEquals(1, layout.lineCount, "「${layout.layoutInput.text}」 takes more than one line")
            assertFalse(layout.isLineEllipsized(0), "「${layout.layoutInput.text}」 is cut off")
        }
    }

    private fun SemanticsNodeInteraction.spokenTexts(): List<String> =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun isRouteNameOrTag() = SemanticsMatcher("is a route name or tag") { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        text != null && RowText.matches(text)
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun showPicker() {
        composeRule.setContent {
            PicaTheme(themeChoice = ThemeChoice.Brand) {
                if (open) {
                    RoutePickerDialog(
                        routing = routing,
                        measureApiRoute = { route -> probeGate.await(); apiLatencies.getValue((route as? ApiRoute.Address)?.ip) },
                        measureImageRoute = { server -> probeGate.await(); imageLatencies.getValue(server) },
                        onDismiss = { dismissals++; open = false },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun autoSelect(): SemanticsNodeInteraction = composeRule.onNodeWithText("自动选择最快")

    /** Both lists use the same names; the 分流 list comes first. */
    private fun apiRow(name: String): SemanticsNodeInteraction = composeRule.onAllNodes(hasText(name) and isSelectable())[0]

    private fun imageRow(name: String): SemanticsNodeInteraction = composeRule.onAllNodes(hasText(name) and isSelectable())[1]

    private fun saved(): AppSettings = runBlocking { settings.settings.first() }

    private companion object {
        val OneLineHeight = 48.dp
        val Address1 = defaultApiAddresses[0]
        val Address2 = defaultApiAddresses[1]
        val Address3 = defaultApiAddresses[2]

        /** Route names and tags; not the dialog's headings and buttons. */
        val RowText = Regex("""不分流|分流 \d+|最快|旧地址""")
    }
}
