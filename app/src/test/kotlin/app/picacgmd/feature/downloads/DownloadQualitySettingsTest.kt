package app.picacgmd.feature.downloads

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import app.picacgmd.core.data.db.PicaDatabase
import app.picacgmd.core.data.settings.ImageQuality
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.download.DownloadManager
import app.picacgmd.core.domain.download.DownloadManagerOwner
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.testing.ComposeHostRule
import app.picacgmd.testing.MemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sun.misc.Unsafe

@RunWith(RobolectricTestRunner::class)
@Config(application = DownloadQualityTestApplication::class, sdk = [29], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadQualitySettingsTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    @Test
    fun theDefaultRowAndBothDialogChoicesPersistWithoutChangingOnlineQuality() {
        DownloadQualityScreenFixture().use { fixture ->
            compose(fixture)
            composeRule.onNodeWithText("下载画质").assertIsDisplayed()
            composeRule.onNodeWithText("与图片质量相同").assertIsDisplayed()
            composeRule.onNodeWithText("下载画质").performClick()
            choice("与图片质量相同").assertIsSelected()
            choice("原图").assertIsNotSelected().performClick()

            waitForStoredChoice(fixture, "ORIGINAL")
            composeRule.onNode(isDialog()).assertDoesNotExist()
            composeRule.onNodeWithText("原图").assertIsDisplayed()
            composeRule.onNodeWithText("下载画质").performClick()
            choice("原图").assertIsSelected()
            choice("与图片质量相同").assertIsNotSelected().performClick()

            waitForStoredChoice(fixture, "SAME_AS_IMAGE")
            composeRule.onNode(isDialog()).assertDoesNotExist()
            composeRule.onNodeWithText("与图片质量相同").assertIsDisplayed()
            assertEquals(ImageQuality.LOW, runBlocking { fixture.settings.settings.first().imageQuality })
        }
    }

    @Test
    fun aStoredOriginalChoiceIsShownOnTheRowAndSelectedInTheDialog() {
        DownloadQualityScreenFixture(stored = "ORIGINAL").use { fixture ->
            compose(fixture)
            composeRule.onNodeWithText("下载画质").assertIsDisplayed()
            composeRule.onNodeWithText("原图").assertIsDisplayed()
            composeRule.onNodeWithText("下载画质").performClick()

            choice("原图").assertIsSelected()
            choice("与图片质量相同").assertIsNotSelected()
        }
    }

    private fun compose(fixture: DownloadQualityScreenFixture) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides fixture.container) {
                MaterialTheme { DownloadSettingsScreen(onBack = {}) }
            }
        }
        composeRule.waitForIdle()
    }

    private fun choice(label: String) = composeRule.onNode(hasText(label) and hasAnyAncestor(isDialog()))

    private fun waitForStoredChoice(fixture: DownloadQualityScreenFixture, choice: String) {
        composeRule.waitUntil(5_000) {
            fixture.preferences.data.value[stringPreferencesKey("download_quality")] == choice
        }
        composeRule.waitForIdle()
    }
}

class DownloadQualityTestApplication : Application(), DownloadManagerOwner {
    override lateinit var downloadManager: DownloadManager
}

private class DownloadQualityScreenFixture(stored: String? = null) : AutoCloseable {
    val preferences = MemoryPreferencesStore().apply {
        data.value = mutablePreferencesOf(stringPreferencesKey("image_quality") to "LOW").apply {
            if (stored != null) this[stringPreferencesKey("download_quality")] = stored
        }
    }
    val settings = SettingsRepository(preferences)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context = RuntimeEnvironment.getApplication() as DownloadQualityTestApplication
    private val database = Room.inMemoryDatabaseBuilder(context, PicaDatabase::class.java).build()
    val container: AppContainer = run {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        (unsafe.allocateInstance(AppContainer::class.java) as AppContainer).also { container ->
            AppContainer::class.java.getDeclaredField("settingsRepository").apply { isAccessible = true; set(container, settings) }
            AppContainer::class.java.getDeclaredField("defaultDownloadConcurrency").apply { isAccessible = true; setInt(container, 4) }
        }
    }

    init {
        context.downloadManager = DownloadManager(
            apiProvider = { error("Opening download settings must not request chapter metadata") },
            imageClientProvider = { error("Opening download settings must not download images") },
            dao = database.downloadDao(), settingsRepository = settings, context = context, scope = scope,
        )
    }

    override fun close() {
        scope.cancel()
        database.close()
    }
}
