package app.picacgmd.reader

import android.net.ConnectivityManager
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.picacgmd.core.data.settings.ImageQuality
import app.picacgmd.di.LocalAppContainer
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
import kotlin.test.assertEquals
import app.picacgmd.feature.reader.R as ReaderR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderDataSaverDestinationTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    @Test fun dataSaverRequestsLowQualityWithoutChangingTheStoredHighChoice() {
        setReaderTestNetwork(unmetered = true)
        val connectivity = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)

        ReaderDataSaverFixture(ImageQuality.HIGH).use { fixture ->
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalAppContainer provides fixture.container,
                    LocalViewModelStoreOwner provides fixture,
                ) {
                    MaterialTheme {
                        OnlineReaderDestination("comic", 1, 0, {}, {}, {}, {}, {})
                    }
                }
            }
            try {
                composeRule.waitUntil(5_000) { fixture.requests.any { it.url.encodedPath.endsWith("/pages") } }
            } catch (exception: Throwable) {
                throw AssertionError("Requests: ${fixture.requests.map { it.url.encodedPath }}\n${composeRule.onRoot().printToString()}", exception)
            }
            assertEquals("low", fixture.requests.single { it.url.encodedPath.endsWith("/pages") }.header("image-quality"))
            assertEquals(ImageQuality.HIGH, runBlocking { fixture.settings.settings.first().imageQuality })
        }
    }

    @Test fun togglingDataSaverKeepsLoadedPagesAndUsesLowForTheNextChapter() {
        setReaderTestNetwork(unmetered = true)
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)

        ReaderDataSaverFixture(ImageQuality.ORIGINAL).use { fixture ->
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalAppContainer provides fixture.container,
                    LocalViewModelStoreOwner provides fixture,
                ) {
                    MaterialTheme { OnlineReaderDestination("comic", 1, 0, {}, {}, {}, {}, {}) }
                }
            }
            composeRule.waitUntil(5_000) { fixture.requests.any { it.url.encodedPath.endsWith("/pages") } }
            assertEquals("original", fixture.requests.last().header("image-quality"))

            shadowOf(connectivity).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
            context.sendBroadcast(Intent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED))
            composeRule.waitForIdle()
            assertEquals(1, fixture.requests.count { it.url.encodedPath.endsWith("/pages") })

            composeRule.onNodeWithContentDescription(context.getString(ReaderR.string.reader_chapters)).performClick()
            composeRule.onNodeWithText("第 2 话").performClick()
            composeRule.waitUntil(5_000) { fixture.requests.any { it.url.encodedPath == "/comics/comic/order/2/pages" } }
            assertEquals("low", fixture.requests.last().header("image-quality"))
            assertEquals(ImageQuality.ORIGINAL, runBlocking { fixture.settings.settings.first().imageQuality })
        }
    }
}
