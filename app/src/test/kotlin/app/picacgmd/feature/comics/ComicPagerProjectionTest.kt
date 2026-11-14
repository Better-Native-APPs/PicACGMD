package app.picacgmd.feature.comics

import app.picacgmd.core.data.settings.PaginationMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class ComicPagerProjectionTest {
    @Test
    fun projectionIsDerivedOncePerStateInstanceHoweverOftenItIsRead() {
        val rows = listOf(ComicEntry.Hidden(1, 2), ComicEntry.Comic(comic("a"), 1), ComicEntry.Comic(comic("a", "duplicate"), 1))
        val counted = CountingEntries(rows)
        val state = ComicPageState(entries = counted, page = 1, pages = 3, hiddenCount = 2, loading = false)

        assertEquals(0, counted.reads)
        val projection = state.comics
        assertEquals(listOf(comic("a"), comic("a", "duplicate")), projection)
        assertEquals(rows.size, counted.reads)
        repeat(20) { assertSame(projection, state.comics) }
        assertEquals(rows.size, counted.reads)
    }

    @Test
    fun aCopyDerivesFromItsOwnEntries() {
        val rows = listOf(ComicEntry.Hidden(1, 2), ComicEntry.Comic(comic("a"), 1), ComicEntry.Comic(comic("a", "duplicate"), 1))
        val counted = CountingEntries(rows)
        val state = ComicPageState(entries = counted, page = 1, pages = 3, hiddenCount = 2, loading = false)
        val projection = state.comics

        assertEquals(rows.size, counted.reads)
        repeat(5) { assertSame(projection, state.comics) }
        assertEquals(rows.size, counted.reads)

        val presentationCopy = state.copy(loading = true)
        assertEquals(rows.size, counted.reads)
        assertEquals(projection, presentationCopy.comics)
        assertEquals(rows.size * 2, counted.reads)
        val copyProjection = presentationCopy.comics
        repeat(5) { assertSame(copyProjection, presentationCopy.comics) }
        assertEquals(rows.size * 2, counted.reads)

        val changedRows = CountingEntries(listOf(ComicEntry.Hidden(4, 1), ComicEntry.Comic(comic("b"), 4)))
        val changed = state.copy(entries = changedRows)
        assertEquals(0, changedRows.reads)
        assertEquals(listOf(comic("b")), changed.comics)
        assertEquals(2, changedRows.reads)
        assertSame(changed.comics, changed.comics)
        assertEquals(2, changedRows.reads)
        assertSame(projection, state.comics)
    }

    @Test
    fun cachedValueDoesNotParticipateInEqualityHashCodeOrGeneratedCopy() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        pager.jumpTo(2)
        runCurrent()
        source.respond(comicPage(2, comic("a"), comic("a", "duplicate"), pages = 4, hidden = 3))
        runCurrent()
        val cached = pager.state.value
        val projection = cached.comics
        val independent = ComicPageState(
            entries = cached.entries.toList(), page = cached.page, pages = cached.pages, total = cached.total,
            hiddenCount = cached.hiddenCount, loading = cached.loading, error = cached.error,
            appending = cached.appending, appendError = cached.appendError,
            revision = 1,
        )

        assertEquals(independent, cached)
        assertEquals(independent.hashCode(), cached.hashCode())
        assertEquals(independent.toString(), cached.toString())
        assertEquals(cached, cached.copy())
        assertEquals(projection, cached.copy().comics)
        assertEquals(projection, independent.comics)
        assertEquals(independent, cached)
        assertEquals(independent.hashCode(), cached.hashCode())

        val changedEntries = listOf(ComicEntry.Hidden(4, 1), ComicEntry.Comic(comic("b"), 4))
        val changed = cached.copy(entries = changedEntries)
        assertEquals(independent.copy(entries = changedEntries), changed)
        assertEquals(listOf(comic("b")), changed.comics)
        assertEquals(listOf(comic("a"), comic("a", "duplicate")), cached.comics)
        assertEquals(emptyList(), cached.copy(entries = emptyList()).comics)
    }

    @Test
    fun projectionMatchesFullRecomputationAcrossMixedPageCommits() = runTest {
        val source = ComicPageSource()
        val pager = ComicPager(this, source::fetch)
        val random = Random(217340)
        var expectedEntries = emptyList<ComicEntry>()
        var expectedHidden = 0
        var expectedRevision = 0
        var furthestAppendedPage = Int.MIN_VALUE
        var appendCount = 0
        var nonAdvancingAppendCount = 0

        repeat(200) { step ->
            val before = pager.state.value
            val oldEntries = before.entries.toList()
            val oldProjection = before.comics.toList()
            val append = before.page > 0 && before.canLoadNext && before.page > furthestAppendedPage && random.nextBoolean()
            val requestedPage = if (append) before.page + 1 else random.nextInt(1, 10)
            val oldMode = pager.mode
            if (append) {
                pager.mode = PaginationMode.INFINITE
                pager.loadNext()
            } else {
                pager.mode = if (random.nextBoolean()) PaginationMode.INFINITE else PaginationMode.BUTTONS
                if (requestedPage == 1) pager.reload() else pager.jumpTo(requestedPage)
            }
            if (pager.mode != oldMode) expectedRevision++
            assertEquals(oldProjection, pager.state.value.comics, "pending step $step")
            assertEquals(expectedRevision, pager.state.value.revision, "pending revision at step $step")
            runCurrent()
            assertEquals(step + 1, source.requests.size)
            assertEquals(requestedPage, source.requestedPages.last())

            val responsePage = if (random.nextInt(4) == 0) random.nextInt(1, 10) else requestedPage
            val hidden = random.nextInt(5)
            val items = List(random.nextInt(8)) { index -> comic("id-${random.nextInt(6)}", "response-$step-$index") }
            val incoming = buildList<ComicEntry> {
                if (hidden > 0) add(ComicEntry.Hidden(responsePage, hidden))
                items.forEach { add(ComicEntry.Comic(it, responsePage)) }
            }
            expectedEntries = if (append) {
                val oldIds = expectedEntries.mapNotNull { (it as? ComicEntry.Comic)?.comic?.id }.toSet()
                expectedEntries + incoming.filter { it !is ComicEntry.Comic || it.comic.id !in oldIds }
            } else {
                incoming
            }
            expectedHidden = if (append) expectedHidden + hidden else hidden
            source.respond(
                comicPage(responsePage, *items.toTypedArray(), pages = responsePage + random.nextInt(5), hidden = hidden, total = 1000 + step),
            )
            runCurrent()
            val committed = pager.state.value
            if (append) {
                furthestAppendedPage = maxOf(furthestAppendedPage, before.page)
                appendCount++
                if (responsePage <= before.page) nonAdvancingAppendCount++
            } else {
                furthestAppendedPage = Int.MIN_VALUE
            }
            expectedRevision++

            assertEquals(expectedEntries, committed.entries, "entries at step $step")
            assertEquals(expectedHidden, committed.hiddenCount, "hidden count at step $step")
            assertEquals(expectedRevision, committed.revision, "revision at step $step")
            assertEquals(expectedEntries.mapNotNull { (it as? ComicEntry.Comic)?.comic }, committed.comics, "projection at step $step")
            assertEquals(committed.entries.mapNotNull { (it as? ComicEntry.Comic)?.comic }, committed.comics, "full recomputation at step $step")
            assertSame(committed.comics, committed.comics)
            assertEquals(committed.comics, committed.copy().comics)
            assertEquals(oldEntries, before.entries, "old entries at step $step")
            assertEquals(oldProjection, before.comics, "old projection at step $step")
        }
        assertTrue(nonAdvancingAppendCount > 5, "$nonAdvancingAppendCount of $appendCount appends did not advance")
    }
}

private class CountingEntries(private val rows: List<ComicEntry>) : AbstractList<ComicEntry>() {
    var reads = 0
        private set
    override val size: Int get() = rows.size

    override fun get(index: Int): ComicEntry {
        reads++
        return rows[index]
    }
}
