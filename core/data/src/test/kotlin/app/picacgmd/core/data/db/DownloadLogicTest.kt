package app.picacgmd.core.data.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadLogicTest {
    @Test
    fun statusTransitionsMatchTheQueueContract() {
        val allowed = mapOf(
            DownloadStatus.QUEUED to setOf(DownloadStatus.RUNNING, DownloadStatus.PAUSED, DownloadStatus.DELETING),
            DownloadStatus.RUNNING to setOf(DownloadStatus.QUEUED, DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.DONE, DownloadStatus.DELETING),
            DownloadStatus.PAUSED to setOf(DownloadStatus.QUEUED, DownloadStatus.DELETING),
            DownloadStatus.FAILED to setOf(DownloadStatus.QUEUED, DownloadStatus.PAUSED, DownloadStatus.DELETING),
            DownloadStatus.DONE to setOf(DownloadStatus.QUEUED, DownloadStatus.DELETING),
            DownloadStatus.DELETING to emptySet(),
        )
        for (from in DownloadStatus.entries) {
            for (to in DownloadStatus.entries) {
                val expected = from == to || to in allowed.getValue(from)
                assertEquals(expected, from.canTransitionTo(to), "$from -> $to")
                if (expected) assertEquals(to, from.transitionTo(to))
                else assertFailsWith<IllegalArgumentException> { from.transitionTo(to) }
            }
        }
    }

    @Test
    fun persistedStatusStringsRoundTripAndRejectUnknownStates() {
        val converters = DownloadStatusConverters()
        assertEquals(listOf("queued", "running", "paused", "failed", "done", "deleting"), DownloadStatus.entries.map(converters::encode))
        DownloadStatus.entries.forEach { assertEquals(it, converters.decode(converters.encode(it))) }
        assertFailsWith<IllegalArgumentException> { converters.decode("unknown") }
    }

    @Test
    fun appPrivatePathsUseEpisodeAndOneBasedPageWithoutSuffixes() {
        assertEquals("comic/logo", DownloadComic("comic", "标题", createdAt = 0).thumbFilePath)
        assertEquals("comic/2", DownloadEp("ep", "comic", 2, "第二话", createdAt = 0).filePath)
        assertEquals("comic/2/3", picture().filePath)
    }

    @Test
    fun onlyResultsForTheCurrentRunningClaimAreAccepted() {
        val running = picture().copy(status = DownloadStatus.RUNNING, attemptCount = 2)
        val success = running.copy(status = DownloadStatus.DONE)
        assertTrue(running.acceptsResult(success))
        assertTrue(running.acceptsResult(success.copy(status = DownloadStatus.FAILED)))
        assertFalse(running.acceptsResult(success.copy(attemptCount = 1)))
        assertFalse(running.copy(status = DownloadStatus.PAUSED).acceptsResult(success))
        assertFalse(running.copy(status = DownloadStatus.DELETING).acceptsResult(success))
        assertFalse(running.acceptsResult(success.copy(pictureId = "other")))
        assertFalse(running.acceptsResult(success.copy(status = DownloadStatus.RUNNING)))
    }

    private fun picture(): DownloadPicture = DownloadPicture(
        pictureId = "picture", comicId = "comic", epId = "ep", epOrder = 2, pictureIndex = 3,
        fileServer = "https://images.example.com", path = "image.jpg", createdAt = 0,
    )
}
