package app.picacgmd.core.domain.download

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadFileStoreTest {
    @Test
    fun interruptedFilesAreCleanedOnlyOnceAcrossStoreInstancesForTheSameRoot() {
        val root = Files.createTempDirectory("pikapika-cleanup-test-").toFile()
        try {
            val interrupted = File(root, "comic/1/.1.interrupted.part")
            interrupted.parentFile!!.mkdirs()
            interrupted.writeText("partial")
            val picture = File(root, "comic/1/1").apply { writeText("complete") }
            val marker = File(root, "comic/.all-episodes").apply { writeText("request") }
            DownloadFileStore(root).removeInterruptedTemporaryFiles()
            assertFalse(interrupted.exists())
            assertTrue(picture.exists())
            assertTrue(marker.exists())

            val liveTemporary = File(root, "comic/1/.2.live.part").apply { writeText("live transfer") }
            DownloadFileStore(root).removeInterruptedTemporaryFiles()
            assertTrue(liveTemporary.exists(), "Later sessions must not rescan or delete new temporary files")
        } finally {
            root.deleteRecursively()
        }
    }
}
