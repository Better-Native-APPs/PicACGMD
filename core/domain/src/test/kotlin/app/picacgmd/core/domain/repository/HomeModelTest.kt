package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.network.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HomeModelTest {
    @Test
    fun homeDefaultsKeepAllThreeBuiltInChannels() {
        val model = buildHomeModel(emptyList(), AppSettings())
        assertEquals(listOf("排行榜", "随机", "游戏专区"), model.channels.map { it.title })
        assertEquals(listOf(HomeChannelKind.RANKINGS, HomeChannelKind.RANDOM, HomeChannelKind.GAMES), model.channels.map { it.kind })
        assertEquals(BUILT_IN_HOME_CATEGORIES, model.categories)
    }

    @Test
    fun rankingsStaysFirstAndOptionalBuiltInsKeepTheirOrderAheadOfCategories() {
        val selections = mapOf(
            setOf("games", "random") to listOf(HomeChannelKind.RANKINGS, HomeChannelKind.RANDOM, HomeChannelKind.GAMES),
            setOf("random") to listOf(HomeChannelKind.RANKINGS, HomeChannelKind.RANDOM),
            setOf("games") to listOf(HomeChannelKind.RANKINGS, HomeChannelKind.GAMES),
            emptySet<String>() to listOf(HomeChannelKind.RANKINGS),
        )
        val category = Category(id = "server-random", title = "随机")
        selections.forEach { (selected, builtIns) ->
            val model = buildHomeModel(
                listOf(category),
                AppSettings(pinnedHomeChannels = selected, pinnedHomeCategories = listOf(category.title)),
            )
            assertEquals(builtIns + HomeChannelKind.CATEGORY, model.channels.map { it.kind })
            assertSame(category, model.channels.last().category)
            assertEquals(BUILT_IN_HOME_CATEGORIES + category, model.categories)
            assertSame(RANDOM_CATEGORY, model.categories[1])
            assertSame(category, model.categories[3])
            assertEquals(model.categories.size, model.categories.map { it.id }.toSet().size)
        }
    }

    @Test
    fun unknownBuiltInPinIdsAreIgnoredRatherThanCreatingChannels() {
        val model = buildHomeModel(
            emptyList(), AppSettings(pinnedHomeChannels = setOf("unknown", "rankings", "games")),
        )
        assertEquals(listOf(HomeChannelKind.RANKINGS, HomeChannelKind.GAMES), model.channels.map { it.kind })
        val unknownOnly = buildHomeModel(emptyList(), AppSettings(pinnedHomeChannels = setOf("unknown")))
        assertEquals(listOf(HomeChannelKind.RANKINGS), unknownOnly.channels.map { it.kind })
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
        assertEquals(listOf("全分类", "随机", "游戏专区", "大濕推薦"), model.categories.map { it.title })
    }

    @Test
    fun gridOrderAndHiddenListAreIndependentOfPins() {
        val model = buildHomeModel(
            listOf(Category(title = "甲"), Category(title = "乙"), Category(title = "丙"), Category(title = "广告", isWeb = true)),
            AppSettings(categoryOrder = listOf("丙", "甲"), hiddenCategories = listOf("乙"), pinnedHomeCategories = listOf("乙")),
        )
        assertEquals(listOf("全分类", "随机", "游戏专区", "丙", "甲"), model.categories.map { it.title })
        assertEquals("乙", model.channels.last().title)
    }

    @Test
    fun builtInGridEntriesStayFirstEvenIfUnpinnedOrOldPreferencesHideOrReorderThem() {
        val model = buildHomeModel(
            listOf(Category(title = "乙"), Category(title = "甲")),
            AppSettings(
                pinnedHomeChannels = emptySet(),
                categoryOrder = listOf("游戏专区", "甲", "随机", "全分类", "乙"),
                hiddenCategories = listOf("全分类", "随机", "游戏专区"),
            ),
        )
        assertEquals(listOf("全分类", "随机", "游戏专区", "甲", "乙"), model.categories.map { it.title })
        assertSame(ALL_COMICS_CATEGORY, model.categories.first())
        assertEquals(BUILT_IN_HOME_CATEGORIES, model.categories.take(3))
        assertEquals(listOf(HomeChannelKind.RANKINGS), model.channels.map { it.kind })
    }

    @Test
    fun builtInGridEntriesAreNotMatchedByNameAgainstCategoryPins() {
        val model = buildHomeModel(
            emptyList(),
            AppSettings(pinnedHomeChannels = emptySet(), pinnedHomeCategories = listOf("随机", "游戏专区")),
        )
        assertEquals(BUILT_IN_HOME_CATEGORIES, model.categories)
        assertEquals(listOf(HomeChannelKind.RANKINGS), model.channels.map { it.kind })
    }

    @Test
    fun allComicsCanBePinnedAndReorderedAlongsideServerCategories() {
        val model = buildHomeModel(
            listOf(Category(title = "甲"), Category(title = "乙")),
            AppSettings(pinnedHomeCategories = listOf("乙", "全分类", "甲", "全分类")),
        )
        assertEquals(listOf("乙", "全分类", "甲"), model.channels.drop(3).map { it.title })
        assertSame(ALL_COMICS_CATEGORY, model.channels[4].category)
    }
}
