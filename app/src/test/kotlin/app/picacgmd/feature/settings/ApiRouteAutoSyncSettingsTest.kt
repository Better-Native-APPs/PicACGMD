package app.picacgmd.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.session.NetworkConfigProvider
import app.picacgmd.core.domain.session.RoutingRepository
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.reader.setReaderTestNetwork
import app.picacgmd.testing.ComposeHostRule
import app.picacgmd.testing.MemoryPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApiRouteAutoSyncSettingsTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val preferences = MemoryPreferencesStore()
    private val settings = SettingsRepository(preferences, AppSettings(autoPunchIn = false))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val network = NetworkConfigProvider(settings.settings, scope)
    private val container = run {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        (unsafe.allocateInstance(AppContainer::class.java) as AppContainer).apply container@{
            AppContainer::class.java.getDeclaredField("settingsRepository").apply { isAccessible = true; set(this@container, settings) }
            AppContainer::class.java.getDeclaredField("routingRepository").apply {
                isAccessible = true
                set(this@container, RoutingRepository(settings, network, fetchAddresses = { error("Unexpected route sync") }))
            }
        }
    }
    private var visible by mutableStateOf(true)

    @After
    fun tearDown() {
        scope.cancel()
        network.close()
    }

    @Test
    fun theSwitchStartsOnShowsItsRuleAndSavesBothChoicesAcrossPageReopenings() {
        showPage()

        composeRule.onNodeWithText("自动同步分流").assertIsDisplayed().assertIsOn()
        composeRule.onNodeWithText("打开分流窗口时，距上次从官方同步超过 24 小时就自动同步一次").assertIsDisplayed()
        composeRule.onNodeWithText("自动同步分流").performClick()
        awaitStored(false)
        composeRule.onNodeWithText("自动同步分流").assertIsOff()

        composeRule.runOnIdle { visible = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("自动同步分流").assertIsOff().performClick()
        awaitStored(true)
        composeRule.onNodeWithText("自动同步分流").assertIsOn()
    }

    @Test
    fun aSavedOffChoiceIsShownWhenThePageOpens() {
        runBlocking { preferences.edit { it[AutoSyncKey] = false } }
        showPage()

        composeRule.onNodeWithText("自动同步分流").assertIsDisplayed().assertIsOff()
        assertEquals(false, preferences.data.value[AutoSyncKey])
    }

    private fun showPage() {
        setReaderTestNetwork(unmetered = true)
        composeRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                MaterialTheme {
                    if (visible) NetworkSettingsPage(onBack = { visible = false })
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun awaitStored(enabled: Boolean) {
        composeRule.waitUntil(5_000) { preferences.data.value[AutoSyncKey] == enabled }
        composeRule.waitForIdle()
    }

    private companion object {
        val AutoSyncKey = booleanPreferencesKey("auto_sync_api_routes")
    }
}
