package app.picacgmd.feature.detail

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.picacgmd.R
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.session.NetworkConfigProvider
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.PicaImage
import app.picacgmd.testing.ComposeHostRule
import app.picacgmd.testing.failureResponse
import app.picacgmd.testing.respondWith
import app.picacgmd.testing.successResponse
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The cover on the comic page opens large over the page, and back, a tap or 「关闭」 puts it away again. */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = DetailTestApplication::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicDetailCoverTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val fixture = DetailTestFixture()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var activity: ComponentActivity
    private var leaves = 0

    @Before
    fun servePictures() {
        System.setProperty(RENDER_MODE, "hardware")
        val context = RuntimeEnvironment.getApplication()
        // The page turns a comic's picture into an address through the network settings.
        AppContainer::class.java.getDeclaredField("networkConfigProvider").apply { isAccessible = true }
            .set(fixture.environment.container, NetworkConfigProvider(fixture.environment.settings.settings, scope))
        // Every picture is this one flat colour, in the shape of a cover, so it can be found on the screen.
        val picture = Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888).apply { eraseColor(COVER_COLOUR) }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context)
                .components {
                    add(
                        object : Interceptor {
                            override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                                SuccessResult(picture.asImage(), chain.request, DataSource.MEMORY)
                        },
                    )
                }
                .build(),
        )
    }

    @After
    fun close() {
        SingletonImageLoader.reset()
        System.clearProperty(RENDER_MODE)
        scope.cancel()
        fixture.close()
    }

    @Test
    fun theCoverIsAButtonThatOpensItFittedToTheScreenAndBackReturnsToThePageAsItWas() {
        compose()
        val title = bounds(detailComic.title)
        val chapters = bounds(detailString(R.string.detail_chapters_forward))
        cover().assertIsDisplayed().assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertTrue(coverShareOfMiddleRow() < 0.1f, "The cover already fills the screen before it is opened")

        cover().performClick()
        composeRule.waitForIdle()

        closeButton().assertIsDisplayed().assertHasClickAction()
        assertTrue(coverShareOfMiddleRow() > 0.95f, "The opened cover does not span the screen")
        // Nothing of the page can be reached while the cover is over it.
        composeRule.onNodeWithText(detailString(R.string.detail_start_reading)).assertDoesNotExist()

        pressBack()

        closeButton().assertDoesNotExist()
        assertEquals(0, leaves, "Back left the page instead of closing the cover")
        assertEquals(title, bounds(detailComic.title))
        assertEquals(chapters, bounds(detailString(R.string.detail_chapters_forward)))
        composeRule.onNodeWithText(detailString(R.string.detail_start_reading)).assertIsDisplayed()
        cover().assertIsDisplayed()
        assertTrue(coverShareOfMiddleRow() < 0.1f, "The cover is still over the page")

        pressBack()
        assertEquals(1, leaves)
    }

    @Test
    fun theCloseButtonPutsTheCoverAway() {
        compose()
        cover().performClick()

        closeButton().performClick()

        closeButton().assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_start_reading)).assertIsDisplayed()
        assertEquals(0, leaves)
    }

    @Test
    fun aTapOnThePicturePutsTheCoverAway() {
        compose()
        cover().performClick()
        composeRule.waitForIdle()

        // The opened picture is named after the comic.
        composeRule.onNodeWithContentDescription(detailComic.title).assertIsDisplayed().performClick()
        // A single tap is only certain once a second one has not followed.
        composeRule.mainClock.advanceTimeBy(1_000)

        closeButton().assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_start_reading)).assertIsDisplayed()
        assertEquals(0, leaves)
    }

    @Test
    fun aDoubleTapEnlargesThePictureInsteadOfClosingItAndAnotherFitsItAgain() {
        compose()
        cover().performClick()
        composeRule.waitForIdle()
        assertFalse(coverReachesTheTopOfTheScreen(), "The fitted cover leaves no room above it on a tall screen")

        composeRule.onNodeWithContentDescription(detailComic.title).performTouchInput { doubleClick() }
        composeRule.mainClock.advanceTimeBy(1_000)

        closeButton().assertIsDisplayed()
        assertTrue(coverReachesTheTopOfTheScreen(), "The double tap did not enlarge the cover")

        composeRule.onNodeWithContentDescription(detailComic.title).performTouchInput { doubleClick() }
        composeRule.mainClock.advanceTimeBy(1_000)

        closeButton().assertIsDisplayed()
        assertFalse(coverReachesTheTopOfTheScreen(), "The second double tap did not fit the cover again")
    }

    @Test
    fun aBackSwipeThatIsLetGoKeepsTheCoverOpenAndTheNextBackClosesIt() {
        compose()
        cover().performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 400f, 0.6f, BackEventCompat.EDGE_LEFT))
        }
        closeButton().assertIsDisplayed()
        composeRule.runOnIdle { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        composeRule.mainClock.advanceTimeBy(1_000)

        closeButton().assertIsDisplayed()
        assertTrue(coverShareOfMiddleRow() > 0.95f, "The cover did not come back to full size")
        assertEquals(0, leaves)

        pressBack()
        closeButton().assertDoesNotExist()
        assertEquals(0, leaves)
    }

    @Test
    fun aComicWithoutACoverHasNothingToOpen() {
        compose(detailComic)

        composeRule.onAllNodesWithContentDescription(detailString(R.string.detail_cover_view)).fetchSemanticsNodes()
            .let { assertTrue(it.isEmpty(), "A comic without a cover offers to open one") }
    }

    private fun cover() = composeRule.onNodeWithContentDescription(detailString(R.string.detail_cover_view))

    private fun closeButton() = composeRule.onNodeWithContentDescription(detailString(R.string.detail_cover_close))

    private fun bounds(text: String): Rect = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun pressBack() {
        composeRule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    /** How much of the screen's middle row shows the cover picture, 0..1. */
    private fun coverShareOfMiddleRow(): Float = coverShareOfRow(0.5f)

    /** Just below the close button's row; a fitted cover on this tall screen starts further down. */
    private fun coverReachesTheTopOfTheScreen(): Boolean = coverShareOfRow(0.15f) > 0.95f

    private fun coverShareOfRow(down: Float): Float {
        composeRule.waitForIdle()
        val screen = checkNotNull(ShadowInstrumentation.getInstrumentation().uiAutomation.takeScreenshot()) { "No screenshot" }
        val row = IntArray(screen.width).also { screen.getPixels(it, 0, screen.width, 0, (screen.height * down).toInt(), screen.width, 1) }
        return row.count { Color.red(it) > 200 && Color.green(it) < 60 && Color.blue(it) > 200 } / screen.width.toFloat()
    }

    private fun compose(comic: ComicInfo = coverComic) {
        fixture.pica.respondWith { request ->
            when (request.url.encodedPath) {
                "/comics/${comic.id}" -> successResponse("{\"comic\":${Json.encodeToString(comic)}}")
                "/comics/${comic.id}/eps" -> successResponse("{\"eps\":{\"docs\":${Json.encodeToString(detailChapters(2))},\"page\":1,\"pages\":1}}")
                "/comics/${comic.id}/recommendation" -> successResponse("{\"comics\":[]}")
                "/comics/${comic.id}/comments" -> successResponse("{\"comments\":{\"docs\":[],\"page\":1,\"pages\":1}}")
                else -> failureResponse()
            }
        }
        (RuntimeEnvironment.getApplication() as DetailTestApplication).downloadManager = fixture.environment.downloads
        composeRule.setContent {
            activity = LocalActivity.current as ComponentActivity
            BackHandler { leaves++ }
            fixture.Content {
                ComicDetailScreen(
                    comicId = comic.id,
                    onBack = {},
                    onRead = { _, _, _ -> },
                    onDownloadChapters = { _, _ -> },
                    onCategoryClick = {},
                    onTagClick = {},
                    onAuthorClick = {},
                    onTeamClick = {},
                    onCreatorClick = { _, _ -> },
                    onComicClick = {},
                    onOpenComments = {},
                )
            }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(detailString(R.string.detail_chapters_forward)).fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val RENDER_MODE = "robolectric.pixelCopyRenderMode"
        const val COVER_COLOUR = Color.MAGENTA
        val coverComic = detailComic.copy(thumb = PicaImage(fileServer = "https://pictures.invalid", path = "cover.jpg"))
    }
}
