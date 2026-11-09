package app.picacgmd.core.domain.download

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal class FakeDownloadTreeAccess(val treeUri: String = TREE) : DownloadTreeAccess {
    override val rootDocumentId = "root"
    private data class Entry(val parent: String, val document: DownloadDocument)
    private val entries = linkedMapOf<String, Entry>()
    private val bytes = mutableMapOf<String, ByteArray>()
    private var nextId = 0
    var readable = true
    var writable = true
    var failNextWrite = false
    val directoryScans = mutableMapOf<String, Int>()

    override fun checkAccess(write: Boolean) {
        if (!readable || write && !writable) throw DownloadStorageAccessException(treeUri)
    }
    override fun children(parentId: String): List<DownloadDocument> {
        directoryScans[parentId] = (directoryScans[parentId] ?: 0) + 1
        return entries.values.filter { it.parent == parentId }.map { it.document }
    }
    override fun create(parentId: String, name: String, mimeType: String): DownloadDocument {
        checkAccess(write = true)
        val document = DownloadDocument("doc-${++nextId}", name, mimeType)
        entries[document.id] = Entry(parentId, document)
        return document
    }
    override fun exists(documentId: String): Boolean = documentId == rootDocumentId || documentId in entries
    override fun openRead(documentId: String): InputStream = ByteArrayInputStream(bytes[documentId] ?: throw IOException("Missing document"))
    override fun openWrite(documentId: String): OutputStream {
        checkAccess(write = true)
        return object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (failNextWrite) {
                    failNextWrite = false
                    throw IOException("No space left on device")
                }
                super.write(buffer, offset, length)
            }
            override fun close() { bytes[documentId] = toByteArray(); super.close() }
        }
    }
    override fun delete(documentId: String) {
        checkAccess(write = true)
        entries.values.filter { it.parent == documentId }.map { it.document.id }.forEach(::delete)
        entries.remove(documentId)
        bytes.remove(documentId)
    }
    override fun location(documentId: String): String = "$treeUri/document/$documentId"
    override fun documentId(location: String): String = location.substringAfterLast('/')

    companion object { const val TREE = "content://fake/tree/root" }
}
