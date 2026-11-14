package app.picacgmd.feature.games

import androidx.lifecycle.viewModelScope
import app.picacgmd.core.data.settings.PaginationMode
import app.picacgmd.core.domain.repository.GameRepository
import app.picacgmd.core.network.GameSimple
import app.picacgmd.feature.comments.awaitState
import app.picacgmd.feature.comments.gamesPage
import app.picacgmd.feature.comments.take
import app.picacgmd.testing.MainDispatcherRule
import app.picacgmd.testing.TestPicaServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
internal class GamesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val pica = TestPicaServer()
    private val models = mutableListOf<GamesViewModel>()

    private data class State(
        val games: List<GameSimple> = emptyList(),
        val page: Int = 0,
        val pages: Int = 0,
        val total: Int = 0,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private fun GamesViewModel.snapshot(): State = State(games, page, pages, total, loading, error)

    @AfterTest
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        main.dispatcher.scheduler.runCurrent()
        pica.close()
    }

    private fun TestScope.create(): GamesViewModel =
        GamesViewModel(GameRepository(pica.session(backgroundScope))).also(models::add)

    private fun TestScope.loaded(page: Int = 1, pages: Int = 4): GamesViewModel {
        val model = create()
        main.take(pica, "GET /games?page=1")
        pica.enqueue(gamesPage(page, pages, docs = listOf(GameSimple(id = "a"))))
        main.awaitState { !model.loading }
        return model
    }

    @Test
    fun firstLoadIgnoresBusyLoadsReplaceKeepsDuplicatesAndAppendDedupesTheWholeList() = runTest(main.dispatcher) {
        val model = create()
        val states = mutableListOf(model.snapshot())
        assertEquals(State(loading = true), model.snapshot())
        main.take(pica, "GET /games?page=1")
        model.load(9, false)
        model.load(2, true)
        assertEquals(State(loading = true), model.snapshot())
        assertEquals(1, pica.requestCount)
        val a = GameSimple(id = "a", title = "first")
        val duplicate = a.copy(title = "second")
        val b = GameSimple(id = "b")
        pica.enqueue(gamesPage(docs = listOf(a, duplicate, b), total = 21))
        main.awaitState { !model.loading }
        val first = State(games = listOf(a, duplicate, b), page = 1, pages = 3, total = 21)
        states += model.snapshot()
        assertEquals(first, model.snapshot())
        model.load(2, true)
        main.take(pica, "GET /games?page=2")
        states += model.snapshot()
        model.load(1, false)
        val c = GameSimple(id = "c", title = "incoming first")
        pica.enqueue(gamesPage(page = 2, docs = listOf(a.copy(title = "incoming loses"), c, c.copy(title = "incoming duplicate")), total = 25))
        main.awaitState { !model.loading }
        val second = State(games = listOf(a, b, c), page = 2, pages = 3, total = 25)
        states += model.snapshot()
        assertEquals(listOf(State(loading = true), first, first.copy(loading = true), second), states)
        model.load(3, false)
        main.take(pica, "GET /games?page=3")
        pica.enqueue(gamesPage(page = 3, pages = 4, docs = listOf(c, c)))
        main.awaitState { !model.loading }
        assertEquals(State(games = listOf(c, c), page = 3, pages = 4, total = 30), model.snapshot())
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun initialFailureUsesInlineErrorAndRetriesPageOne() = runTest(main.dispatcher) {
        val model = create()
        main.take(pica, "GET /games?page=1")
        pica.enqueueFailure(body = """{"code":500,"message":"first failed"}""")
        main.awaitState { !model.loading }
        assertEquals(State(error = "first failed"), model.snapshot())
        model.load(1, false)
        assertEquals(State(loading = true), model.snapshot())
        main.take(pica, "GET /games?page=1")
        pica.enqueue(gamesPage(pages = 0, total = 0))
        main.awaitState { !model.loading }
        assertEquals(State(page = 1), model.snapshot())
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun appendFailureKeepsContentAndRetriesTheSameAppend() = runTest(main.dispatcher) {
        val model = loaded()
        val first = model.snapshot()
        model.load(2, true)
        main.take(pica, "GET /games?page=2")
        assertEquals(first.copy(loading = true), model.snapshot())
        pica.enqueueFailure(body = """{"code":500,"message":"tail failed"}""")
        main.awaitState { !model.loading }
        assertEquals(first.copy(error = "tail failed"), model.snapshot())
        model.retry()
        assertEquals(first.copy(loading = true), model.snapshot())
        main.take(pica, "GET /games?page=2")
        val b = GameSimple(id = "b")
        pica.enqueue(gamesPage(page = 2, pages = 4, docs = listOf(b)))
        main.awaitState { !model.loading }
        assertEquals(first.copy(games = first.games + b, page = 2), model.snapshot())
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun failedJumpInButtonModeRetriesTheFailedTarget() = runTest(main.dispatcher) {
        pica.preferences.settings.value = pica.preferences.settings.value.copy(paginationMode = PaginationMode.BUTTONS)
        val model = loaded(page = 2, pages = 5)
        val first = model.snapshot()
        model.load(4, false)
        main.take(pica, "GET /games?page=4")
        pica.enqueueFailure(body = """{"code":500,"message":"jump failed"}""")
        main.awaitState { !model.loading }
        assertEquals(first.copy(error = "jump failed"), model.snapshot())
        model.retry()
        main.take(pica, "GET /games?page=4")
        val fresh = GameSimple(id = "fresh")
        pica.enqueue(gamesPage(page = 4, pages = 5, docs = listOf(fresh)))
        main.awaitState { !model.loading }
        assertEquals(first.copy(games = listOf(fresh), page = 4), model.snapshot())
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun failedRefreshRetriesPageOneAsAReplacementInEitherMode() = runTest(main.dispatcher) {
        for (mode in listOf(PaginationMode.BUTTONS, PaginationMode.INFINITE)) {
            pica.preferences.settings.value = pica.preferences.settings.value.copy(paginationMode = mode)
            val requests = pica.requestCount
            val model = loaded(page = 2, pages = 2)
            val first = model.snapshot()
            model.load(1, false)
            main.take(pica, "GET /games?page=1")
            pica.enqueueFailure(body = """{"code":500,"message":"refresh failed"}""")
            main.awaitState { !model.loading }
            assertEquals(first.copy(error = "refresh failed"), model.snapshot())
            model.retry()
            assertEquals(first.copy(loading = true), model.snapshot())
            main.take(pica, "GET /games?page=1")
            val fresh = GameSimple(id = "fresh")
            pica.enqueue(gamesPage(page = 1, pages = 2, docs = listOf(fresh)))
            main.awaitState { !model.loading }
            assertEquals(first.copy(games = listOf(fresh), page = 1), model.snapshot())
            assertEquals(requests + 3, pica.requestCount)
        }
    }

    @Test
    fun retryWhileLoadingSendsNoRequestAndIgnoredLoadsDoNotChangeTheRetry() = runTest(main.dispatcher) {
        val model = loaded()
        val first = model.snapshot()
        model.load(3, true)
        main.take(pica, "GET /games?page=3")
        model.load(4, false)
        model.retry()
        main.dispatcher.scheduler.runCurrent()
        assertEquals(first.copy(loading = true), model.snapshot())
        assertNull(pica.pollRequest(50))
        assertEquals(2, pica.requestCount)
        pica.enqueueFailure(body = """{"code":500,"message":"append failed"}""")
        main.awaitState { !model.loading }
        model.retry()
        main.take(pica, "GET /games?page=3")
        val b = GameSimple(id = "b")
        pica.enqueue(gamesPage(page = 3, pages = 4, docs = listOf(b)))
        main.awaitState { !model.loading }
        assertEquals(first.copy(games = first.games + b, page = 3), model.snapshot())
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun emptyAppendUsesServerEndpointsAndModeSwitchDoesNotReload() = runTest(main.dispatcher) {
        val model = loaded()
        model.load(2, true)
        main.take(pica, "GET /games?page=2")
        pica.enqueue(gamesPage(page = 2, pages = 4))
        main.awaitState { !model.loading }
        assertEquals(2, model.page)
        assertEquals(4, model.pages)
        assertEquals(listOf("a"), model.games.map { it.id })
        val before = model.snapshot()
        pica.preferences.settings.value = pica.preferences.settings.value.copy(paginationMode = PaginationMode.BUTTONS)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(before, model.snapshot())
        assertNull(pica.pollRequest(50))
        pica.preferences.settings.value = pica.preferences.settings.value.copy(paginationMode = PaginationMode.INFINITE)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(before, model.snapshot())
        assertNull(pica.pollRequest(50))
        assertEquals(2, pica.requestCount)
    }
}
