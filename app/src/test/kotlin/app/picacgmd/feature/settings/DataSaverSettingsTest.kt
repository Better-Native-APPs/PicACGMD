package app.picacgmd.feature.settings

import android.net.ConnectivityManager
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.picacgmd.core.data.settings.ImageQuality
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.reader.ReaderDataSaverFixture
import app.picacgmd.reader.setReaderTestNetwork
import app.picacgmd.testing.ComposeHostRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DataSaverSettingsTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    @Test fun qualityRowExplainsTheDataSaverOverride() {
        setReaderTestNetwork(unmetered = true)
        val connectivity = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)

        ReaderDataSaverFixture().use { fixture ->
            composeRule.setContent {
                CompositionLocalProvider(LocalAppContainer provides fixture.container) {
                    MaterialTheme { NetworkSettingsPage(onBack = {}) }
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("省流量模式开启中，在线阅读使用低画质").assertIsDisplayed()
            composeRule.onNodeWithText("高").assertDoesNotExist()
        }
    }

    @Test fun qualityDialogStillSavesTheUserChoiceAndTheRowRestoresItWhenDataSaverTurnsOff() {
        setReaderTestNetwork(unmetered = true)
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)

        ReaderDataSaverFixture().use { fixture ->
            composeRule.setContent {
                CompositionLocalProvider(LocalAppContainer provides fixture.container) {
                    MaterialTheme { NetworkSettingsPage(onBack = {}) }
                }
            }
            composeRule.onNodeWithText("图片质量").performClick()
            composeRule.onNodeWithText("原图").performClick()
            composeRule.waitUntil(5_000) { runBlocking { fixture.settings.settings.first().imageQuality == ImageQuality.ORIGINAL } }
            composeRule.onNodeWithText("省流量模式开启中，在线阅读使用低画质").assertIsDisplayed()

            for (status in listOf(
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED,
            )) {
                shadowOf(connectivity).setRestrictBackgroundStatus(status)
                context.sendBroadcast(Intent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED))
                composeRule.waitForIdle()
                composeRule.onNodeWithText("原图").assertIsDisplayed()
                composeRule.onNodeWithText("省流量模式开启中，在线阅读使用低画质").assertDoesNotExist()
            }
        }
    }
}
