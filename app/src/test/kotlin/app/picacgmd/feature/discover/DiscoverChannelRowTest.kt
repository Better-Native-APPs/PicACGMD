package app.picacgmd.feature.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.component.HomeChannel
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.testing.ComposeHostRule
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** The home row with pinned categories in their shaped badges: what is announced and what a tap does. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h800dp")
class DiscoverChannelRowTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private var pinned by mutableStateOf(listOf(HomeCategory("大家都在看"), HomeCategory("大濕推薦"), AllComicsHomeCategory))
    private var darkMode by mutableStateOf(DarkMode.Light)
    private val opened = mutableListOf<String>()

    @Test
    fun aPinnedCategoryIsOneButtonNamedByItsLabelAndLargeEnoughToTap() {
        compose()
        listOf("大家都在看", "大濕推薦", AllComicsHomeCategory.name).forEach { name ->
            entry(name)
                .assertExists()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                // The picture and the stand-in character add nothing to what is read out.
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun tapsOpenTheCategoryAndTheFixedEntriesStillWork() {
        compose()
        entry("大濕推薦").performClick()
        entry(AllComicsHomeCategory.name).performClick()
        entry("排行榜").performClick()
        assertEquals(listOf("category:大濕推薦", "all", "channel:Rankings"), opened)
    }

    @Test
    fun theRowSurvivesReorderingUnpinningAndAThemeChange() {
        compose()
        pinned = listOf(HomeCategory("大濕推薦"), HomeCategory("大家都在看"))
        composeRule.waitForIdle()
        darkMode = DarkMode.Dark
        composeRule.waitForIdle()
        pinned = listOf(HomeCategory("大家都在看"))
        composeRule.waitForIdle()

        entry("大濕推薦").assertDoesNotExist()
        entry("大家都在看").performClick()
        assertEquals(listOf("category:大家都在看"), opened)
    }

    private fun entry(label: String) = composeRule.onNode(hasText(label) and hasClickAction())

    private fun compose() {
        composeRule.setContent {
            PicaTheme(themeChoice = ThemeChoice.Brand, darkMode = darkMode) {
                ChannelRow(
                    pinned = pinned,
                    onChannelClick = { channel: HomeChannel -> opened += "channel:${channel.name}" },
                    onPinnedClick = { opened += "category:$it" },
                    onAllComicsClick = { opened += "all" },
                )
            }
        }
    }
}
