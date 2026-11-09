package app.picacgmd.feature.reader.ui

import app.picacgmd.feature.reader.PageImage
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadedPageImageTest {
    @Test
    fun persistedDocumentUrisAreCoilModelsAndFailAsLocalFiles() {
        val uri = "content://documents/tree/root/document/page-1"
        val image = PageImage.Document(uri)
        assertEquals(uri, image.coilModel)
        assertEquals(PageFailure.LocalFile, pageFailureOf(SecurityException("Permission revoked"), image))
        assertEquals(PageFailure.LocalFile, pageFailureOf(IOException("Missing document"), image))
        val file = File("downloaded-page")
        assertEquals(file, PageImage.Local(file).coilModel)
    }
}
