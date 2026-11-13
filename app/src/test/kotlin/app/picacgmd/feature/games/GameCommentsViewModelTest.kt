package app.picacgmd.feature.games

import androidx.lifecycle.viewModelScope
import app.picacgmd.core.data.settings.PaginationMode
import app.picacgmd.core.domain.repository.CommentRepository
import app.picacgmd.core.domain.repository.GameRepository
import app.picacgmd.core.network.Comment
import app.picacgmd.feature.comments.awaitState
import app.picacgmd.feature.comments.comment
import app.picacgmd.feature.comments.commentPage
import app.picacgmd.feature.comments.content
import app.picacgmd.feature.comments.failure
import app.picacgmd.feature.comments.holdResponses
import app.picacgmd.feature.comments.replyPage
import app.picacgmd.feature.comments.success
import app.picacgmd.feature.comments.take
import app.picacgmd.testing.MainDispatcherRule
import app.picacgmd.testing.TestPicaServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
internal class GameCommentsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val pica = TestPicaServer()
    private val models = mutableListOf<GameCommentsViewModel>()

    private data class State(
        val comments: List<Comment> = emptyList(),
        val page: Int = 0,
        val pages: Int = 0,
        val loading: Boolean = false,
        val sending: Boolean = false,
        val error: String? = null,
        val liking: Set<String> = emptySet(),
    )

    private fun GameCommentsViewModel.snapshot(): State = State(comments, page, pages, loading, sending, error, liking)

    @AfterTest
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        main.dispatcher.scheduler.runCurrent()
        pica.close()
    }

    private fun TestScope.create(parentId: String? = null): GameCommentsViewModel {
        val session = pica.session(backgroundScope)
        return GameCommentsViewModel(GameRepository(session), CommentRepository(session), "game", parentId).also(models::add)
    }

    private fun TestScope.loaded(
        docs: List<Comment> = listOf(comment("a")),
        page: Int = 1,
        pages: Int = 3,
    ): GameCommentsViewModel {
        val model = create()
        main.take(pica, "GET /games/game/comments?page=1")
        pica.enqueue(commentPage(page = page, pages = pages, docs = docs))
        main.awaitState { !model.loading }
        return model
    }

    /** This is the footer's existing decision, not a last-failed-target retry. */
    private fun GameCommentsViewModel.retryTail(infinite: Boolean) {
        if (infinite && page < pages) load(page + 1, true)
        else load(page.coerceAtLeast(1), false)
    }

    @Test
    fun firstLoadRejectsBusyActionsAndMergesPinnedBeforeDedupingBothWrites() = runTest(main.dispatcher) {
        val model = create()
        val initial = State(loading = true)
        val states = mutableListOf(model.snapshot())
        assertEquals(initial, model.snapshot())
        main.take(pica, "GET /games/game/comments?page=1")
        model.load(9, false)
        model.load(2, true)
        model.post("blocked") { error("loading must block sending") }
        assertEquals(initial, model.snapshot())
        assertEquals(1, pica.requestCount)
        val pin = comment("same", content = "pinned wins", top = true)
        val a = comment("a")
        pica.enqueue(commentPage(docs = listOf(comment("same"), a, a), top = listOf(pin, pin)))
        main.awaitState { !model.loading }
        val first = State(comments = listOf(pin, a), page = 1, pages = 3)
        states += model.snapshot()
        model.load(2, true)
        main.take(pica, "GET /games/game/comments?page=2")
        states += model.snapshot()
        assertEquals(first.copy(loading = true), model.snapshot())
        model.load(1, false)
        val b = comment("b", top = true)
        val c = comment("c")
        pica.enqueue(commentPage(page = 2, docs = listOf(comment("same", "new loses"), comment("b"), c, c), top = listOf(b)))
        main.awaitState { !model.loading }
        val second = first.copy(comments = listOf(pin, a, b, c), page = 2)
        states += model.snapshot()
        assertEquals(listOf(initial, first, first.copy(loading = true), second), states)
        model.load(3, false)
        main.take(pica, "GET /games/game/comments?page=3")
        val fresh = comment("fresh", top = true)
        pica.enqueue(commentPage(page = 3, pages = 4, top = listOf(fresh), docs = listOf(fresh, fresh)))
        main.awaitState { !model.loading }
        assertEquals(State(comments = listOf(fresh), page = 3, pages = 4), model.snapshot())
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun firstFailureIsInlineAndRetryKeepsTheServerPageCount() = runTest(main.dispatcher) {
        val model = create()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.messages.toList(messages) }
        main.take(pica, "GET /games/game/comments?page=1")
        pica.enqueueFailure(body = """{"code":500,"message":"first failed"}""")
        main.awaitState { !model.loading }
        assertEquals(State(error = "first failed"), model.snapshot())
        model.load(1, false)
        assertEquals(State(loading = true), model.snapshot())
        main.take(pica, "GET /games/game/comments?page=1")
        pica.enqueue(commentPage(pages = 0))
        main.awaitState { !model.loading }
        assertEquals(State(page = 1), model.snapshot())
        assertTrue(messages.isEmpty())
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun appendFailureKeepsContentAndTailRetryAsksForCurrentPlusOne() = runTest(main.dispatcher) {
        val model = loaded()
        val first = model.snapshot()
        model.load(2, true)
        main.take(pica, "GET /games/game/comments?page=2")
        assertEquals(first.copy(loading = true), model.snapshot())
        pica.enqueueFailure(body = """{"code":500,"message":"tail failed"}""")
        main.awaitState { !model.loading }
        assertEquals(first.copy(error = "tail failed"), model.snapshot())
        model.retryTail(true)
        assertEquals(first.copy(loading = true), model.snapshot())
        main.take(pica, "GET /games/game/comments?page=2")
        pica.enqueue(commentPage(page = 2, docs = listOf(comment("b"))))
        main.awaitState { !model.loading }
        assertEquals(first.copy(comments = first.comments + comment("b"), page = 2), model.snapshot())
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun infiniteRetryAfterFailedJumpAppendsCurrentPlusOneNotTheFailedTarget() = runTest(main.dispatcher) {
        val model = loaded(pages = 5)
        val first = model.snapshot()
        model.load(4, false)
        main.take(pica, "GET /games/game/comments?page=4")
        pica.enqueueFailure(body = """{"code":500,"message":"jump failed"}""")
        main.awaitState { !model.loading }
        assertEquals(first.copy(error = "jump failed"), model.snapshot())
        model.retryTail(true)
        main.take(pica, "GET /games/game/comments?page=2")
        pica.enqueue(commentPage(page = 2, pages = 5, docs = listOf(comment("b"))))
        main.awaitState { !model.loading }
        assertEquals(listOf("a", "b"), model.comments.map { it.id })
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun buttonRetryReloadsCurrentPageAndInfiniteRetryAtTheEndDoesTheSame() = runTest(main.dispatcher) {
        val model = loaded(page = 2, pages = 2)
        model.load(1, false)
        main.take(pica, "GET /games/game/comments?page=1")
        pica.enqueueFailure(body = """{"code":500,"message":"refresh failed"}""")
        main.awaitState { !model.loading }
        model.retryTail(false)
        main.take(pica, "GET /games/game/comments?page=2")
        pica.enqueue(commentPage(page = 2, pages = 2, docs = listOf(comment("button"))))
        main.awaitState { !model.loading }
        assertEquals(listOf("button"), model.comments.map { it.id })
        model.load(1, false)
        main.take(pica, "GET /games/game/comments?page=1")
        pica.enqueueFailure(body = """{"code":500,"message":"refresh failed again"}""")
        main.awaitState { !model.loading }
        model.retryTail(true)
        main.take(pica, "GET /games/game/comments?page=2")
        pica.enqueue(commentPage(page = 2, pages = 2, docs = listOf(comment("end"))))
        main.awaitState { !model.loading }
        assertEquals(listOf("end"), model.comments.map { it.id })
        assertEquals(5, pica.requestCount)
    }

    @Test
    fun emptyAppendDoesNotInventAnEndAndPagingModeChangesDoNotReload() = runTest(main.dispatcher) {
        val model = loaded()
        model.load(2, true)
        main.take(pica, "GET /games/game/comments?page=2")
        pica.enqueue(commentPage(page = 2, pages = 4))
        main.awaitState { !model.loading }
        assertEquals(listOf("a"), model.comments.map { it.id })
        assertEquals(2, model.page)
        assertEquals(4, model.pages)
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

    @Test
    fun likesWaitForTheServerWithSynchronousPerIdDisableAndInlineFailure() = runTest(main.dispatcher) {
        val a = comment("a")
        val b = comment("b")
        val model = loaded(listOf(a, b))
        val first = model.snapshot()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.messages.toList(messages) }
        val gates = pica.holdResponses(success("""{"action":"like"}"""), failure("like failed"))
        model.toggleLike(a)
        assertEquals(first.copy(liking = setOf("a")), model.snapshot())
        main.take(pica, "POST /comments/a/like", gates[0])
        model.toggleLike(a)
        assertEquals(2, pica.requestCount)
        model.toggleLike(b)
        assertEquals(first.copy(liking = setOf("a", "b")), model.snapshot())
        main.take(pica, "POST /comments/b/like", gates[1])
        gates[1].release()
        main.awaitState { "b" !in model.liking }
        assertEquals(first.copy(liking = setOf("a"), error = "like failed"), model.snapshot())
        gates[0].release()
        main.awaitState { model.liking.isEmpty() }
        assertEquals(first.copy(comments = listOf(a.copy(isLiked = true, likesCount = 3), b), error = "like failed"), model.snapshot())
        assertTrue(messages.isEmpty())
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun aPendingLikeUpdatesTheCurrentReplacementNotTheOldCommentObject() = runTest(main.dispatcher) {
        val old = comment("a")
        val fresh = comment("a", content = "fresh", likes = 9)
        val model = loaded(listOf(old))
        val gates = pica.holdResponses(success("""{"action":"like"}"""), success(commentPage(docs = listOf(fresh))))
        model.toggleLike(old)
        main.take(pica, "POST /comments/a/like", gates[0])
        model.load(1, false)
        main.take(pica, "GET /games/game/comments?page=1", gates[1])
        gates[1].release()
        main.awaitState { !model.loading }
        assertEquals(listOf(fresh), model.comments)
        assertEquals(setOf("a"), model.liking)
        gates[0].release()
        main.awaitState { model.liking.isEmpty() }
        assertEquals(listOf(fresh.copy(isLiked = true, likesCount = 10)), model.comments)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun postingChecksTrimmedLengthAndClearsInputBeforeReloadWhileSendingIsStillTrue() = runTest(main.dispatcher) {
        val model = loaded()
        val first = model.snapshot()
        model.post(" \n ") { error("blank must be rejected") }
        model.post("x".repeat(501)) { error("long draft must be rejected") }
        assertNull(pica.pollRequest(50))
        val callbackStates = mutableListOf<State>()
        val callbackCounts = mutableListOf<Int>()
        val draft = "  ${"x".repeat(500)}  "
        model.post(draft.trim()) { callbackStates += model.snapshot(); callbackCounts += pica.requestCount }
        assertEquals(first.copy(sending = true), model.snapshot())
        assertEquals("x".repeat(500), main.take(pica, "POST /games/game/comments").content())
        model.post("again") { error("sending must block another send") }
        pica.enqueue("{}")
        main.take(pica, "GET /games/game/comments?page=1")
        assertEquals(listOf(first.copy(sending = true)), callbackStates)
        assertEquals(listOf(2), callbackCounts)
        assertEquals(first.copy(loading = true), model.snapshot())
        model.post("while reload waits") { error("loading must block another send") }
        assertEquals(3, pica.requestCount)
        pica.enqueue(commentPage(docs = listOf(comment("sent"))))
        main.awaitState { !model.loading }
        assertEquals(first.copy(comments = listOf(comment("sent"))), model.snapshot())
    }

    @Test
    fun directGamePostKeepsWhitespaceBecauseTrimmingBelongsToTheScreen() = runTest(main.dispatcher) {
        val model = loaded()
        model.post("  raw \n") {}
        assertEquals("  raw \n", main.take(pica, "POST /games/game/comments").content())
        pica.enqueue("{}")
        main.take(pica, "GET /games/game/comments?page=1")
        pica.enqueue(commentPage())
        main.awaitState { !model.loading }
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun postFailureClearsSendingButKeepsDraftAndUsesInlineError() = runTest(main.dispatcher) {
        val model = loaded()
        val first = model.snapshot()
        model.load(1, false)
        main.take(pica, "GET /games/game/comments?page=1")
        pica.enqueueFailure(body = """{"code":500,"message":"load failed"}""")
        main.awaitState { !model.loading }
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.messages.toList(messages) }
        var cleared = false
        model.post("draft") { cleared = true }
        assertEquals(first.copy(sending = true), model.snapshot())
        main.take(pica, "POST /games/game/comments")
        pica.enqueueFailure(body = """{"code":500,"message":"send failed"}""")
        main.awaitState { !model.sending }
        assertEquals(first.copy(error = "send failed"), model.snapshot())
        assertFalse(cleared)
        assertTrue(messages.isEmpty())
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun aUserLoadDuringPostingMakesTheSuccessReloadIgnored() = runTest(main.dispatcher) {
        val model = loaded()
        val first = model.snapshot()
        val gates = pica.holdResponses(success("{}"), success(commentPage(page = 3, docs = listOf(comment("third")))))
        val callbacks = mutableListOf<State>()
        model.post("draft") { callbacks += model.snapshot() }
        main.take(pica, "POST /games/game/comments", gates[0])
        model.load(3, false)
        main.take(pica, "GET /games/game/comments?page=3", gates[1])
        assertEquals(first.copy(loading = true, sending = true), model.snapshot())
        gates[0].release()
        main.awaitState { !model.sending }
        assertEquals(listOf(first.copy(loading = true, sending = true)), callbacks)
        assertEquals(first.copy(loading = true), model.snapshot())
        assertNull(pica.pollRequest(50))
        gates[1].release()
        main.awaitState { !model.loading }
        assertEquals(first.copy(comments = listOf(comment("third")), page = 3), model.snapshot())
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun gameReplyRouteUsesCommonReplyEndpointsAndReloadsPageOneAfterClearing() = runTest(main.dispatcher) {
        val model = create("parent")
        main.take(pica, "GET /comments/parent/childrens?page=1")
        val r = comment("reply")
        pica.enqueue(replyPage(docs = listOf(r, r)))
        main.awaitState { !model.loading }
        assertEquals(listOf(r), model.comments)
        val first = model.snapshot()
        val callbacks = mutableListOf<State>()
        model.post("reply text".trim()) { callbacks += model.snapshot() }
        assertEquals("reply text", main.take(pica, "POST /comments/parent").content())
        pica.enqueue("{}")
        main.take(pica, "GET /comments/parent/childrens?page=1")
        assertEquals(listOf(first.copy(sending = true)), callbacks)
        assertEquals(first.copy(loading = true), model.snapshot())
        pica.enqueue(replyPage(docs = listOf(comment("new"))))
        main.awaitState { !model.loading }
        assertEquals(listOf("new"), model.comments.map { it.id })
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun reportsUseSnackbarMessagesIncludingFailureWithoutChangingInlineError() = runTest(main.dispatcher) {
        val model = loaded()
        val first = model.snapshot()
        val messages = mutableListOf<String>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.messages.toList(messages) }
        model.report("a", "reported")
        assertEquals("{}", requireNotNull(main.take(pica, "POST /comments/a/report").body).utf8())
        pica.enqueue("{}")
        main.awaitState { messages.size == 1 }
        model.report("reply", "reported again")
        main.take(pica, "POST /comments/reply/report")
        pica.enqueueFailure(body = """{"code":500,"message":"report failed"}""")
        main.awaitState { messages.size == 2 }
        assertEquals(listOf("reported", "report failed"), messages)
        assertEquals(first, model.snapshot())
        collector.cancel()
        val replay = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.messages.toList(replay) }
        assertTrue(replay.isEmpty())
        assertEquals(3, pica.requestCount)
    }
}
