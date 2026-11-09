package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.network.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HomeModelTest {
    @Test
    fun homeAlwaysStartsWithExactlyThreeFixedChannels() {
        val model = buildHomeModel(emptyList(), AppSettings())
        assertEquals(listOf("排行榜", "随机", "游戏专区"), model.channels.map { it.title })
        assertEquals(listOf(HomeChannelKind.RANKINGS, HomeChannelKind.RANDOM, HomeChannelKind.GAMES), model.channels.map { it.kind })
    }

    @Test
    fun pinsUseActualServerSpellingAndKeepConfiguredOrder() {
        val recommended = Category(id = "r", title = "大湿推荐")
        val popular = Category(id = "p", title = "大家都在看")
        val model = buildHomeModel(listOf(recommended, popular), AppSettings())
        assertEquals(listOf("大家都在看", "大湿推荐"), model.channels.drop(3).map { it.title })
        assertSame(recommended, model.channels.last().category)
    }

    @Test
    fun simplifiedPinResolvesTraditionalServerCategory() {
        val category = Category(title = "大濕推薦")
        val model = buildHomeModel(
            listOf(category), AppSettings(pinnedHomeCategories = listOf("大湿推荐")),
        )
        assertEquals("大濕推薦", model.channels.last().title)
        assertSame(category, model.channels.last().category)
    }

    @Test
    fun unknownWebAndDuplicatePinsDoNotBecomeBrokenChannels() {
        val model = buildHomeModel(
            listOf(Category(title = "大濕推薦"), Category(title = "广告", isWeb = true)),
            AppSettings(pinnedHomeCategories = listOf("不存在", "广告", "大湿推荐", "大濕推薦")),
        )
        assertEquals(listOf("排行榜", "随机", "游戏专区", "大濕推薦"), model.channels.map { it.title })
        assertEquals(listOf("大濕推薦"), model.categories.map { it.title })
    }

    @Test
    fun gridOrderAndHiddenListAreIndependentOfPins() {
        val model = buildHomeModel(
            listOf(Category(title = "甲"), Category(title = "乙"), Category(title = "丙"), Category(title = "广告", isWeb = true)),
            AppSettings(categoryOrder = listOf("丙", "甲"), hiddenCategories = listOf("乙"), pinnedHomeCategories = listOf("乙")),
        )
        assertEquals(listOf("丙", "甲"), model.categories.map { it.title })
        assertEquals("乙", model.channels.last().title)
    }
}
