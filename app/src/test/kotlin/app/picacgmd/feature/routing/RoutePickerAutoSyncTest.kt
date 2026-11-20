package app.picacgmd.feature.routing

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.ImageQuality
import app.picacgmd.core.data.settings.RoutingMode
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.data.settings.defaultApiAddresses
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.domain.session.NetworkConfigProvider
import app.picacgmd.core.domain.session.RoutingRepository
import app.picacgmd.core.network.ApiRoute
import app.picacgmd.testing.ComposeHostRule
import app.picacgmd.testing.MemoryPreferencesStore
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h800dp-xhdpi")
class RoutePickerAutoSyncTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val now = System.currentTimeMillis()
    private val preferences = MemoryPreferencesStore()
    private val settings = SettingsRepository(preferences, AppSettings(autoPunchIn = false))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val network = NetworkConfigProvider(settings.settings, scope)
    private val fetches = AtomicInteger()
    private val probes = AtomicInteger()
    private var fetch: suspend () -> List<String> = { OfficialAddresses }
    private val routing = RoutingRepository(settings, network, fetchAddresses = {
        fetches.incrementAndGet()
        fetch()
    }, nowMillis = { now })
    private var open by mutableStateOf(true)
    private var errorColor = Color.Unspecified

    @After
    fun tearDown() {
        scope.cancel()
        network.close()
    }

    @Test
    fun openingWithA25HourOldSyncFetchesOnceAndShowsTheOfficialSyncResult() {
        configure(enabled = true, lastSyncedAt = now - 25 * Hour)
        showPicker()
        awaitFetches(1)

        composeRule.onNodeWithText(Success).assertExists()
        assertEquals(OfficialAddresses, saved().syncedApiAddresses)
        assertEquals(now, saved().apiRoutesSyncedAt)

        composeRule.onNodeWithContentDescription("重新测速").performClick()
        runBlocking { settings.setImageQuality(ImageQuality.HIGH) }
        composeRule.waitForIdle()
        assertEquals(1, fetches.get())
    }

    @Test
    fun openingWithA1HourOldSyncMakesNoFetch() {
        configure(enabled = true, lastSyncedAt = now - Hour)
        showPicker()

        assertEquals(0, fetches.get())
        composeRule.onNodeWithText("从官方同步").assertIsEnabled()
        composeRule.onNodeWithText(Success).assertDoesNotExist()
    }

    @Test
    fun turningTheOptionOffSkipsAutomaticSyncButTheManualButtonStillWorks() {
        configure(enabled = false, lastSyncedAt = now - 25 * Hour)
        showPicker()

        assertEquals(0, fetches.get())
        composeRule.onNodeWithText("从官方同步").assertIsEnabled().performClick()
        awaitFetches(1)
        composeRule.onNodeWithText(Success).assertExists()
        assertEquals(1, fetches.get())
    }

    @Test
    fun neverSyncedRoutesFetchOnceWithTheDefaultOption() {
        showPicker()
        awaitFetches(1)

        composeRule.onNodeWithText(Success).assertExists()
        assertEquals(1, fetches.get())
    }

    @Test
    fun aFutureSyncTimestampFetchesOnce() {
        configure(enabled = true, lastSyncedAt = now + Hour)
        showPicker()
        awaitFetches(1)

        composeRule.onNodeWithText(Success).assertExists()
        assertEquals(1, fetches.get())
    }

    @Test
    fun aFailedFetchShowsTheAutomaticErrorAndRetriesOnlyAfterReopeningOrAManualTap() {
        configure(enabled = true, lastSyncedAt = now - 25 * Hour)
        fetch = { throw IOException("Offline") }
        val before = saved()
        showPicker()
        awaitFetches(1)

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("自动同步失败").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        assertEquals(errorColor, layouts.single().layoutInput.style.color)
        assertEquals(before.syncedApiAddresses, saved().syncedApiAddresses)
        assertEquals(before.apiRoutesSyncedAt, saved().apiRoutesSyncedAt)
        assertEquals(before.apiRouteAddress, saved().apiRouteAddress)
        composeRule.onAllNodes(hasText("分流 1") and isSelectable())[0].assertIsEnabled()
        composeRule.onNodeWithText("从官方同步").assertIsEnabled()

        composeRule.onNodeWithContentDescription("重新测速").performClick()
        runBlocking { settings.setImageQuality(ImageQuality.HIGH) }
        composeRule.waitForIdle()
        assertEquals(1, fetches.get())

        composeRule.onNodeWithText("完成").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("完成").assertDoesNotExist()
        composeRule.runOnIdle { open = true }
        composeRule.waitForIdle()
        awaitFetches(2)
        composeRule.onNodeWithText("自动同步失败").assertExists()
        assertEquals(2, fetches.get())

        fetch = { OfficialAddresses }
        composeRule.onNodeWithText("从官方同步").performClick()
        awaitFetches(3)
        composeRule.onNodeWithText(Success).assertExists()
        assertEquals(3, fetches.get())
    }

    @Test
    fun automaticSyncLeavesRouteSelectionAndProbingAvailableWhileOnlyTheSyncButtonWaits() {
        configure(enabled = true, lastSyncedAt = now - 25 * Hour)
        val answer = CompletableDeferred<List<String>>()
        fetch = { answer.await() }
        showPicker()
        awaitFetches(1)

        composeRule.onNodeWithText("从官方同步").assertIsNotEnabled()
        composeRule.onNodeWithText("自动选择最快").assertIsEnabled()
        composeRule.onNodeWithContentDescription("重新测速").assertIsEnabled()
        composeRule.onAllNodes(isSelectable()).assertAll(isEnabled())

        composeRule.onAllNodes(hasText("分流 2") and isSelectable())[0].performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(defaultApiAddresses[1], saved().apiRouteAddress)
        composeRule.onAllNodes(hasText("分流 2") and isSelectable())[1].performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(RoutingMode.ROUTE_2, saved().imageRoutingMode)
        assertFalse(answer.isCompleted)

        val beforeRemeasuring = probes.get()
        composeRule.onNodeWithContentDescription("重新测速").performClick()
        composeRule.waitForIdle()
        assertTrue(probes.get() > beforeRemeasuring)
        composeRule.onNodeWithText("自动选择最快").assertIsEnabled()
        composeRule.onNodeWithText("从官方同步").assertIsNotEnabled()
        assertEquals(1, fetches.get())

        answer.complete(OfficialAddresses)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("从官方同步").assertIsEnabled()
        composeRule.onNodeWithContentDescription("重新测速").assertIsEnabled()
        composeRule.onNodeWithText(Success).assertDoesNotExist()
        composeRule.onNodeWithText("上次同步：${formatSyncTime(now)}").assertExists()
        assertEquals(now, saved().apiRoutesSyncedAt)
        assertEquals(defaultApiAddresses[1], saved().apiRouteAddress)
        assertEquals(RoutingMode.ROUTE_2, saved().imageRoutingMode)
        assertEquals(1, fetches.get())
    }

    @Test
    fun aUserRouteChoiceOwnsTheEmptyStatusLineSoALateAutomaticFailureIsNotShown() {
        configure(enabled = true, lastSyncedAt = now - 25 * Hour)
        val answer = CompletableDeferred<List<String>>()
        fetch = { answer.await() }
        showPicker()
        awaitFetches(1)

        composeRule.onAllNodes(hasText("分流 2") and isSelectable())[0].assertIsEnabled().performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(defaultApiAddresses[1], saved().apiRouteAddress)
        assertFalse(answer.isCompleted)

        answer.completeExceptionally(IOException("Offline"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("自动同步失败").assertDoesNotExist()
        composeRule.onNodeWithText("从官方同步").assertIsEnabled()
        composeRule.onAllNodes(hasText("分流 2") and isSelectable())[0].assertIsSelected()
        assertEquals(defaultApiAddresses[1], saved().apiRouteAddress)
        assertEquals(now - 25 * Hour, saved().apiRoutesSyncedAt)
        assertEquals(1, fetches.get())
    }

    @Test
    fun choosingTheFastestRouteClosesThePickerAndCancelsThePendingAutomaticSync() {
        configure(enabled = true, lastSyncedAt = now - 25 * Hour)
        val answer = CompletableDeferred<List<String>>()
        val cancelled = CompletableDeferred<Unit>()
        fetch = {
            try {
                answer.await()
            } finally {
                cancelled.complete(Unit)
            }
        }
        showPicker { route -> if ((route as? ApiRoute.Address)?.ip == defaultApiAddresses[1]) 10L else 100L }
        awaitFetches(1)

        composeRule.onNodeWithText("自动选择最快").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { cancelled.isCompleted }
        composeRule.onNodeWithText("完成").assertDoesNotExist()
        assertEquals(defaultApiAddresses[1], saved().apiRouteAddress)
        assertEquals(now - 25 * Hour, saved().apiRoutesSyncedAt)
        assertFalse(answer.isCompleted)
        assertEquals(1, fetches.get())
    }

    @Test
    fun anUnreachableNetworkIsNamedEvenWhenTheAutomaticSyncFailedToo() {
        configure(enabled = true, lastSyncedAt = now - 25 * Hour)
        fetch = { throw IOException("Offline") }
        showPicker(imageLatency = null) { null }
        awaitFetches(1)

        composeRule.onNodeWithText("暂无可用分流，请检查网络后重新测速").assertExists()
        composeRule.onNodeWithText("自动同步失败").assertDoesNotExist()
        composeRule.onNodeWithText("从官方同步").assertIsEnabled()
        assertEquals(1, fetches.get())
    }

    @Test
    fun aRouteSwitchedAfterMeasuringIsNotReplacedByTheAutomaticSyncResult() {
        configure(enabled = true, lastSyncedAt = now - 25 * Hour)
        val answer = CompletableDeferred<List<String>>()
        fetch = { answer.await() }
        showPicker { route ->
            when ((route as? ApiRoute.Address)?.ip) {
                defaultApiAddresses[0] -> null
                defaultApiAddresses[1] -> 10L
                else -> 100L
            }
        }
        awaitFetches(1)

        composeRule.onNodeWithText(Switched).assertExists()
        assertEquals(defaultApiAddresses[1], saved().apiRouteAddress)
        assertFalse(answer.isCompleted)

        answer.complete(OfficialAddresses)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(Switched).assertExists()
        composeRule.onNodeWithText(Success).assertDoesNotExist()
        composeRule.onNodeWithText("上次同步：${formatSyncTime(now)}").assertExists()
        assertEquals(OfficialAddresses, saved().syncedApiAddresses)
        assertEquals(defaultApiAddresses[1], saved().apiRouteAddress)
        assertEquals(1, fetches.get())
    }

    private fun configure(enabled: Boolean, lastSyncedAt: Long) = runBlocking {
        preferences.edit { it[booleanPreferencesKey("auto_sync_api_routes")] = enabled }
        settings.setSyncedApiAddresses(listOf(defaultApiAddresses.first()), lastSyncedAt)
    }

    private fun showPicker(imageLatency: Long? = 100L, apiLatency: (ApiRoute) -> Long? = { 100L }) {
        composeRule.setContent {
            PicaTheme(themeChoice = ThemeChoice.Brand) {
                errorColor = MaterialTheme.colorScheme.error
                if (open) {
                    RoutePickerDialog(
                        routing = routing,
                        settings = settings.settings,
                        measureApiRoute = { route -> probes.incrementAndGet(); apiLatency(route) },
                        measureImageRoute = { probes.incrementAndGet(); imageLatency },
                        onDismiss = { open = false },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun awaitFetches(count: Int) {
        composeRule.waitUntil(5_000) { fetches.get() == count }
        composeRule.waitForIdle()
    }

    private fun saved(): AppSettings = runBlocking { settings.settings.first() }

    private companion object {
        const val Hour = 3_600_000L
        const val Success = "已从官方同步 2 条分流"
        const val Switched = "当前分流不可用，已切换至分流 2"
        val OfficialAddresses = defaultApiAddresses.take(2)
    }
}
