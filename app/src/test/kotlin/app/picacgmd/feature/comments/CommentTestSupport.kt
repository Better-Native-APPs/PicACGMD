package app.picacgmd.feature.comments

import app.picacgmd.core.network.Comment
import app.picacgmd.core.network.CommentsPage
import app.picacgmd.core.network.GameSimple
import app.picacgmd.core.network.Page
import app.picacgmd.testing.MainDispatcherRule
import app.picacgmd.testing.TestPicaServer
import app.picacgmd.testing.line
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

internal fun comment(id: String, content: String = id, top: Boolean = false, likes: Int = 2): Comment =
    Comment(id = id, content = content, isTop = top, likesCount = likes)

internal fun commentPage(
    page: Int = 1,
    pages: Int = 3,
    docs: List<Comment> = emptyList(),
    top: List<Comment> = emptyList(),
): String = Json.encodeToString(CommentsPage(Page(docs = docs, page = page, pages = pages, total = 30), top))

internal fun replyPage(page: Int = 1, pages: Int = 3, docs: List<Comment> = emptyList()): String =
    """{"comments":${Json.encodeToString(Page(docs = docs, page = page, pages = pages, total = 30))}}"""

internal fun gamesPage(page: Int = 1, pages: Int = 3, docs: List<GameSimple> = emptyList(), total: Int = 30): String =
    """{"games":${Json.encodeToString(Page(docs = docs, page = page, pages = pages, total = total))}}"""

internal fun success(data: String): MockResponse =
    MockResponse.Builder().body("""{"code":200,"message":"success","data":$data}""").build()

internal fun failure(message: String): MockResponse =
    MockResponse.Builder().code(500).body("""{"code":500,"message":"$message"}""").build()

internal class ResponseGate(val response: MockResponse) {
    val started = CountDownLatch(1)
    val released = CountDownLatch(1)
    fun release() = released.countDown()
}

/** Holds each response independently so overlapping requests can finish in a chosen order. */
internal fun TestPicaServer.holdResponses(vararg responses: MockResponse): List<ResponseGate> {
    val gates = responses.map(::ResponseGate)
    val next = AtomicInteger()
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val gate = gates[next.getAndIncrement()]
            gate.started.countDown()
            check(gate.released.await(5, TimeUnit.SECONDS)) { "response was not released" }
            return gate.response
        }
    }
    return gates
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun MainDispatcherRule.take(pica: TestPicaServer, line: String, gate: ResponseGate? = null): RecordedRequest {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    var request: RecordedRequest? = null
    while (request == null && System.nanoTime() < deadline) {
        dispatcher.scheduler.runCurrent()
        request = pica.pollRequest(10)
    }
    return requireNotNull(request) { "no request arrived" }.also {
        assertEquals(line, it.line())
        if (gate != null) assertTrue(gate.started.await(5, TimeUnit.SECONDS), "request was not dispatched")
    }
}

/** The HTTP worker uses real time; pump only ready Main tasks while waiting for its response. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun MainDispatcherRule.awaitState(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (true) {
        dispatcher.scheduler.runCurrent()
        if (condition()) return
        if (System.nanoTime() >= deadline) fail("state did not arrive")
        Thread.sleep(1)
    }
}

internal fun RecordedRequest.content(): String =
    Json.parseToJsonElement(requireNotNull(body).utf8()).let {
        (it as kotlinx.serialization.json.JsonObject).getValue("content").let { value ->
            (value as kotlinx.serialization.json.JsonPrimitive).content
        }
    }
