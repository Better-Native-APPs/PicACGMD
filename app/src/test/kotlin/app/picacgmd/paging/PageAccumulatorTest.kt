package app.picacgmd.paging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PageAccumulatorTest {
    @Test
    fun currentStartsAsInitial() {
        val initial = page(listOf(Row(1, "initial")), page = 0, pages = 0, total = 0, meta = 7)
        val pager = accumulator(initial = initial)

        assertSame(initial, pager.current)
    }

    @Test
    fun replaceNoneDiscardsOldContentAndKeepsPageDuplicates() {
        val incoming = listOf(Row(1, "first"), Row(1, "second"), Row(2, "new"))
        val pager = accumulator(initial = page(listOf(Row(9, "old"))))

        val commit = pager.commit(page(incoming), PageWrite.REPLACE)

        assertEquals(incoming, commit.data.items)
        assertTrue(commit.resetProjection)
        assertEquals(incoming, commit.projectionDelta)
        assertSame(commit.data, pager.current)
    }

    @Test
    fun replaceAllKeepsTheFirstOccurrenceWithinThePage() {
        val first = Row(1, "first")
        val other = Row(2, "other")
        val pager = accumulator(replaceDedupe = Dedupe.ALL, initial = page(listOf(Row(1, "old"))))

        val commit = pager.commit(page(listOf(first, Row(1, "duplicate"), other)), PageWrite.REPLACE)

        assertEquals(listOf(first, other), commit.data.items)
        assertTrue(commit.resetProjection)
        assertEquals(commit.data.items, commit.projectionDelta)
    }

    @Test
    fun replaceAgainstOldBehavesLikeNoneEvenForAnOldKey() {
        val incoming = listOf(Row(1, "first"), Row(1, "second"))
        val pager = accumulator(replaceDedupe = Dedupe.AGAINST_OLD, initial = page(listOf(Row(1, "old"))))

        val commit = pager.commit(page(incoming), PageWrite.REPLACE)

        assertEquals(incoming, commit.data.items)
        assertTrue(commit.resetProjection)
        assertEquals(incoming, commit.projectionDelta)
    }

    @Test
    fun appendNoneKeepsOldKeysAndDuplicatesWithinTheIncomingPage() {
        val old = Row(1, "old")
        val incoming = listOf(Row(1, "incoming old key"), Row(2, "first"), Row(2, "second"))
        val pager = accumulator(appendDedupe = Dedupe.NONE, initial = page(listOf(old)))

        val commit = pager.commit(page(incoming), PageWrite.APPEND)

        assertEquals(listOf(old) + incoming, commit.data.items)
        assertFalse(commit.resetProjection)
        assertEquals(incoming, commit.projectionDelta)
    }

    @Test
    fun appendAgainstOldRejectsOldKeysButKeepsIncomingDuplicates() {
        val old = listOf(Row(1, "old first"), Row(1, "old duplicate"))
        val added = listOf(Row(2, "first"), Row(2, "second"))
        val pager = accumulator(initial = page(old))

        val commit = pager.commit(page(listOf(Row(1, "ignored")) + added), PageWrite.APPEND)

        assertEquals(old + added, commit.data.items)
        assertFalse(commit.resetProjection)
        assertEquals(added, commit.projectionDelta)
    }

    @Test
    fun appendAgainstOldIndexesThePreviousIncomingPage() {
        val pager = accumulator()
        val firstPage = listOf(Row(1, "first"), Row(1, "second"))
        pager.commit(page(firstPage), PageWrite.APPEND)
        val newRow = Row(2, "new")

        val commit = pager.commit(page(listOf(Row(1, "ignored"), newRow)), PageWrite.APPEND)

        assertEquals(firstPage + newRow, commit.data.items)
        assertEquals(listOf(newRow), commit.projectionDelta)
        assertFalse(commit.resetProjection)
    }

    @Test
    fun appendAllKeepsFirstOccurrencesWithOldRowsFirst() {
        val old = Row(1, "old")
        val firstNew = Row(2, "first new")
        val pager = accumulator(appendDedupe = Dedupe.ALL, initial = page(listOf(old)))

        val commit = pager.commit(
            page(listOf(Row(1, "ignored old key"), firstNew, Row(2, "ignored page duplicate"))),
            PageWrite.APPEND,
        )

        assertEquals(listOf(old, firstNew), commit.data.items)
        assertFalse(commit.resetProjection)
        assertEquals(listOf(firstNew), commit.projectionDelta)
    }

    @Test
    fun appendAllCleansOldDuplicatesAndResetsProjectionOnlyWhenNeeded() {
        val first = Row(1, "first old")
        val structural = Row(null, "slot")
        val second = Row(2, "second old")
        val third = Row(3, "first new")
        val pager = accumulator(appendDedupe = Dedupe.ALL)
        pager.commit(page(listOf(first, structural, Row(1, "old duplicate"), second)), PageWrite.REPLACE)

        val cleaned = pager.commit(
            page(listOf(Row(2, "ignored old key"), third, Row(3, "page duplicate"))),
            PageWrite.APPEND,
        )

        assertEquals(listOf(first, structural, second, third), cleaned.data.items)
        assertTrue(cleaned.resetProjection)
        assertEquals(cleaned.data.items, cleaned.projectionDelta)

        val fourth = Row(4, "next")
        val incremental = pager.commit(page(listOf(Row(3, "ignored"), fourth)), PageWrite.APPEND)

        assertEquals(cleaned.data.items + fourth, incremental.data.items)
        assertFalse(incremental.resetProjection)
        assertEquals(listOf(fourth), incremental.projectionDelta)
    }

    @Test
    fun nullKeyRowsSurviveEveryReplaceAndAppendMode() {
        val firstSlot = Row(null, "first slot")
        val nextSlot = Row(null, "next slot")
        for (replace in Dedupe.entries) for (append in Dedupe.entries) {
            val pager = accumulator(replaceDedupe = replace, appendDedupe = append)
            pager.commit(
                page(listOf(firstSlot, Row(1, "first"), firstSlot, Row(1, "duplicate"))),
                PageWrite.REPLACE,
            )
            val commit = pager.commit(
                page(listOf(nextSlot, Row(1, "old key"), nextSlot, Row(2, "new"), Row(2, "duplicate"))),
                PageWrite.APPEND,
            )

            assertEquals(
                listOf(firstSlot, firstSlot, nextSlot, nextSlot),
                commit.data.items.filter { it.key == null },
                "$replace / $append",
            )
        }
    }

    @Test
    fun replaceRebuildsTheIndexWithoutDiscardedKeys() {
        for (replace in Dedupe.entries) for (append in listOf(Dedupe.AGAINST_OLD, Dedupe.ALL)) {
            val pager = accumulator(replaceDedupe = replace, appendDedupe = append)
            pager.commit(page(listOf(Row(1, "discarded"))), PageWrite.REPLACE)
            val replacement = Row(2, "replacement")
            pager.commit(page(listOf(replacement)), PageWrite.REPLACE)
            val restored = Row(1, "allowed again")

            val commit = pager.commit(page(listOf(restored, Row(2, "ignored"))), PageWrite.APPEND)

            assertEquals(listOf(replacement, restored), commit.data.items, "$replace / $append")
            assertEquals(listOf(restored), commit.projectionDelta)
        }
    }

    @Test
    fun replaceTakesIncomingMetaAndAppendAddsHiddenCounts() {
        var appendMetaCalls = 0
        val pager = accumulator(initial = page(meta = 40), appendMeta = { old, incoming ->
            appendMetaCalls += 1
            old + incoming
        })

        val replacement = pager.commit(page(meta = 2), PageWrite.REPLACE)
        assertEquals(2, replacement.data.meta)
        assertEquals(0, appendMetaCalls)

        val appended = pager.commit(page(meta = 3), PageWrite.APPEND)
        assertEquals(5, appended.data.meta)
        assertEquals(1, appendMetaCalls)
    }

    @Test
    fun pinnedMetaChangesOnlyOnReplace() {
        val initial = PageData<Row, String>(emptyList(), 0, 0, 0, "initial pinned")
        val pager = PageAccumulator<Row, Int, String>(
            initial, Row::key, Dedupe.NONE, Dedupe.AGAINST_OLD, appendMeta = { old, _ -> old },
        )

        pager.commit(initial.copy(meta = "replacement pinned"), PageWrite.REPLACE)
        val appended = pager.commit(initial.copy(meta = "ignored pinned"), PageWrite.APPEND)
        assertEquals("replacement pinned", appended.data.meta)

        val replaced = pager.commit(initial.copy(meta = "next pinned"), PageWrite.REPLACE)
        assertEquals("next pinned", replaced.data.meta)
    }

    @Test
    fun pagePagesAndTotalPassThroughWithoutNormalization() {
        for (write in PageWrite.entries) {
            val pager = accumulator(initial = page(listOf(Row(1, "old"))))
            val incoming = page(listOf(Row(2, "new")), page = -4, pages = -1, total = -9)

            val commit = pager.commit(incoming, write)

            assertEquals(incoming.page, commit.data.page)
            assertEquals(incoming.pages, commit.data.pages)
            assertEquals(incoming.total, commit.data.total)
        }
    }

    @Test
    fun emptyAppendKeepsItemsWhileNumbersAdvanceAndDoesNotEndPaging() {
        for (append in Dedupe.entries) {
            val old = page(listOf(Row(1, "old")), page = 1, pages = 5, total = 8)
            val pager = accumulator(appendDedupe = append, initial = old)

            val empty = pager.commit(page(page = 2, pages = 5, total = 7), PageWrite.APPEND)

            assertEquals(old.items, empty.data.items)
            assertEquals(2, empty.data.page)
            assertEquals(5, empty.data.pages)
            assertEquals(7, empty.data.total)
            assertFalse(empty.resetProjection)
            assertEquals(emptyList(), empty.projectionDelta)
            assertNotSame(old.items, empty.data.items)

            val nextRow = Row(2, "after empty page")
            val next = pager.commit(page(listOf(nextRow), page = 3, pages = 5), PageWrite.APPEND)
            assertEquals(old.items + nextRow, next.data.items)
            assertEquals(3, next.data.page)
        }
    }

    @Test
    fun emptyReplaceClearsItemsAndResetsProjection() {
        for (replace in Dedupe.entries) {
            val pager = accumulator(replaceDedupe = replace, initial = page(listOf(Row(1, "old"))))

            val commit = pager.commit(page(page = 3, pages = 9), PageWrite.REPLACE)

            assertEquals(emptyList(), commit.data.items)
            assertTrue(commit.resetProjection)
            assertEquals(emptyList(), commit.projectionDelta)
            assertEquals(3, commit.data.page)
            assertEquals(9, commit.data.pages)
        }
    }

    @Test
    fun fullyDeduplicatedAppendStillAdvancesServerNumbers() {
        for (append in listOf(Dedupe.AGAINST_OLD, Dedupe.ALL)) {
            val old = Row(1, "old")
            val pager = accumulator(appendDedupe = append, initial = page(listOf(old)))

            val commit = pager.commit(page(listOf(Row(1, "ignored")), page = 4, pages = 8, total = 20), PageWrite.APPEND)

            assertEquals(listOf(old), commit.data.items)
            assertEquals(4, commit.data.page)
            assertEquals(8, commit.data.pages)
            assertEquals(20, commit.data.total)
            assertFalse(commit.resetProjection)
            assertEquals(emptyList(), commit.projectionDelta)
        }
    }

    @Test
    fun updateCanChangeRemoveAddAndReorderRowsAndReplaceNumbersAndMeta() {
        val initial = page(listOf(Row(1, "first"), Row(2, "removed"), Row(3, "changed")))
        val pager = accumulator(initial = initial)
        val transformed = initial.copy(
            items = listOf(initial.items[2].copy(key = 4, label = "updated"), Row(5, "added"), initial.items[0]),
            page = 7, pages = 8, total = 42, meta = 99,
        )

        val result = pager.update { old ->
            assertSame(initial, old)
            transformed
        }

        assertSame(transformed, result)
        assertSame(result, pager.current)
        assertEquals(transformed, pager.current)
    }

    @Test
    fun updateRemovalAllowsAgainstOldAndAllToAppendThatKeyAgain() {
        for (append in listOf(Dedupe.AGAINST_OLD, Dedupe.ALL)) {
            val retained = Row(2, "retained")
            val pager = accumulator(appendDedupe = append)
            pager.commit(page(listOf(Row(1, "removed"), retained)), PageWrite.REPLACE)
            pager.update { it.copy(items = it.items.filter { row -> row.key != 1 }) }
            val restored = Row(1, "restored")

            val commit = pager.commit(page(listOf(restored)), PageWrite.APPEND)

            assertEquals(listOf(retained, restored), commit.data.items, "$append")
            assertFalse(commit.resetProjection)
            assertEquals(listOf(restored), commit.projectionDelta)
        }
    }

    @Test
    fun updateAddedAndChangedKeysBecomeTheBasisForDeduplication() {
        for (append in listOf(Dedupe.AGAINST_OLD, Dedupe.ALL)) {
            val pager = accumulator(appendDedupe = append)
            pager.commit(page(listOf(Row(1, "original"))), PageWrite.REPLACE)
            val changed = Row(5, "changed key")
            val added = Row(6, "added by update")
            pager.update { it.copy(items = listOf(added, changed)) }
            val restored = Row(1, "old key is free")

            val commit = pager.commit(page(listOf(Row(5, "ignored"), Row(6, "ignored"), restored)), PageWrite.APPEND)

            assertEquals(listOf(added, changed, restored), commit.data.items)
            assertEquals(listOf(restored), commit.projectionDelta)
        }
    }

    @Test
    fun updateDuplicatesTriggerAllCleanupAndAFullProjectionReset() {
        val first = Row(1, "first")
        val slot = Row(null, "slot")
        val pager = accumulator(replaceDedupe = Dedupe.ALL, appendDedupe = Dedupe.ALL)
        pager.commit(page(listOf(first)), PageWrite.REPLACE)
        pager.update { it.copy(items = it.items + listOf(Row(1, "duplicate from update"), slot)) }
        val next = Row(2, "next")

        val commit = pager.commit(page(listOf(next)), PageWrite.APPEND)

        assertEquals(listOf(first, slot, next), commit.data.items)
        assertTrue(commit.resetProjection)
        assertEquals(commit.data.items, commit.projectionDelta)
    }

    @Test
    fun previouslyPublishedListsRemainUnchangedAfterLaterOperations() {
        for (replace in Dedupe.entries) for (append in Dedupe.entries) {
            val pager = accumulator(replaceDedupe = replace, appendDedupe = append,
                initial = page(listOf(Row(9, "initial"))))
            val published = mutableListOf(pager.current.items to pager.current.items.toList())
            val replacement = pager.commit(page(listOf(Row(1, "first"), Row(1, "duplicate"))), PageWrite.REPLACE)
            published += replacement.data.items to replacement.data.items.toList()
            published += replacement.projectionDelta to replacement.projectionDelta.toList()
            val appended = pager.commit(page(listOf(Row(2, "appended"))), PageWrite.APPEND)
            published += appended.data.items to appended.data.items.toList()
            published += appended.projectionDelta to appended.projectionDelta.toList()
            pager.update { it.copy(items = it.items.reversed() + Row(3, "updated")) }
            published += pager.current.items to pager.current.items.toList()
            pager.commit(page(), PageWrite.REPLACE)
            pager.commit(page(listOf(Row(4, "last"))), PageWrite.APPEND)

            for ((list, snapshot) in published) assertEquals(snapshot, list, "$replace / $append")
            assertNotSame(replacement.data.items, appended.data.items)
        }
    }

    @Test
    fun everyCommitAllocatesANewListEvenWhenEmpty() {
        for (replace in Dedupe.entries) for (append in Dedupe.entries) {
            val pager = accumulator(replaceDedupe = replace, appendDedupe = append)
            val initialItems = pager.current.items
            val replacement = pager.commit(page(), PageWrite.REPLACE)
            val appended = pager.commit(page(), PageWrite.APPEND)

            assertNotSame(initialItems, replacement.data.items)
            assertNotSame(replacement.data.items, appended.data.items)
            assertEquals(emptyList(), replacement.data.items)
            assertEquals(emptyList(), appended.data.items)
        }
    }

    @Test
    fun committedListsAndDeltasDoNotAliasIncomingMutableLists() {
        val pager = accumulator(appendDedupe = Dedupe.NONE)
        val first = Row(1, "first")
        val incomingReplace = mutableListOf(first)
        val replacement = pager.commit(page(incomingReplace), PageWrite.REPLACE)
        incomingReplace.clear()
        assertEquals(listOf(first), replacement.data.items)
        assertEquals(listOf(first), replacement.projectionDelta)

        val next = Row(2, "next")
        val incomingAppend = mutableListOf(next)
        val appended = pager.commit(page(incomingAppend), PageWrite.APPEND)
        incomingAppend.clear()
        assertEquals(listOf(first, next), appended.data.items)
        assertEquals(listOf(next), appended.projectionDelta)
    }

    @Test
    fun noneModesNeverInspectKeys() {
        val pager = accumulator(replaceDedupe = Dedupe.NONE, appendDedupe = Dedupe.NONE,
            initial = page(listOf(Row(1, "initial"))), keyOf = { error("NONE must not inspect keys") })

        pager.commit(page(listOf(Row(1, "replace"), Row(1, "duplicate"))), PageWrite.REPLACE)
        pager.commit(page(listOf(Row(1, "append"))), PageWrite.APPEND)
        pager.update { it.copy(items = it.items.reversed()) }
        pager.commit(page(listOf(Row(null, "structural"))), PageWrite.APPEND)

        assertEquals(4, pager.current.items.size)
    }

    @Test
    fun replaceIndexesOnlyTheCommittedPageAndDistinctAppendsOnlyInspectIncomingRows() {
        for (replace in Dedupe.entries) for (append in listOf(Dedupe.AGAINST_OLD, Dedupe.ALL)) {
            val inspected = mutableListOf<Row>()
            val pager = accumulator(replaceDedupe = replace, appendDedupe = append,
                initial = page(listOf(Row(999, "discarded"))), keyOf = { inspected.add(it); it.key })
            val rows = List(100) { Row(it, "old $it") }

            pager.commit(page(rows), PageWrite.REPLACE)
            assertEquals(rows, inspected, "$replace / $append replacement")
            repeat(10) { index ->
                inspected.clear()
                val incoming = listOf(Row(0, "old key"), Row(100 + index, "new"), Row(null, "slot $index"))
                pager.commit(page(incoming), PageWrite.APPEND)
                assertEquals(incoming, inspected, "$replace / $append append $index")
            }
        }
    }

    @Test
    fun allAppendScansDirtyOldContentOnlyOnce() {
        var keyCalls = 0
        val pager = accumulator(appendDedupe = Dedupe.ALL, keyOf = { keyCalls += 1; it.key })
        val old = listOf(Row(1, "first"), Row(1, "duplicate"), Row(null, "slot"))
        pager.commit(page(old), PageWrite.REPLACE)
        keyCalls = 0

        val cleaned = pager.commit(page(listOf(Row(2, "new"))), PageWrite.APPEND)

        assertEquals(old.size + 1, keyCalls)
        assertTrue(cleaned.resetProjection)
        keyCalls = 0
        val next = pager.commit(page(listOf(Row(3, "next"))), PageWrite.APPEND)
        assertEquals(1, keyCalls)
        assertFalse(next.resetProjection)
    }

    @Test
    fun updatedIndexIsRebuiltOnceAndThenMaintainedIncrementally() {
        for (append in listOf(Dedupe.AGAINST_OLD, Dedupe.ALL)) {
            var keyCalls = 0
            val pager = accumulator(appendDedupe = append, keyOf = { keyCalls += 1; it.key })
            pager.commit(page(listOf(Row(1, "first"), Row(2, "removed"))), PageWrite.REPLACE)
            pager.update { it.copy(items = listOf(Row(1, "retained"), Row(3, "added"), Row(null, "slot"))) }
            keyCalls = 0

            pager.commit(page(listOf(Row(2, "restored"))), PageWrite.APPEND)
            assertEquals(4, keyCalls, "$append first append after update")
            keyCalls = 0
            pager.commit(page(listOf(Row(4, "next"))), PageWrite.APPEND)
            assertEquals(1, keyCalls, "$append incremental append")
        }
    }

    @Test
    fun seededRandomSequencesMatchNaiveReferencesForEveryModeCombination() {
        for (replace in Dedupe.entries) for (append in Dedupe.entries) repeat(12) { seed ->
            val random = Random(0x51A1 + seed)
            var expected = randomPage(random, "initial")
            val pager = accumulator(replaceDedupe = replace, appendDedupe = append, initial = expected)
            var projection = expected.items.toList()
            val published = mutableListOf(pager.current.items to pager.current.items.toList())
            repeat(160) { step ->
                val context = "replace=$replace append=$append seed=$seed step=$step"
                when (random.nextInt(3)) {
                    0, 1 -> {
                        val write = if (random.nextBoolean()) PageWrite.REPLACE else PageWrite.APPEND
                        val incoming = randomPage(random, "$seed-$step")
                        val old = expected
                        expected = referenceCommit(old, incoming, write, replace, append)
                        val commit = pager.commit(incoming, write)
                        val reset = write == PageWrite.REPLACE ||
                            (append == Dedupe.ALL && referenceDistinct(old.items) != old.items)
                        val delta = if (reset) expected.items else expected.items.drop(old.items.size)

                        assertEquals(reset, commit.resetProjection, context)
                        assertEquals(delta, commit.projectionDelta, context)
                        projection = if (commit.resetProjection) commit.projectionDelta.toList()
                            else projection + commit.projectionDelta
                        assertEquals(expected, commit.data, context)
                        assertSame(commit.data, pager.current, context)
                        published += commit.projectionDelta to commit.projectionDelta.toList()
                    }
                    else -> {
                        val transform = randomUpdate(random, "$seed-$step")
                        expected = transform(expected)
                        val updated = pager.update(transform)
                        assertEquals(expected, updated, context)
                        assertSame(updated, pager.current, context)
                        projection = updated.items.toList()
                    }
                }
                assertEquals(expected, pager.current, context)
                assertEquals(pager.current.items, projection, context)
                published += pager.current.items to pager.current.items.toList()
            }
            for ((list, snapshot) in published) assertEquals(snapshot, list, "$replace / $append seed=$seed published")
        }
    }

    private data class Row(val key: Int?, val label: String)

    private fun page(
        items: List<Row> = emptyList(),
        page: Int = 1,
        pages: Int = 10,
        total: Int = 100,
        meta: Int = 0,
    ): PageData<Row, Int> = PageData(items, page, pages, total, meta)

    private fun accumulator(
        replaceDedupe: Dedupe = Dedupe.NONE,
        appendDedupe: Dedupe = Dedupe.AGAINST_OLD,
        initial: PageData<Row, Int> = page(page = 0, pages = 0, total = 0),
        keyOf: (Row) -> Int? = Row::key,
        appendMeta: (Int, Int) -> Int = { old, incoming -> old + incoming },
    ): PageAccumulator<Row, Int, Int> = PageAccumulator(initial, keyOf, replaceDedupe, appendDedupe, appendMeta)

    private fun referenceDistinct(rows: List<Row>): List<Row> {
        val seen = mutableSetOf<Int>()
        return rows.filter { it.key?.let(seen::add) ?: true }
    }

    private fun referenceCommit(
        old: PageData<Row, Int>,
        incoming: PageData<Row, Int>,
        write: PageWrite,
        replaceDedupe: Dedupe,
        appendDedupe: Dedupe,
    ): PageData<Row, Int> {
        val items = if (write == PageWrite.REPLACE) {
            if (replaceDedupe == Dedupe.ALL) referenceDistinct(incoming.items) else incoming.items.toList()
        } else when (appendDedupe) {
            Dedupe.NONE -> old.items + incoming.items
            Dedupe.AGAINST_OLD -> {
                val seen = old.items.mapNotNull(Row::key).toSet()
                old.items + incoming.items.filter { it.key?.let { key -> key !in seen } ?: true }
            }
            Dedupe.ALL -> referenceDistinct(old.items + incoming.items)
        }
        return incoming.copy(items = items, meta = if (write == PageWrite.REPLACE) incoming.meta else old.meta + incoming.meta)
    }

    private fun randomRows(random: Random, prefix: String): List<Row> = List(random.nextInt(7)) { index ->
        Row(if (random.nextInt(4) == 0) null else random.nextInt(4), "$prefix-$index")
    }

    private fun randomPage(random: Random, prefix: String): PageData<Row, Int> = page(
        items = randomRows(random, prefix),
        page = random.nextInt(-2, 9),
        pages = random.nextInt(-1, 9),
        total = random.nextInt(-3, 25),
        meta = random.nextInt(6),
    )

    private fun randomUpdate(random: Random, prefix: String): (PageData<Row, Int>) -> PageData<Row, Int> {
        val action = random.nextInt(6)
        val key = if (random.nextInt(4) == 0) null else random.nextInt(4)
        val added = randomRows(random, "$prefix-update")
        val numbers = randomPage(random, "$prefix-numbers")
        return { old ->
            val items = when (action) {
                0 -> old.items.filter { it.key != key }
                1 -> old.items + added
                2 -> old.items.reversed()
                3 -> old.items.mapIndexed { index, row ->
                    if (index % 2 == 0) row.copy(key = key, label = "$prefix-changed-$index") else row
                }
                4 -> added + old.items
                else -> old.items.drop(1)
            }
            old.copy(items = items, page = numbers.page, pages = numbers.pages, total = numbers.total, meta = numbers.meta)
        }
    }
}
