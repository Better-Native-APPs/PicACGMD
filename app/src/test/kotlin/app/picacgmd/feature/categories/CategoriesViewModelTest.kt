package app.picacgmd.feature.categories

import androidx.lifecycle.viewModelScope
import app.picacgmd.core.data.settings.PinnableHomeChannel
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.feature.comments.awaitState
import app.picacgmd.feature.discover.LoadState
import app.picacgmd.testing.MainDispatcherRule
import app.picacgmd.testing.TestAppEnvironment
import app.picacgmd.testing.TestPicaServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
