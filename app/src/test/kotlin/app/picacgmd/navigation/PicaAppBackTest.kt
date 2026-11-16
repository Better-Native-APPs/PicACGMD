package app.picacgmd.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.download.DownloadManager
import app.picacgmd.core.domain.download.DownloadManagerOwner
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.network.PicaApi
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.testing.ComposeHostRule
import app.picacgmd.ui.PicaApp
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = BackTestApplication::class, qualifiers = "w412dp-h860dp")
class PicaAppBackTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private lateinit var navController: NavHostController
    private lateinit var dispatcher: NavigationEventDispatcher
    private lateinit var activity: ComponentActivity
    private val input = DirectNavigationEventInput()
    private var destinationChanges = 0

    @After
    fun closeContainer() {
        (RuntimeEnvironment.getApplication() as BackTestApplication).close()
    }

    @Test
    fun compactMeBackStartedInsideTheHostWindowWaitsForTheNextGesture() {
        host()
        enterMe(48)
        val entryId = navController.currentBackStackEntry?.id
        val changes = destinationChanges
        completeBack()
        assertCurrent<MeRoute>()
        assertEquals(changes, destinationChanges)
        assertEquals(entryId, navController.currentBackStackEntry?.id)
        assertReturnsToDiscover()
    }

    @Test
    fun compactMeAtRestBackReturnsToDiscover() {
        host()
        enterMe(800)
        assertReturnsToDiscover()
    }

    @Test
    @Config(qualifiers = "w800dp-h860dp")
    fun railMeBackDuringTabEntryReturnsToDiscover() {
        host()
        selectTab(TopLevelDestination.Me, 48)
        assertReturnsToDiscover()
    }

    @Test
    @Config(qualifiers = "w800dp-h860dp")
    fun railMeAtRestBackReturnsToDiscover() {
        host()
        selectTab(TopLevelDestination.Me)
        assertReturnsToDiscover()
    }

    @Test
    fun compactStartTabBackIsHandledByTheSystem() {
        host()
        assertSystemBack()
    }

    @Test
    @Config(qualifiers = "w800dp-h860dp")
    fun railStartTabBackIsHandledByTheSystem() {
        host()
        assertSystemBack()
    }

    @Test
    fun compactBookshelfBackReturnsToTheStartTab() {
        host()
        selectTab(TopLevelDestination.Bookshelf)
        assertCurrent<BookshelfRoute>()
        assertReturnsToDiscover()
    }

    @Test
    @Config(qualifiers = "w800dp-h860dp")
    fun railBookshelfBackReturnsToTheStartTab() {
        host()
        selectTab(TopLevelDestination.Bookshelf)
        assertCurrent<BookshelfRoute>()
        assertReturnsToDiscover()
    }

    @Test
    fun compactDownloadsBackReturnsToTheStartTab() {
        host()
        selectTab(TopLevelDestination.Downloads)
        assertCurrent<DownloadsRoute>()
        assertReturnsToDiscover()
    }

    @Test
    @Config(qualifiers = "w800dp-h860dp")
    fun railDownloadsBackReturnsToTheStartTab() {
        host()
        selectTab(TopLevelDestination.Downloads)
        assertCurrent<DownloadsRoute>()
        assertReturnsToDiscover()
    }

    @Test
    fun pushedPageBackReturnsToItsSourceTabRatherThanExiting() {
        host()
        selectTab(TopLevelDestination.Downloads)
        val sourceId = navController.currentBackStackEntry?.id
        composeRule.runOnIdle { navController.navigate(DownloadSettingsRoute) }
        composeRule.mainClock.advanceTimeBy(800)
        assertCurrent<DownloadSettingsRoute>()
        val changes = destinationChanges
        completeBack()
        assertCurrent<DownloadsRoute>()
        assertEquals(sourceId, navController.currentBackStackEntry?.id)
        assertEquals(changes + 1, destinationChanges)
        composeRule.runOnIdle { assertFalse(activity.isFinishing) }
    }

    private fun host() {
        val application = RuntimeEnvironment.getApplication() as BackTestApplication
        composeRule.setContent {
            activity = LocalActivity.current as ComponentActivity
            dispatcher = requireNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
            navController = rememberNavController()
            DisposableEffect(navController) {
                val listener = NavController.OnDestinationChangedListener { _, _, _ -> destinationChanges++ }
                navController.addOnDestinationChangedListener(listener)
                onDispose { navController.removeOnDestinationChangedListener(listener) }
            }
            CompositionLocalProvider(LocalAppContainer provides application.container) {
                PicaTheme(themeChoice = ThemeChoice.Brand, darkMode = DarkMode.Light) {
                    PicaApp(navController = navController)
                }
            }
        }
        composeRule.runOnIdle { dispatcher.addInput(input) }
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeByFrame()
    }

    private fun enterMe(millis: Long) {
        composeRule.runOnIdle { navController.navigate(MeGraph) }
        composeRule.mainClock.advanceTimeBy(millis)
        assertCurrent<MeRoute>()
    }

    private fun selectTab(destination: TopLevelDestination, millis: Long = 800) {
        composeRule.runOnIdle { navController.navigateToTopLevel(destination) }
        composeRule.mainClock.advanceTimeBy(millis)
    }

    private fun completeBack() {
        composeRule.runOnIdle { input.backStarted(backEvent(0f)) }
        composeRule.runOnIdle { input.backProgressed(backEvent(0.35f)) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { input.backCompleted() }
        composeRule.mainClock.advanceTimeBy(1_000)
    }

    private fun assertReturnsToDiscover() {
        val changes = destinationChanges
        completeBack()
        assertCurrent<DiscoverRoute>()
        assertEquals(changes + 1, destinationChanges)
        composeRule.runOnIdle { assertFalse(activity.isFinishing) }
    }

    private fun assertSystemBack() {
        assertCurrent<DiscoverRoute>()
        val changes = destinationChanges
        composeRule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(changes, destinationChanges)
        composeRule.runOnIdle { assertTrue(activity.isFinishing) }
    }

    private inline fun <reified T : Any> assertCurrent() {
        composeRule.runOnIdle {
            assertTrue(navController.currentDestination?.hasRoute<T>() == true)
        }
    }

    private fun backEvent(progress: Float) = NavigationEvent(
        swipeEdge = NavigationEvent.EDGE_LEFT,
        progress = progress,
        touchY = 200f,
    )
}

/** Real app wiring with a failing in-process API boundary and no startup work. */
class BackTestApplication : Application(), DownloadManagerOwner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val container: AppContainer by lazy {
        AppContainer(this).also { container ->
            // The container is concrete; replace its session boundary before composing any screen.
            val offlineApi: suspend () -> PicaApi = { throw IOException("Offline navigation fixture") }
            SessionManager::class.java.getDeclaredField("apiProvider").apply {
                isAccessible = true
                set(container.sessionManager, offlineApi)
            }
        }
    }
    override val downloadManager: DownloadManager by lazy {
        DownloadManager(
            apiProvider = { error("The navigation fixture must not download") },
            imageClientProvider = { error("The navigation fixture must not load images") },
            dao = container.database.downloadDao(),
            settingsRepository = container.settingsRepository,
            context = this,
            scope = scope,
        )
    }

    fun close() {
        scope.cancel()
        container.close()
    }
}
