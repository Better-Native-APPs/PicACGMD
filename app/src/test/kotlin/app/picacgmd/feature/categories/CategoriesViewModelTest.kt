package app.picacgmd.feature.categories

import androidx.lifecycle.viewModelScope
import app.picacgmd.core.data.settings.PinnableHomeChannel
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.domain.repository.ALL_COMICS_CATEGORY
import app.picacgmd.core.domain.repository.GAMES_CATEGORY
import app.picacgmd.core.domain.repository.RANDOM_CATEGORY
import app.picacgmd.core.domain.repository.buildHomeModel
import app.picacgmd.core.network.Category
import app.picacgmd.feature.comments.awaitState
import app.picacgmd.feature.discover.LoadState
import app.picacgmd.testing.MainDispatcherRule
import app.picacgmd.testing.TestAppEnvironment
import app.picacgmd.testing.TestPicaServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
internal class CategoriesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val pica = TestPicaServer()
    private val models = mutableListOf<CategoriesViewModel>()

    @AfterTest
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        main.dispatcher.scheduler.runCurrent()
        pica.close()
    }

    @Test
    fun builtInPinSetterPersistsBothDirectionsWithoutChangingSameNamedCategoryPins() = runTest(main.dispatcher) {
        val environment = TestAppEnvironment(pica, backgroundScope)
        val categories = listOf("随机", "游戏专区", "分类一")
        environment.settings.setPinnedHomeCategories(categories)
        pica.enqueue("""{"categories":[]}""")
        val model = CategoriesViewModel(environment.container).also(models::add)
        main.awaitState { model.categories is LoadState.Ready }
        assertEquals(setOf("random", "games"), model.settings.value.pinnedHomeChannels)

        model.setChannelPinned(PinnableHomeChannel.RANDOM, false).join()
        main.dispatcher.scheduler.runCurrent()
        assertEquals(setOf("games"), model.settings.value.pinnedHomeChannels)
        model.setChannelPinned(PinnableHomeChannel.GAMES, false).join()
        main.dispatcher.scheduler.runCurrent()
        assertEquals(emptySet(), model.settings.value.pinnedHomeChannels)
        model.setChannelPinned(PinnableHomeChannel.RANDOM, true).join()
        main.dispatcher.scheduler.runCurrent()
        val restored = SettingsRepository(environment.preferences).settings.first()
        assertEquals(setOf("random"), restored.pinnedHomeChannels)
        assertEquals(categories, restored.pinnedHomeCategories)
    }

    @Test
    fun existingEmptyChannelSelectionStaysEmptyAndCategoryChangesDoNotRepinBuiltIns() = runTest(main.dispatcher) {
        val environment = TestAppEnvironment(pica, backgroundScope)
        environment.settings.setPinnedHomeChannels(emptySet())
        environment.settings.setPinnedHomeCategories(listOf("随机", "分类一"))
        pica.enqueue("""{"categories":[]}""")
        val model = CategoriesViewModel(environment.container).also(models::add)
        main.awaitState { model.categories is LoadState.Ready }
        assertEquals(emptySet(), model.settings.value.pinnedHomeChannels)

        model.setPinned("随机", false).join()
        main.dispatcher.scheduler.runCurrent()
        model.reorderPinned(listOf("分类一")).join()
        main.dispatcher.scheduler.runCurrent()
        assertEquals(emptySet(), model.settings.value.pinnedHomeChannels)
        model.setChannelPinned(PinnableHomeChannel.GAMES, true).join()
        main.dispatcher.scheduler.runCurrent()
        assertEquals(setOf("games"), model.settings.value.pinnedHomeChannels)
        assertEquals(listOf("分类一"), model.settings.value.pinnedHomeCategories)
    }

    @Test
    fun builtInVisibilityPersistsByIdWithoutChangingSameNamedCategoryVisibilityOrPins() = runTest(main.dispatcher) {
        val environment = TestAppEnvironment(pica, backgroundScope)
        val serverRandom = Category(id = "server-random", title = RANDOM_CATEGORY.title)
        environment.settings.setHiddenCategories(listOf(serverRandom.title))
        environment.settings.setPinnedHomeCategories(listOf(serverRandom.title))
        pica.enqueue("""{"categories":[]}""")
        val model = CategoriesViewModel(environment.container).also(models::add)
        main.awaitState { model.categories is LoadState.Ready }

        model.setVisible(RANDOM_CATEGORY.id, false).join()
        main.dispatcher.scheduler.runCurrent()
        val hidden = SettingsRepository(environment.preferences).settings.first()
        assertEquals(listOf(serverRandom.title, RANDOM_CATEGORY.id), hidden.hiddenCategories)
        assertEquals(listOf(ALL_COMICS_CATEGORY, GAMES_CATEGORY), buildHomeModel(listOf(serverRandom), hidden).categories)

        model.setVisible(RANDOM_CATEGORY.id, true).join()
        main.dispatcher.scheduler.runCurrent()
        val restored = SettingsRepository(environment.preferences).settings.first()
        assertEquals(listOf(serverRandom.title), restored.hiddenCategories)
        val home = buildHomeModel(listOf(serverRandom), restored)
        assertEquals(listOf(ALL_COMICS_CATEGORY, RANDOM_CATEGORY, GAMES_CATEGORY), home.categories)
        assertEquals(setOf("random", "games"), restored.pinnedHomeChannels)
        assertSame(serverRandom, home.channels.last().category)
    }

    @Test
    fun movingABuiltInBelowAServerCategorySurvivesSettingsReloadAndKeepsUnavailableNames() = runTest(main.dispatcher) {
        val environment = TestAppEnvironment(pica, backgroundScope)
        val first = Category(id = "first", title = "甲")
        val second = Category(id = "second", title = "乙")
        environment.settings.setCategoryOrder(listOf("暂未返回", second.title, first.title))
        pica.enqueue("""{"categories":[]}""")
        val model = CategoriesViewModel(environment.container).also(models::add)
        main.awaitState { model.categories is LoadState.Ready }

        val shown = listOf(ALL_COMICS_CATEGORY.id, RANDOM_CATEGORY.id, first.title, GAMES_CATEGORY.id, second.title)
        model.reorderCategories(shown).join()
        main.dispatcher.scheduler.runCurrent()
        val restored = SettingsRepository(environment.preferences).settings.first()
        assertEquals(listOf("暂未返回") + shown, restored.categoryOrder)
        assertEquals(listOf(ALL_COMICS_CATEGORY, RANDOM_CATEGORY, first, GAMES_CATEGORY, second), buildHomeModel(listOf(first, second), restored).categories)
    }
}
