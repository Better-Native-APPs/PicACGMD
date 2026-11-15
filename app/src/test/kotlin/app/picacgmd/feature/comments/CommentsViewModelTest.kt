package app.picacgmd.feature.comments

import androidx.lifecycle.viewModelScope
import app.picacgmd.core.domain.repository.CommentRepository
import app.picacgmd.core.network.Comment
import app.picacgmd.testing.MainDispatcherRule
import app.picacgmd.testing.TestPicaServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommentsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val pica = TestPicaServer()
    private val models = mutableListOf<CommentsViewModel>()

    @AfterTest
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        main.dispatcher.scheduler.runCurrent()
        pica.close()
    }

    private fun TestScope.create(): CommentsViewModel =
        CommentsViewModel(CommentRepository(pica.session(backgroundScope)), "comic").also(models::add)

    private fun TestScope.loaded(docs: List<Comment> = listOf(comment("a")), top: List<Comment> = emptyList()): CommentsViewModel {
        val model = create()
        main.take(pica, "GET /comics/comic/comments?page=1")
        pica.enqueue(commentPage(docs = docs, top = top))
        main.awaitState { !model.state.value.loading }
        return model
    }

    @Test
    fun firstLoadAndAppendKeepPinnedSeparateAndOnlyDropPreviouslyLoadedIds() = runTest(main.dispatcher) {
        val model = create()
        val states = mutableListOf<CommentsState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.toList(states) }
        val a = comment("a")
        val pin = comment("pin", top = true)
        val c = comment("c")
        main.take(pica, "GET /comics/comic/comments?page=1")
        assertEquals(CommentsState(), model.state.value)
        pica.enqueue(commentPage(docs = listOf(a, a), top = listOf(pin, a)))
        main.awaitState { !model.state.value.loading }
        val first = CommentsState(top = listOf(pin, a), items = listOf(a, a), page = 1, pages = 3, loading = false, revision = 1)
        assertEquals(first, model.state.value)
        model.loadNext()
        main.take(pica, "GET /comics/comic/comments?page=2")
        assertEquals(first.copy(appending = true), model.state.value)
        pica.enqueue(commentPage(page = 2, docs = listOf(a, c, c), top = listOf(comment("other", top = true))))
        main.awaitState { !model.state.value.appending }
        val second = first.copy(items = listOf(a, a, c, c), page = 2, revision = 2)
        assertEquals(listOf(CommentsState(), first, first.copy(appending = true), second), states)
        model.loadNext()
        main.take(pica, "GET /comics/comic/comments?page=3")
        pica.enqueue(commentPage(page = 3, pages = 4))
        main.awaitState { !model.state.value.appending }
        assertEquals(second.copy(page = 3, pages = 4, revision = 3), model.state.value)
        assertTrue(model.state.value.hasNext)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun initialFailureRetriesPageOneWithoutASnackbar() = runTest(main.dispatcher) {
        val model = create()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.messages.toList(messages) }
        main.take(pica, "GET /comics/comic/comments?page=1")
        pica.enqueueFailure(body = """{"code":500,"message":"first failed"}""")
        main.awaitState { !model.state.value.loading }
        assertEquals(CommentsState(loading = false, error = "first failed", revision = 1), model.state.value)
        model.reload()
        assertEquals(CommentsState(revision = 1), model.state.value)
        main.take(pica, "GET /comics/comic/comments?page=1")
        pica.enqueue(commentPage(pages = 0))
        main.awaitState { !model.state.value.loading }
        assertEquals(CommentsState(page = 1, loading = false, revision = 2), model.state.value)
        assertTrue(messages.isEmpty())
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun appendErrorStopsAutoLoadingAndSurvivesReloadUntilTailRetry() = runTest(main.dispatcher) {
        val a = comment("a")
        val model = loaded(listOf(a), listOf(comment("old-pin", top = true)))
        val first = model.state.value
        val states = mutableListOf<CommentsState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.toList(states) }
        model.loadNext()
        main.take(pica, "GET /comics/comic/comments?page=2")
        model.loadNext()
        assertEquals(2, pica.requestCount)
        pica.enqueueFailure(body = """{"code":500,"message":"tail failed"}""")
        main.awaitState { !model.state.value.appending }
        val failed = first.copy(appendError = "tail failed", revision = 2)
        assertEquals(failed, model.state.value)
        model.loadNext()
        assertNull(pica.pollRequest(50))
        model.reload()
        main.take(pica, "GET /comics/comic/comments?page=1")
        assertEquals(failed.copy(loading = true), model.state.value)
        val pin = comment("new-pin", top = true)
        pica.enqueue(commentPage(docs = listOf(a), top = listOf(pin)))
        main.awaitState { !model.state.value.loading }
        val reloaded = failed.copy(top = listOf(pin), revision = 3)
        assertEquals(reloaded, model.state.value)
        model.loadNext()
        assertNull(pica.pollRequest(50))
        model.retryAppend()
        main.take(pica, "GET /comics/comic/comments?page=2")
        assertEquals(reloaded.copy(appending = true, appendError = null), model.state.value)
        val b = comment("b")
        pica.enqueue(commentPage(page = 2, docs = listOf(b)))
        main.awaitState { !model.state.value.appending }
        assertEquals(
            listOf(first, first.copy(appending = true), failed, failed.copy(loading = true), reloaded,
                reloaded.copy(appendError = null), reloaded.copy(appendError = null, appending = true),
                reloaded.copy(items = listOf(a, b), page = 2, appendError = null, revision = 4)),
            states,
        )
        assertEquals(4, pica.requestCount)
    }

    @Test
    fun overlappingReloadAndAppendCommitInCompletionOrder() = runTest(main.dispatcher) {
        val model = loaded()
        val b = comment("b")
        val fresh = comment("fresh")
        val pin = comment("fresh-pin", top = true)
        val gates = pica.holdResponses(
            success(commentPage(page = 2, docs = listOf(b), top = listOf(comment("ignored")))),
            success(commentPage(docs = listOf(fresh), top = listOf(pin))),
        )
        model.loadNext()
        main.take(pica, "GET /comics/comic/comments?page=2", gates[0])
        model.reload()
        main.take(pica, "GET /comics/comic/comments?page=1", gates[1])
        assertTrue(model.state.value.loading && model.state.value.appending)
        gates[1].release()
        main.awaitState { !model.state.value.loading }
        assertEquals(listOf(fresh), model.state.value.items)
        assertTrue(model.state.value.appending)
        gates[0].release()
        main.awaitState { !model.state.value.appending }
        assertEquals(listOf(fresh, b), model.state.value.items)
        assertEquals(listOf(pin), model.state.value.top)
        assertEquals(2, model.state.value.page)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun aCommentAppendWithTheSamePageNumberStopsScrollingUntilReload() = runTest(main.dispatcher) {
        val a = comment("a")
        val b = comment("b")
        val pin = comment("pin", top = true)
        val firstPage = commentPage(docs = listOf(a, a), top = listOf(pin))
        val repeatedPage = commentPage(docs = listOf(a.copy(content = "ignored"), b, b), top = listOf(comment("other-pin", top = true)))
        val model = loaded(listOf(a, a), listOf(pin))
        val first = model.state.value
        assertEquals(1, first.revision)

        model.loadNext(first.revision)
        main.take(pica, "GET /comics/comic/comments?page=2")
        pica.enqueue(repeatedPage)
        main.awaitState { !model.state.value.appending }
        val stalled = first.copy(items = listOf(a, a, b, b), revision = 2)
        assertEquals(stalled, model.state.value)
        assertTrue(stalled.canLoadNext)
        model.loadNext(stalled.revision)
        main.dispatcher.scheduler.runCurrent()
        assertNull(pica.pollRequest(50))
        assertEquals(2, pica.requestCount)
        assertEquals(stalled, model.state.value)

        model.reload()
        main.take(pica, "GET /comics/comic/comments?page=1")
        pica.enqueue(firstPage)
        main.awaitState { !model.state.value.loading }
        val reloaded = first.copy(revision = 3)
        assertEquals(reloaded, model.state.value)
        model.loadNext(reloaded.revision)
        main.take(pica, "GET /comics/comic/comments?page=2")
        pica.enqueueFailure(body = """{"code":500,"message":"append failed"}""")
        main.awaitState { !model.state.value.appending }
        val failed = reloaded.copy(appendError = "append failed", revision = 4)
        assertEquals(failed, model.state.value)
        assertFalse(failed.canLoadNext)
        model.loadNext(failed.revision)
        main.dispatcher.scheduler.runCurrent()
        assertNull(pica.pollRequest(50))
        assertEquals(4, pica.requestCount)
        assertEquals(failed, model.state.value)

        model.retryAppend()
        main.take(pica, "GET /comics/comic/comments?page=2")
        pica.enqueue(repeatedPage)
        main.awaitState { !model.state.value.appending }
        val retried = stalled.copy(revision = 5)
        assertEquals(retried, model.state.value)
        assertTrue(retried.canLoadNext)
        model.loadNext(retried.revision)
        main.dispatcher.scheduler.runCurrent()
        assertNull(pica.pollRequest(50))
        assertEquals(5, pica.requestCount)
        assertEquals(retried, model.state.value)
    }

    @Test
    fun everyFinishedCommentLoadChangesTheRevisionExactlyOnce() = runTest(main.dispatcher) {
        val model = create()
        main.take(pica, "GET /comics/comic/comments?page=1")
        assertEquals(0, model.state.value.revision)
        pica.enqueue(commentPage(docs = listOf(comment("a"))))
        main.awaitState { !model.state.value.loading }
        assertEquals(1, model.state.value.revision)

        model.loadNext(1)
        main.take(pica, "GET /comics/comic/comments?page=2")
        assertEquals(1, model.state.value.revision)
        pica.enqueue(commentPage(page = 2, docs = listOf(comment("b"))))
        main.awaitState { !model.state.value.appending }
        assertEquals(2, model.state.value.revision)

        model.reload()
        main.take(pica, "GET /comics/comic/comments?page=1")
        assertEquals(2, model.state.value.revision)
        pica.enqueueFailure(body = """{"code":500,"message":"refresh failed"}""")
        main.awaitState { !model.state.value.loading }
        assertEquals(3, model.state.value.revision)

        model.loadNext(3)
        main.take(pica, "GET /comics/comic/comments?page=3")
        assertEquals(3, model.state.value.revision)
        pica.enqueueFailure(body = """{"code":500,"message":"append failed"}""")
        main.awaitState { !model.state.value.appending }
        assertEquals(4, model.state.value.revision)
        assertEquals(4, pica.requestCount)
    }

    @Test
    fun aCommentRefreshWithEqualRowsAndPageStillChangesTheState() = runTest(main.dispatcher) {
        val a = comment("a")
        val pin = comment("pin", top = true)
        val model = loaded(listOf(a), listOf(pin))
        val first = model.state.value
        assertEquals(1, first.revision)

        model.reload()
        main.take(pica, "GET /comics/comic/comments?page=1")
        assertEquals(first.copy(loading = true), model.state.value)
        pica.enqueue(commentPage(docs = listOf(a), top = listOf(pin)))
        main.awaitState { !model.state.value.loading }

        assertNotEquals(first, model.state.value)
        assertEquals(first.copy(revision = 2), model.state.value)
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun commentRequestsForAnEarlierRevisionDoNotLoadOrChangeState() = runTest(main.dispatcher) {
        val a = comment("a")
        val model = loaded(listOf(a))
        val earlierRevision = model.state.value.revision
        model.reload()
        main.take(pica, "GET /comics/comic/comments?page=1")
        pica.enqueue(commentPage(docs = listOf(a)))
        main.awaitState { !model.state.value.loading }
        val current = model.state.value
        assertEquals(2, current.revision)
        assertTrue(current.canLoadNext)

        model.loadNext(earlierRevision)
        main.dispatcher.scheduler.runCurrent()
        assertNull(pica.pollRequest(50))
        assertEquals(2, pica.requestCount)
        assertEquals(current, model.state.value)

        model.loadNext(current.revision)
        main.take(pica, "GET /comics/comic/comments?page=2")
        assertEquals(current.copy(appending = true), model.state.value)
        pica.enqueue(commentPage(page = 2, docs = listOf(comment("b"))))
        main.awaitState { !model.state.value.appending }
        assertEquals(3, model.state.value.revision)
        assertEquals(listOf(a, comment("b")), model.state.value.items)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun likesFlipEveryAppearanceImmediatelyAndHaveNoPerIdLock() = runTest(main.dispatcher) {
        val top = comment("same", top = true, likes = 2)
        val item = comment("same", likes = 4)
        val reply = comment("same", likes = 6)
        val model = loaded(listOf(item), listOf(top))
        model.openReplies(top)
        main.take(pica, "GET /comments/same/childrens?page=1")
        pica.enqueue(replyPage(docs = listOf(reply)))
        main.awaitState { model.replies.value?.loading == false }
        fun assertAppearances(liked: Boolean) {
            val appearances = model.state.value.top + model.state.value.items +
                listOf(assertNotNull(model.replies.value).parent) + assertNotNull(model.replies.value).replies
            assertEquals(listOf(liked, liked, liked, liked), appearances.map { it.isLiked })
            assertEquals(listOf(2, 4, 2, 6).map { it + if (liked) 1 else 0 }, appearances.map { it.likesCount })
        }
        val gates = pica.holdResponses(success("""{"action":"like"}"""), success("""{"action":"unlike"}"""))
        model.toggleLike("same")
        assertAppearances(true)
        main.take(pica, "POST /comments/same/like", gates[0])
        model.toggleLike("same")
        assertAppearances(false)
        main.take(pica, "POST /comments/same/like", gates[1])
        gates[1].release()
        main.awaitState { model.viewModelScope.coroutineContext[Job]?.children?.count() == 1 }
        assertAppearances(false)
        gates[0].release()
        main.awaitState { model.state.value.items.single().isLiked }
        assertAppearances(true)
        assertEquals(4, pica.requestCount)
    }

    @Test
    fun failedLikeRollsBackAndUsesTheMessageStream() = runTest(main.dispatcher) {
        val model = loaded(listOf(comment("a", likes = 0)))
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.messages.toList(messages) }
        model.toggleLike("a")
        assertTrue(model.state.value.items.single().isLiked)
        assertEquals(1, model.state.value.items.single().likesCount)
        main.take(pica, "POST /comments/a/like")
        pica.enqueueFailure(body = """{"code":500,"message":"like failed"}""")
        main.awaitState { messages.isNotEmpty() }
        assertEquals(listOf("like failed"), messages)
        assertFalse(model.state.value.items.single().isLiked)
        assertEquals(0, model.state.value.items.single().likesCount)
        assertNull(model.state.value.error)
    }

    @Test
    fun postingIsAllowedDuringLoadingAndClearsInputBeforeResettingSendingAndReloading() = runTest(main.dispatcher) {
        val gates = pica.holdResponses(success(commentPage()), success("{}"), success(commentPage(docs = listOf(comment("sent")))))
        val model = create()
        main.take(pica, "GET /comics/comic/comments?page=1", gates[0])
        val callbackStates = mutableListOf<CommentsState>()
        val callbackCounts = mutableListOf<Int>()
        model.post("  draft \n") { callbackStates += model.state.value; callbackCounts += pica.requestCount }
        assertTrue(model.state.value.loading && model.state.value.sending)
        val request = main.take(pica, "POST /comics/comic/comments", gates[1])
        assertEquals("draft", request.content())
        model.post("second") { error("second send must be ignored") }
        gates[1].release()
        main.awaitState { !model.state.value.sending }
        main.take(pica, "GET /comics/comic/comments?page=1", gates[2])
        assertEquals(listOf(CommentsState(sending = true)), callbackStates)
        assertEquals(listOf(2), callbackCounts)
        assertTrue(model.state.value.loading)
        gates[0].release()
        main.awaitState { model.state.value.page == 1 }
        gates[2].release()
        main.awaitState { model.state.value.items.map { it.id } == listOf("sent") }
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun comicLengthLimitLivesInTheComposerNotTheViewModel() = runTest(main.dispatcher) {
        val model = loaded()
        model.post(" \n ") { error("blank send must be ignored") }
        assertNull(pica.pollRequest(50))
        assertEquals(0, commentLength(" \n "))
        assertFalse(commentSendable(0))
        assertTrue(commentSendable(commentLength("  ${"x".repeat(500)}  ")))
        assertFalse(commentSendable(commentLength("  ${"x".repeat(501)}  ")))
        model.post(" ${"x".repeat(501)} ") {}
        assertEquals("x".repeat(501), main.take(pica, "POST /comics/comic/comments").content())
        pica.enqueue("{}")
        main.take(pica, "GET /comics/comic/comments?page=1")
        pica.enqueue(commentPage())
        main.awaitState { !model.state.value.loading }
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun postFailureKeepsInputAndOnlyEmitsASnackbarMessage() = runTest(main.dispatcher) {
        val model = loaded()
        val first = model.state.value
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.messages.toList(messages) }
        var cleared = false
        model.post("draft") { cleared = true }
        assertEquals(first.copy(sending = true), model.state.value)
        main.take(pica, "POST /comics/comic/comments")
        pica.enqueueFailure(body = """{"code":500,"message":"send failed"}""")
        main.awaitState { messages.isNotEmpty() }
        assertEquals(first, model.state.value)
        assertEquals(listOf("send failed"), messages)
        assertFalse(cleared)
        assertEquals(2, pica.requestCount)
    }

    @Test
    fun reportsUseTheCommonEndpointAndReplayZeroMessages() = runTest(main.dispatcher) {
        val model = loaded()
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
        collector.cancel()
        val replay = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.messages.toList(replay) }
        assertTrue(replay.isEmpty())
        assertNull(model.state.value.error)
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun replyFirstFailureRetriesOneAndLaterFailureRetriesCurrentPlusOne() = runTest(main.dispatcher) {
        val model = loaded()
        val parent = comment("parent")
        val states = mutableListOf<ReplyThread?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.replies.toList(states) }
        model.openReplies(parent)
        main.take(pica, "GET /comments/parent/childrens?page=1")
        assertEquals(ReplyThread(parent), model.replies.value)
        model.loadMoreReplies()
        model.retryReplies()
        pica.enqueueFailure(body = """{"code":500,"message":"first reply failed"}""")
        main.awaitState { model.replies.value?.loading == false }
        val firstFailure = ReplyThread(parent, loading = false, error = "first reply failed", revision = 1)
        assertEquals(firstFailure, model.replies.value)
        model.loadMoreReplies()
        assertNull(pica.pollRequest(50))
        model.retryReplies()
        main.take(pica, "GET /comments/parent/childrens?page=1")
        assertEquals(ReplyThread(parent, revision = 1), model.replies.value)
        val r1 = comment("r1")
        val r2 = comment("r2")
        pica.enqueue(replyPage(docs = listOf(r1, r1)))
        main.awaitState { model.replies.value?.loading == false }
        model.loadMoreReplies()
        main.take(pica, "GET /comments/parent/childrens?page=2")
        pica.enqueue(replyPage(page = 2, docs = listOf(r1, r2, r2)))
        main.awaitState { model.replies.value?.loading == false }
        val second = ReplyThread(parent, replies = listOf(r1, r1, r2, r2), page = 2, pages = 3, loading = false, revision = 3)
        assertEquals(second, model.replies.value)
        model.loadMoreReplies()
        main.take(pica, "GET /comments/parent/childrens?page=3")
        pica.enqueueFailure(body = """{"code":500,"message":"later reply failed"}""")
        main.awaitState { model.replies.value?.loading == false }
        assertEquals(second.copy(error = "later reply failed", revision = 4), model.replies.value)
        model.loadMoreReplies()
        assertNull(pica.pollRequest(50))
        model.retryReplies()
        main.take(pica, "GET /comments/parent/childrens?page=3")
        assertEquals(second.copy(loading = true, revision = 4), model.replies.value)
        pica.enqueue(replyPage(page = 3, pages = 4))
        main.awaitState { model.replies.value?.loading == false }
        assertEquals(second.copy(page = 3, pages = 4, revision = 5), model.replies.value)
        assertEquals(listOf(null, ReplyThread(parent), firstFailure, ReplyThread(parent, revision = 1)), states.take(4))
        assertEquals(6, pica.requestCount)
    }

    @Test
    fun aFirstReplyPageNumberedZeroIsNotAskedForAgainByScrolling() = runTest(main.dispatcher) {
        val model = loaded()
        model.openReplies(comment("parent"))
        main.take(pica, "GET /comments/parent/childrens?page=1")
        pica.enqueue(replyPage(page = 0, pages = 1))
        main.awaitState { model.replies.value?.loading == false }
        val thread = assertNotNull(model.replies.value)
        assertEquals(0, thread.page)
        assertTrue(thread.canLoadMore)

        model.loadMoreReplies(thread.revision)
        main.dispatcher.scheduler.runCurrent()
        assertNull(pica.pollRequest(50))
        assertEquals(thread, model.replies.value)

        // 重试 still asks for the first page.
        model.retryReplies()
        main.take(pica, "GET /comments/parent/childrens?page=1")
        pica.enqueue(replyPage(page = 1, pages = 1, docs = listOf(comment("a"))))
        main.awaitState { model.replies.value?.loading == false }
        assertEquals(listOf("a"), model.replies.value?.replies?.map { it.id })
    }

    @Test
    fun aReplyAppendWithTheSamePageNumberStopsScrollingUntilRefresh() = runTest(main.dispatcher) {
        val model = loaded()
        val parent = comment("parent")
        val a = comment("a")
        val b = comment("b")
        val firstPage = replyPage(docs = listOf(a, a))
        val repeatedPage = replyPage(docs = listOf(a.copy(content = "ignored"), b, b))
        model.openReplies(parent)
        main.take(pica, "GET /comments/parent/childrens?page=1")
        pica.enqueue(firstPage)
        main.awaitState { model.replies.value?.loading == false }
        val first = assertNotNull(model.replies.value)
        assertEquals(1, first.revision)

        model.loadMoreReplies(first.revision)
        main.take(pica, "GET /comments/parent/childrens?page=2")
        pica.enqueue(repeatedPage)
        main.awaitState { model.replies.value?.loading == false }
        val stalled = first.copy(replies = listOf(a, a, b, b), revision = 2)
        assertEquals(stalled, model.replies.value)
        assertTrue(stalled.canLoadMore)
        model.loadMoreReplies(stalled.revision)
        main.dispatcher.scheduler.runCurrent()
        assertNull(pica.pollRequest(50))
        assertEquals(3, pica.requestCount)
        assertEquals(stalled, model.replies.value)

        model.reply("refresh") {}
        main.take(pica, "POST /comments/parent")
        pica.enqueue("{}")
        main.take(pica, "GET /comments/parent/childrens?page=1")
        pica.enqueue(firstPage)
        main.awaitState { model.replies.value?.loading == false }
        val refreshed = first.copy(parent = parent.copy(commentsCount = parent.commentsCount + 1), revision = 3)
        assertEquals(refreshed, model.replies.value)
        model.loadMoreReplies(refreshed.revision)
        main.take(pica, "GET /comments/parent/childrens?page=2")
        pica.enqueueFailure(body = """{"code":500,"message":"append failed"}""")
        main.awaitState { model.replies.value?.loading == false }
        val failed = refreshed.copy(error = "append failed", revision = 4)
        assertEquals(failed, model.replies.value)
        assertFalse(failed.canLoadMore)
        model.loadMoreReplies(failed.revision)
        main.dispatcher.scheduler.runCurrent()
        assertNull(pica.pollRequest(50))
        assertEquals(6, pica.requestCount)
        assertEquals(failed, model.replies.value)

        model.retryReplies()
        main.take(pica, "GET /comments/parent/childrens?page=2")
        pica.enqueue(repeatedPage)
        main.awaitState { model.replies.value?.loading == false }
        val retried = stalled.copy(parent = refreshed.parent, revision = 5)
        assertEquals(retried, model.replies.value)
        assertTrue(retried.canLoadMore)
        model.loadMoreReplies(retried.revision)
        main.dispatcher.scheduler.runCurrent()
        assertNull(pica.pollRequest(50))
        assertEquals(7, pica.requestCount)
        assertEquals(retried, model.replies.value)
    }

    @Test
    fun everyFinishedReplyLoadChangesTheRevisionExactlyOnce() = runTest(main.dispatcher) {
        val model = loaded()
        model.openReplies(comment("parent"))
        main.take(pica, "GET /comments/parent/childrens?page=1")
        assertEquals(0, model.replies.value?.revision)
        pica.enqueue(replyPage(docs = listOf(comment("r1"))))
        main.awaitState { model.replies.value?.loading == false }
        assertEquals(1, model.replies.value?.revision)

        model.loadMoreReplies(1)
        main.take(pica, "GET /comments/parent/childrens?page=2")
        assertEquals(1, model.replies.value?.revision)
        pica.enqueue(replyPage(page = 2, docs = listOf(comment("r2"))))
        main.awaitState { model.replies.value?.loading == false }
        assertEquals(2, model.replies.value?.revision)

        model.reply("refresh") {}
        main.take(pica, "POST /comments/parent")
        pica.enqueue("{}")
        main.take(pica, "GET /comments/parent/childrens?page=1")
        assertEquals(2, model.replies.value?.revision)
        pica.enqueueFailure(body = """{"code":500,"message":"refresh failed"}""")
        main.awaitState { model.replies.value?.loading == false }
        assertEquals(3, model.replies.value?.revision)
        assertEquals(2, model.replies.value?.page)

        model.retryReplies()
        main.take(pica, "GET /comments/parent/childrens?page=3")
        assertEquals(3, model.replies.value?.revision)
        pica.enqueueFailure(body = """{"code":500,"message":"append failed"}""")
        main.awaitState { model.replies.value?.loading == false }
        assertEquals(4, model.replies.value?.revision)
        assertEquals(6, pica.requestCount)
    }

    @Test
    fun aReplyRefreshWithEqualRowsAndPageStillChangesTheState() = runTest(main.dispatcher) {
        val model = loaded()
        model.openReplies(comment("parent"))
        main.take(pica, "GET /comments/parent/childrens?page=1")
        val rows = listOf(comment("r1"))
        pica.enqueue(replyPage(docs = rows))
        main.awaitState { model.replies.value?.loading == false }
        val first = assertNotNull(model.replies.value)
        assertEquals(1, first.revision)

        model.reply("refresh") {}
        main.take(pica, "POST /comments/parent")
        pica.enqueue("{}")
        main.take(pica, "GET /comments/parent/childrens?page=1")
        // Sending already updated the parent; only the finished page should change this comparison.
        val beforeRefresh = assertNotNull(model.replies.value).copy(loading = false)
        assertEquals(first.revision, beforeRefresh.revision)
        pica.enqueue(replyPage(docs = rows.toList()))
        main.awaitState { model.replies.value?.loading == false }

        val refreshed = assertNotNull(model.replies.value)
        assertEquals(first.replies, refreshed.replies)
        assertEquals(first.page, refreshed.page)
        assertNotEquals(beforeRefresh, refreshed)
        assertEquals(beforeRefresh.copy(revision = 2), refreshed)
        assertEquals(4, pica.requestCount)
    }

    @Test
    fun replyRequestsForAnEarlierRevisionDoNotLoadOrChangeState() = runTest(main.dispatcher) {
        val model = loaded()
        model.openReplies(comment("parent"))
        main.take(pica, "GET /comments/parent/childrens?page=1")
        pica.enqueue(replyPage(docs = listOf(comment("r1"))))
        main.awaitState { model.replies.value?.loading == false }
        val earlierRevision = assertNotNull(model.replies.value).revision

        model.reply("refresh") {}
        main.take(pica, "POST /comments/parent")
        pica.enqueue("{}")
        main.take(pica, "GET /comments/parent/childrens?page=1")
        pica.enqueue(replyPage(docs = listOf(comment("r1"))))
        main.awaitState { model.replies.value?.loading == false }
        val current = assertNotNull(model.replies.value)
        assertEquals(2, current.revision)
        assertTrue(current.canLoadMore)

        model.loadMoreReplies(earlierRevision)
        main.dispatcher.scheduler.runCurrent()
        assertNull(pica.pollRequest(50))
        assertEquals(4, pica.requestCount)
        assertEquals(current, model.replies.value)

        model.loadMoreReplies(current.revision)
        main.take(pica, "GET /comments/parent/childrens?page=2")
        assertEquals(current.copy(loading = true), model.replies.value)
        pica.enqueue(replyPage(page = 2, docs = listOf(comment("r2"))))
        main.awaitState { model.replies.value?.loading == false }
        assertEquals(3, model.replies.value?.revision)
        assertEquals(listOf(comment("r1"), comment("r2")), model.replies.value?.replies)
        assertEquals(5, pica.requestCount)
    }

    @Test
    fun aLaterReplyThreadContinuesThePreviousThreadsRevision() = runTest(main.dispatcher) {
        val model = loaded()
        val previousRevisions = mutableListOf<Int>()
        model.openReplies(comment("a"))
        main.take(pica, "GET /comments/a/childrens?page=1")
        previousRevisions += assertNotNull(model.replies.value).revision
        pica.enqueue(replyPage(docs = listOf(comment("a1"))))
        main.awaitState { model.replies.value?.loading == false }
        previousRevisions += assertNotNull(model.replies.value).revision
        model.loadMoreReplies(1)
        main.take(pica, "GET /comments/a/childrens?page=2")
        pica.enqueue(replyPage(page = 2, docs = listOf(comment("a2"))))
        main.awaitState { model.replies.value?.loading == false }
        previousRevisions += assertNotNull(model.replies.value).revision
        assertEquals(listOf(0, 1, 2), previousRevisions)

        model.closeReplies()
        assertNull(model.replies.value)
        model.openReplies(comment("b"))
        main.take(pica, "GET /comments/b/childrens?page=1")
        assertEquals(previousRevisions.last(), model.replies.value?.revision)
        pica.enqueue(replyPage(docs = listOf(comment("b1"))))
        main.awaitState { model.replies.value?.loading == false }
        val current = assertNotNull(model.replies.value)
        assertEquals(3, current.revision)
        assertFalse(current.revision in previousRevisions)
        assertTrue(current.canLoadMore)
        model.loadMoreReplies(previousRevisions.last())
        main.dispatcher.scheduler.runCurrent()
        assertNull(pica.pollRequest(50))
        assertEquals(current, model.replies.value)
        assertEquals(4, pica.requestCount)
    }

    @Test
    fun replySuccessClearsInputBeforeIncrementingParentAndReplacingPageOne() = runTest(main.dispatcher) {
        val parent = comment("parent").copy(commentsCount = 3)
        val model = loaded(listOf(parent))
        model.openReplies(parent)
        main.take(pica, "GET /comments/parent/childrens?page=1")
        pica.enqueue(replyPage(docs = listOf(comment("old"))))
        main.awaitState { model.replies.value?.loading == false }
        val old = assertNotNull(model.replies.value)
        val callbacks = mutableListOf<ReplyThread?>()
        model.reply(" \n ") { error("blank reply must be ignored") }
        model.reply("  reply \n") { callbacks += model.replies.value }
        assertEquals(old.copy(sending = true), model.replies.value)
        assertEquals("reply", main.take(pica, "POST /comments/parent").content())
        model.reply("again") { error("concurrent reply must be ignored") }
        pica.enqueue("{}")
        main.take(pica, "GET /comments/parent/childrens?page=1")
        assertEquals(listOf<ReplyThread?>(old.copy(sending = true)), callbacks)
        assertEquals(old.copy(parent = parent.copy(commentsCount = 4), loading = true), model.replies.value)
        assertEquals(3, model.state.value.items.single().commentsCount)
        val fresh = comment("fresh")
        pica.enqueue(replyPage(docs = listOf(fresh, fresh)))
        main.awaitState { model.replies.value?.loading == false }
        assertEquals(listOf(fresh, fresh), model.replies.value?.replies)
        assertEquals(1, model.replies.value?.page)
        assertEquals(4, pica.requestCount)
    }

    @Test
    fun replyingWhileRepliesLoadIsAllowedAndFailureOnlyEmitsAMessage() = runTest(main.dispatcher) {
        val model = loaded()
        val parent = comment("parent")
        val gates = pica.holdResponses(success(replyPage()), failure("reply send failed"))
        model.openReplies(parent)
        main.take(pica, "GET /comments/parent/childrens?page=1", gates[0])
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.messages.toList(messages) }
        var cleared = false
        model.reply("draft") { cleared = true }
        main.take(pica, "POST /comments/parent", gates[1])
        assertEquals(ReplyThread(parent, sending = true), model.replies.value)
        gates[1].release()
        main.awaitState { messages.isNotEmpty() }
        assertEquals(ReplyThread(parent), model.replies.value)
        assertEquals(listOf("reply send failed"), messages)
        assertFalse(cleared)
        gates[0].release()
        main.awaitState { model.replies.value?.loading == false }
        assertEquals(3, pica.requestCount)
    }

    @Test
    fun repliesDiscardSuccessAndFailureForADifferentParent() = runTest(main.dispatcher) {
        val model = loaded()
        val gates = pica.holdResponses(success(replyPage(docs = listOf(comment("wrong")))), failure("wrong failure"), success(replyPage(docs = listOf(comment("right")))))
        model.openReplies(comment("a"))
        main.take(pica, "GET /comments/a/childrens?page=1", gates[0])
        model.openReplies(comment("b"))
        main.take(pica, "GET /comments/b/childrens?page=1", gates[1])
        model.openReplies(comment("c"))
        main.take(pica, "GET /comments/c/childrens?page=1", gates[2])
        gates[0].release()
        gates[1].release()
        main.awaitState { model.viewModelScope.coroutineContext[Job]?.children?.count() == 1 }
        assertEquals(ReplyThread(comment("c")), model.replies.value)
        gates[2].release()
        main.awaitState { model.replies.value?.loading == false }
        assertEquals("c", model.replies.value?.parent?.id)
        assertEquals(listOf("right"), model.replies.value?.replies?.map { it.id })
        assertNull(model.replies.value?.error)
        model.closeReplies()
        assertNull(model.replies.value)
        assertEquals(4, pica.requestCount)
    }

    @Test
    fun closingAndReopeningTheSameParentStillAcceptsTheEarlierResponse() = runTest(main.dispatcher) {
        val model = loaded()
        val parent = comment("parent")
        val gates = pica.holdResponses(success(replyPage(docs = listOf(comment("early")))), success(replyPage(docs = listOf(comment("late")))))
        model.openReplies(parent)
        main.take(pica, "GET /comments/parent/childrens?page=1", gates[0])
        model.closeReplies()
        model.openReplies(parent)
        main.take(pica, "GET /comments/parent/childrens?page=1", gates[1])
        gates[0].release()
        main.awaitState { model.replies.value?.loading == false }
        assertEquals(listOf("early"), model.replies.value?.replies?.map { it.id })
        gates[1].release()
        main.awaitState { model.replies.value?.replies?.map { it.id } == listOf("late") }
        assertEquals(3, pica.requestCount)
    }
}
