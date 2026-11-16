package app.picacgmd.feature.detail

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import app.picacgmd.R
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.domain.repository.ReadingProgress
import app.picacgmd.core.domain.repository.containsKeyword
import app.picacgmd.core.network.Comment
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.Ep
import app.picacgmd.core.network.UserProfile
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.testing.ComposeHostRule
import app.picacgmd.testing.failureResponse
import app.picacgmd.testing.respondWith
import app.picacgmd.testing.successResponse
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowInstrumentation
import kotlin.test.Test

/**
 * Draws the comic detail page in the states worth looking at and writes each as a PNG, for comparing
 * with the design by eye. Nothing is asserted, and nothing runs unless the environment variable
 * `DETAIL_SHOTS_DIR` names the folder the pictures go to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = DetailTestApplication::class, sdk = [36], qualifiers = PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicDetailRenderTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val shotsDir: File? = System.getenv("DETAIL_SHOTS_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
    private val application get() = RuntimeEnvironment.getApplication() as DetailTestApplication
    private val fixture by lazy { DetailTestFixture() }

    @Before
    fun onlyWhenAskedFor() {
        assumeTrue("DETAIL_SHOTS_DIR is not set", shotsDir != null)
        // Windows are drawn the way a device draws them, shadows included.
        System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
        application.downloadManager = fixture.environment.downloads
    }

    @After
    fun close() {
        if (shotsDir != null) fixture.close()
    }

    @Test
    @Config(qualifiers = "w412dp-h2200dp-xhdpi")
    fun wholePageAt412Light() {
        body()
        shot("01-page-412-light")
    }

    @Test
    fun firstScreenAt412Light() {
        body()
        shot("02-first-screen-412-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h2900dp-xhdpi")
    fun wholePageAt360WithLargeFontDark() {
        body(dark = true, fontScale = 1.3f)
        shot("03-page-360-font130-dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun firstScreenAt360WithLargeFontDark() {
        body(dark = true, fontScale = 1.3f)
        shot("04-first-screen-360-font130-dark")
    }

    @Test
    @Config(qualifiers = "w412dp-h1500dp-xhdpi")
    fun oneChapterWithoutTranslator() {
        body(
            comic = COMIC.copy(chineseTeam = "", description = DESCRIPTION_SHORT, epsCount = 1, pagesCount = 24),
            state = STATE.copy(eps = listOf(Ep(id = "only", title = "第 1 话", order = 1))),
            library = LibraryState(),
        )
        shot("05-one-chapter-no-translator-412-light")
    }

    @Test
    fun titleMenu() {
        body()
        composeRule.onNodeWithText(TITLE).performTouchInput { longClick() }
        shot("06-title-menu-412-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun titleMenuAt360WithLargeFontDark() {
        body(dark = true, fontScale = 1.3f)
        composeRule.onNodeWithText(TITLE).performTouchInput { longClick() }
        shot("07-title-menu-360-font130-dark")
    }

    @Test
    fun titlePressedWithoutConfirmation() {
        body(settings = AppSettings.DEFAULT.copy(confirmCopy = false))
        // Taken with the finger still down, after the long press has copied the name.
        composeRule.onNodeWithText(TITLE).performTouchInput { down(center) }
        shot("08-title-held-no-confirm-412-light")
        composeRule.onNodeWithText(TITLE).performTouchInput { up() }
    }

    @Test
    fun collapsedTopBar() {
        body()
        scrollToChapters()
        shot("09-collapsed-top-bar-412-light")
        composeRule.onNodeWithText(TITLE).performTouchInput { longClick() }
        shot("10-collapsed-top-bar-title-menu-412-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun collapsedTopBarAt360WithLargeFontDark() {
        body(dark = true, fontScale = 1.3f)
        scrollToChapters()
        shot("11-collapsed-top-bar-360-font130-dark")
    }

    @Test
    @Config(qualifiers = KEYWORDS_IN_VIEW)
    fun tagMenu() {
        body()
        composeRule.onNodeWithText("猫").performTouchInput { longClick() }
        shot("12-menu-tag-412-light")
    }

    @Test
    @Config(qualifiers = KEYWORDS_IN_VIEW)
    fun favouriteTagMenu() {
        body()
        composeRule.onNodeWithText("列车").performTouchInput { longClick() }
        shot("13-menu-tag-favourite-412-light")
    }

    @Test
    @Config(qualifiers = KEYWORDS_IN_VIEW)
    fun blockedCategoryMenu() {
        body()
        composeRule.onNodeWithText("短篇合集").performTouchInput { longClick() }
        shot("14-menu-category-blocked-412-light")
    }

    @Test
    @Config(qualifiers = KEYWORDS_IN_VIEW)
    fun categoryMenu() {
        body()
        composeRule.onNodeWithText("奇幻").performTouchInput { longClick() }
        shot("15-menu-category-412-light")
    }

    @Test
    @Config(qualifiers = KEYWORDS_IN_VIEW)
    fun blockedFavouriteTagMenu() {
        body(hidden = listOf("短篇合集", "猫", "星空"), state = STATE.copy(hiddenByRule = true))
        composeRule.onNodeWithText("星空").performTouchInput { longClick() }
        shot("16-menu-tag-blocked-and-favourite-412-light")
    }

    @Test
    @Config(qualifiers = KEYWORDS_IN_VIEW)
    fun tagMenuWithTagFavouritesOff() {
        body(showTagFavourites = false)
        composeRule.onNodeWithText("猫").performTouchInput { longClick() }
        shot("17-menu-tag-favourites-off-412-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xhdpi")
    fun tagMenuAt360WithLargeFontDark() {
        body(dark = true, fontScale = 1.3f, hidden = listOf("短篇合集", "猫", "星空"), state = STATE.copy(hiddenByRule = true))
        composeRule.onNodeWithText("手绘水彩").performTouchInput { longClick() }
        shot("18-marks-notice-menu-360-font130-dark")
    }

    @Test
    fun hiddenNotice() {
        body(state = STATE.copy(hiddenByRule = true))
        shot("19-notice-412-light")
    }

    @Test
    fun longDescriptionExpanded() {
        body()
        composeRule.onNodeWithText("展开").performClick()
        shot("20-description-expanded-412-light")
    }

    @Test
    @Config(qualifiers = "w412dp-h1200dp-xhdpi")
    fun longNamesAndAuthorSearchOff() {
        body(comic = LONG_NAMES, settings = AppSettings.DEFAULT.copy(showAuthorSearch = false))
        shot("21-people-long-names-412-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h1200dp-xhdpi")
    fun longNamesAt360WithLargeFontDark() {
        body(comic = LONG_NAMES, dark = true, fontScale = 1.3f)
        shot("22-people-long-names-360-font130-dark")
    }

    @Test
    fun uploaderOnlyAndDownloadNotAllowed() {
        body(comic = COMIC.copy(author = "", chineseTeam = "", description = "", allowDownload = false))
        shot("23-uploader-only-no-description-412-light")
    }

    @Test
    @Config(qualifiers = "w412dp-h3300dp-xhdpi")
    fun chaptersReversedAndExpanded() {
        body()
        composeRule.onNodeWithContentDescription("章节顺序：正序").performClick()
        pump()
        composeRule.onNodeWithText("展开全部 46 话").performClick()
        shot("24-chapters-reversed-expanded-412-light")
    }

    /** The page as the app shows it, loaded through the view model, so the message sits where it really does. */
    @Test
    fun blockingATagShowsTheMessageAndTheNotice() {
        serve()
        composeRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides fixture.environment.container) {
                PicaTheme(themeChoice = ThemeChoice.Brand, darkMode = DarkMode.Light) {
                    ComicDetailScreen(
                        comicId = COMIC.id,
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
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()
        }
        scrollToChapters()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(detailString(R.string.detail_chapters_forward)).fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(3)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("猫").performTouchInput { longClick() }
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_block)).performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { fixture.environment.settings.settings.first().hiddenKeywords }.containsKeyword("猫")
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(detailString(R.string.detail_keyword_undo)).fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        shot("25-blocked-message-undo-412-light")
        composeRule.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        shot("26-blocked-notice-and-message-412-light")
    }

    private fun body(
        comic: ComicInfo = COMIC,
        settings: AppSettings = AppSettings.DEFAULT,
        state: DetailState = STATE,
        library: LibraryState = LIBRARY,
        hidden: List<String> = listOf("短篇合集"),
        showTagFavourites: Boolean = true,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppContainer provides fixture.environment.container,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                PicaTheme(themeChoice = ThemeChoice.Brand, darkMode = if (dark) DarkMode.Dark else DarkMode.Light) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        DetailBody(
                            comic = comic,
                            preferences = settings,
                            state = state.copy(comic = comic),
                            library = library,
                            onBack = {},
                            onRead = { _, _ -> },
                            onLike = {},
                            onFavourite = {},
                            onSubscribe = {},
                            onDownload = {},
                            onCategoryClick = {},
                            onTagClick = {},
                            onAuthorClick = {},
                            onTeamClick = {},
                            onCreatorClick = { _, _ -> },
                            onComicClick = {},
                            onOpenComments = {},
                            onRetryEps = {},
                            hiddenKeywords = hidden,
                            onToggleKeyword = {},
                            showTagFavourites = showTagFavourites,
                            favouriteTags = setOf("列车", "星空"),
                            onToggleTagFavourite = {},
                        )
                    }
                }
            }
        }
        composeRule.mainClock.autoAdvance = false
        pump()
    }

    private fun scrollToChapters() {
        composeRule.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(6)
        pump()
    }

    /** Lets animations run out and delivers what was posted to the main thread meanwhile. */
    private fun pump(millis: Long = 900) {
        composeRule.mainClock.advanceTimeBy(millis)
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.mainClock.advanceTimeByFrame()
    }

    /** Every window on the display in one picture; a picture taller than a phone is also cut into phone-high parts. */
    private fun shot(name: String) {
        pump()
        val dir = checkNotNull(shotsDir).apply { mkdirs() }
        val screen = checkNotNull(ShadowInstrumentation.getInstrumentation().uiAutomation.takeScreenshot()) { "No screenshot" }
        write(screen, File(dir, "$name.png"))
        val part = (PART_HEIGHT_DP * application.resources.displayMetrics.density).toInt()
        if (screen.height > part * 3 / 2) {
            var top = 0
            var index = 1
            while (top < screen.height) {
                val height = minOf(part, screen.height - top)
                write(Bitmap.createBitmap(screen, 0, top, screen.width, height), File(dir, "$name-part$index.png"))
                top += height
                index++
            }
        }
    }

    private fun write(bitmap: Bitmap, file: File) {
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not write ${file.name}" } }
    }

    /** Answers the page's requests from this machine, with the same comic the other pictures show. */
    private fun serve() {
        val json = Json { encodeDefaults = true }
        val comic = json.encodeToString(ComicInfo.serializer(), COMIC)
        val chapters = json.encodeToString(ListSerializer(Ep.serializer()), CHAPTERS.reversed())
        val recommendations = json.encodeToString(ListSerializer(ComicSimple.serializer()), RECOMMENDATIONS)
        val comments = json.encodeToString(ListSerializer(Comment.serializer()), COMMENTS)
        fixture.pica.respondWith { request ->
            when (request.url.encodedPath) {
                "/comics/${COMIC.id}" -> successResponse("""{"comic":$comic}""")
                "/comics/${COMIC.id}/eps" ->
                    successResponse("""{"eps":{"docs":$chapters,"total":${CHAPTERS.size},"limit":${CHAPTERS.size},"page":1,"pages":1}}""")
                "/comics/${COMIC.id}/recommendation" -> successResponse("""{"comics":$recommendations}""")
                "/comics/${COMIC.id}/comments" ->
                    successResponse("""{"topComments":[],"comments":{"docs":$comments,"page":1,"pages":1}}""")
                else -> failureResponse()
            }
        }
    }

    private companion object {
        const val PART_HEIGHT_DP = 915

        const val TITLE = "星屑列车：驶向银河尽头的夜行特快与七位迷路旅客的漫长告别【全彩修订版】"
        const val DESCRIPTION_SHORT = "单行本追加番外，共 24 页。"
        const val DESCRIPTION_LONG = "终点站之后还有一站。每逢新月的夜里，一列没有时刻表的特快会停靠在废弃的星见台车站，" +
            "只载那些「不知道该去哪里」的人。丢了乐谱的小提琴手、离家出走的灯塔看守、背着一整箱旧信的邮差……" +
            "七位旅客在车厢里相遇，随列车驶过星屑海、倒悬的雪原与没有白昼的城镇，各自寻找一个能好好说再见的地方。" +
            "\n\n全彩修订版重绘了第 1–9 话，并收录单行本追加的番外「检票员的假日」。"

        val COMIC = ComicInfo(
            id = "comic",
            title = TITLE,
            author = "白川凪",
            pagesCount = 214,
            epsCount = 46,
            categories = listOf("全彩", "长篇", "奇幻", "冒险", "治愈", "短篇合集", "单行本"),
            likesCount = 12_400,
            creator = UserProfile(id = "uploader", name = "鹿角面包", level = 12, title = "见习骑士"),
            description = DESCRIPTION_LONG,
            chineseTeam = "晨光汉化组",
            tags = listOf("列车", "旅行", "星空", "群像", "夜行", "猫", "车站", "蒸汽朋克", "公路片", "慢节奏", "无对白", "手绘水彩"),
            updatedAt = "2026-11-12T08:00:00.000Z",
            viewsCount = 86_200,
            isFavourite = true,
            commentsCount = 326,
        )

        val LONG_NAMES = COMIC.copy(
            author = "白川凪 × 远野朔 × 葛城遥（原作：望月澄）",
            chineseTeam = "晨光汉化组 & 猫尾草联合汉化",
            creator = UserProfile(id = "uploader", name = "一个名字特别特别长的上传者昵称", level = 128, title = "传说中的图书管理员"),
        )

        val CHAPTERS = listOf(
            "星见台车站", "没有时刻表的特快", "丢失的乐谱", "离家的灯塔看守", "一箱旧信", "餐车的夜宵", "隧道里的回声", "雨夜的道口",
            "检票员的一天", "倒悬的雪原", "没有白昼的城镇", "星屑海", "检票员的怀表", "第七位旅客", "雾中换乘", "旧信", "月台上的小提琴",
            "临时停车", "车窗外的鲸", "失物招领处", "煤水车", "夜班站长", "逆行列车", "桥", "第二封回信", "熄灯之后", "借来的伞", "终点之前",
            "候车室", "汽笛", "沿海支线", "写给明天的明信片", "不存在的十三号车厢", "换轨", "长夜", "流星雨警报", "没有名字的小站", "归还乐谱",
            "灯塔再亮", "最后一箱信", "银河渡口", "告别的练习", "尽头", "回程票", "漫长告别", "番外 检票员的假日",
        ).mapIndexed { index, name -> Ep(id = "ep${index + 1}", title = "第 ${index + 1} 话 $name", order = index + 1) }

        val RECOMMENDATIONS = listOf("夜行邮差", "灯塔看守人", "机巧图书馆", "午后的温室")
            .mapIndexed { index, title -> ComicSimple(id = "recommendation$index", title = title) }

        val COMMENTS = listOf(
            Comment(
                id = "comment1",
                content = "第 12 话星屑海那几页跨页太美了，修订版的颜色比旧版通透很多，建议大家重看一遍。",
                user = UserProfile(id = "reader1", name = "北岸渡鸦", level = 9),
            ),
            Comment(id = "comment2", content = "邮差那条线看哭了。", user = UserProfile(id = "reader2", name = "半糖乌龙", level = 4)),
            Comment(
                id = "comment3",
                content = "感谢汉化！番外里检票员居然养了猫，前面完全没看出来，回去翻了一下第 6 话餐车那里其实有伏笔，" +
                    "作者真的很会埋线，期待后面把七个人的结局都交代清楚。",
                user = UserProfile(id = "reader3", name = "Kotori_07", level = 15),
            ),
        )

        val STATE = DetailState(
            loading = false,
            eps = CHAPTERS,
            recommendations = RECOMMENDATIONS,
            comments = COMMENTS,
            commentsLoaded = true,
        )

        val LIBRARY = LibraryState(
            progress = ReadingProgress(epOrder = 12, epTitle = "第 12 话 星屑海", pictureIndex = 14),
            downloadedEpIds = setOf("ep13", "ep14"),
            currentEpPages = 22,
        )
    }
}

private const val PHONE = "w412dp-h915dp-xhdpi"

/** Tall enough for the categories and tags to be on screen without scrolling. */
private const val KEYWORDS_IN_VIEW = "w412dp-h1300dp-xhdpi"
