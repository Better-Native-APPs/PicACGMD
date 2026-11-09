package app.picacgmd.core.domain.download

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Persisted tree grants only; no filesystem path guessing or all-files permission. */
internal class AndroidDownloadTreeAccess(
    private val resolver: ContentResolver,
    private val tree: Uri,
) : DownloadTreeAccess {
    init { require(DocumentsContract.isTreeUri(tree)) { "请选择下载文件夹" } }
    override val rootDocumentId: String = DocumentsContract.getTreeDocumentId(tree)
    private var checkedWritableRoot = false

    override fun checkAccess(write: Boolean) = access {
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == tree } ?: throw permissionError()
        if (!grant.isReadPermission || write && !grant.isWritePermission) throw permissionError()
        if (write && !checkedWritableRoot) synchronized(this) {
            resolver.query(uri(rootDocumentId), arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS),
                null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR ||
                    cursor.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) throw permissionError()
            } ?: throw permissionError()
            checkedWritableRoot = true
        }
    }

    override fun children(parentId: String): List<DownloadDocument> = access {
        val result = mutableListOf<DownloadDocument>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) result += DownloadDocument(cursor.getString(0), cursor.getString(1), cursor.getString(2))
        } ?: throw IOException("读取下载目录失败")
        result
    }

    override fun create(parentId: String, name: String, mimeType: String): DownloadDocument = access {
        val created = DocumentsContract.createDocument(resolver, uri(parentId), mimeType, name) ?: throw IOException("创建下载文件失败")
        DownloadDocument(DocumentsContract.getDocumentId(created), name, mimeType)
    }

    override fun exists(documentId: String): Boolean = access {
        resolver.query(uri(documentId), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    }

    override fun openRead(documentId: String): InputStream = access {
        resolver.openInputStream(uri(documentId)) ?: throw IOException("无法读取下载文件")
    }

    override fun openWrite(documentId: String): OutputStream = access {
        resolver.openOutputStream(uri(documentId), "wt") ?: throw IOException("无法写入下载文件")
    }

    override fun delete(documentId: String) = access {
        if (!DocumentsContract.deleteDocument(resolver, uri(documentId))) throw IOException("删除下载文件失败")
    }

    override fun location(documentId: String): String = uri(documentId).toString()
    override fun documentId(location: String): String = DocumentsContract.getDocumentId(Uri.parse(location))
    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    private fun permissionError() = DownloadStorageAccessException(tree.toString())
    private inline fun <T> access(block: () -> T): T = try { block() } catch (_: SecurityException) { throw permissionError() }
}
