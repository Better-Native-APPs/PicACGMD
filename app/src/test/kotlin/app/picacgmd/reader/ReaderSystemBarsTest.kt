package app.picacgmd.reader

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.picacgmd.core.data.settings.FullScreenDisplay
import app.picacgmd.feature.reader.ui.AppSystemBars
import app.picacgmd.feature.reader.ui.ReaderSystemBars
import app.picacgmd.testing.ComposeHostRule
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@Suppress("DEPRECATION")
class ReaderSystemBarsTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    @Test
    fun outsideReaderEveryStoredModeLeavesBothBarsVisible() {
        val fixture = Fixture()
        host(fixture)
        FullScreenDisplay.entries.forEach { mode ->
            composeRule.runOnIdle { fixture.mode = mode }
            assertBars(fixture, statusHidden = false, navigationHidden = false)
        }
    }

    @Test
    fun readerHidesOnlyTheSelectedBarsAndBackRestoresBoth() {
        val fixture = Fixture()
        host(fixture)
        composeRule.runOnIdle { fixture.navigation.navigate("reader") }
        val expected = listOf(false to false, true to false, false to true, true to true)
        FullScreenDisplay.entries.zip(expected).forEach { (mode, hidden) ->
            composeRule.runOnIdle { fixture.mode = mode }
            assertBars(fixture, statusHidden = hidden.first, navigationHidden = hidden.second)
        }
        composeRule.runOnIdle { fixture.navigation.popBackStack() }
        assertBars(fixture, statusHidden = false, navigationHidden = false)
    }

    @Test
    fun openingSettingsShowsBothBarsAndReturningReappliesReaderMode() {
        val fixture = Fixture(FullScreenDisplay.HIDE_STATUS_BAR)
        host(fixture)
        composeRule.runOnIdle { fixture.navigation.navigate("reader") }
        assertBars(fixture, statusHidden = true, navigationHidden = false)
        composeRule.runOnIdle { fixture.navigation.navigate("settings") }
        assertBars(fixture, statusHidden = false, navigationHidden = false)
        composeRule.runOnIdle { fixture.navigation.popBackStack() }
        assertBars(fixture, statusHidden = true, navigationHidden = false)
    }

    @Test
    fun cancelledPredictiveBackKeepsTheReaderMode() {
        val fixture = Fixture(FullScreenDisplay.HIDE_NAVIGATION)
        host(fixture)
        composeRule.runOnIdle { fixture.navigation.navigate("reader") }
        assertBars(fixture, statusHidden = false, navigationHidden = true)
        startPredictiveBack(fixture)
        composeRule.runOnIdle { fixture.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        assertBars(fixture, statusHidden = false, navigationHidden = true)
        composeRule.runOnIdle {
            assertEquals("reader", fixture.navigation.currentBackStackEntry?.destination?.route)
        }
    }

    @Test
    fun committedPredictiveBackRestoresBothBars() {
        val fixture = Fixture(FullScreenDisplay.HIDE_SYSTEM_BARS)
        host(fixture)
        composeRule.runOnIdle { fixture.navigation.navigate("reader") }
        assertBars(fixture, statusHidden = true, navigationHidden = true)
        startPredictiveBack(fixture)
        composeRule.runOnIdle { fixture.activity.onBackPressedDispatcher.onBackPressed() }
        assertBars(fixture, statusHidden = false, navigationHidden = false)
        composeRule.runOnIdle {
            assertEquals("detail", fixture.navigation.currentBackStackEntry?.destination?.route)
        }
    }

    @Test
    fun savedStateRecreationReappliesReaderModeAndExitRestoresBothBars() {
        val fixture = Fixture(FullScreenDisplay.HIDE_STATUS_BAR)
        val restoration = StateRestorationTester(composeRule)
        host(fixture, restoration)
        composeRule.runOnIdle { fixture.navigation.navigate("reader") }
        assertBars(fixture, statusHidden = true, navigationHidden = false)
        restoration.emulateSavedInstanceStateRestore()
        assertBars(fixture, statusHidden = true, navigationHidden = false)
        composeRule.runOnIdle { fixture.navigation.popBackStack() }
        assertBars(fixture, statusHidden = false, navigationHidden = false)
    }

    @Test
    fun appThemeCannotOverrideReaderIconsAndExitUsesTheLatestTheme() {
        val fixture = Fixture(FullScreenDisplay.HIDE_STATUS_BAR)
        host(fixture)
        composeRule.runOnIdle { fixture.navigation.navigate("reader") }
        composeRule.runOnIdle { fixture.lightAppBackground = true }
        assertBars(fixture, statusHidden = true, navigationHidden = false)
        assertLightIcons(fixture, lightBackground = false)
        composeRule.runOnIdle { fixture.navigation.popBackStack() }
        assertBars(fixture, statusHidden = false, navigationHidden = false)
        assertLightIcons(fixture, lightBackground = true)
    }

    private fun host(fixture: Fixture, restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            val activity = LocalView.current.context.findActivity()
            val navigation = rememberNavController()
            val lightBackground = fixture.lightAppBackground
            SideEffect {
                fixture.activity = activity
                fixture.navigation = navigation
            }
            DisposableEffect(lightBackground) {
                activity.enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { !lightBackground },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { !lightBackground },
                )
                onDispose { }
            }
            AppSystemBars(lightBackground = lightBackground) {
                MaterialTheme {
                    NavHost(navigation, startDestination = "detail") {
                        composable("detail") { Text(fixture.mode.name) }
                        composable("reader") {
                            ReaderSystemBars(fixture.mode.toSystemBarsMode(), lightBackground = false)
                            Text("阅读")
                        }
                        composable("settings") { Text("设置") }
                    }
                }
            }
        }
        if (restoration == null) composeRule.setContent(content) else restoration.setContent(content)
    }

    private fun startPredictiveBack(fixture: Fixture) {
        composeRule.runOnIdle {
            fixture.activity.onBackPressedDispatcher.dispatchOnBackStarted(
                BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT),
            )
        }
        composeRule.runOnIdle {
            fixture.activity.onBackPressedDispatcher.dispatchOnBackProgressed(
                BackEventCompat(100f, 200f, 0.6f, BackEventCompat.EDGE_LEFT),
            )
        }
    }

    private fun assertBars(fixture: Fixture, statusHidden: Boolean, navigationHidden: Boolean) {
        composeRule.runOnIdle {
            val flags = fixture.activity.window.decorView.systemUiVisibility
            assertEquals(statusHidden, flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0, "status bar")
            assertEquals(navigationHidden, flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0, "navigation bar")
        }
    }

    private fun assertLightIcons(fixture: Fixture, lightBackground: Boolean) {
        composeRule.runOnIdle {
            val flags = fixture.activity.window.decorView.systemUiVisibility
            assertEquals(lightBackground, flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0, "status bar icons")
            assertEquals(lightBackground, flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0, "navigation bar icons")
        }
    }

    private class Fixture(mode: FullScreenDisplay = FullScreenDisplay.OFF) {
        var mode by mutableStateOf(mode)
        var lightAppBackground by mutableStateOf(false)
        lateinit var activity: ComponentActivity
        lateinit var navigation: NavHostController
    }
}

private tailrec fun Context.findActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No Compose host activity")
}
