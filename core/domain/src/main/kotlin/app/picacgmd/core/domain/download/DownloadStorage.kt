package app.picacgmd.core.domain.download

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI

/** Reader addresses are persisted, not reconstructed from whichever location is selected today. */
sealed interface DownloadedFileLocation {
    data class OnDisk(val file: File) : DownloadedFileLocation
    data class Document(val uri: String) : DownloadedFileLocation
}

internal data class DownloadStorageSelection(val root: String, val unavailableTree: String? = null)

class DownloadStorageAccessException(val treeUri: String) : IOException(
    "下载目录授权已失效或目录已移除，请在下载设置中重新选择原目录",
)

internal interface DownloadStorageBackend {
    val isDocumentTree: Boolean
    fun checkAccess(write: Boolean)
    fun exists(pathOrLocation: String): Boolean
    fun openRead(pathOrLocation: String): InputStream
    fun location(relativePath: String): String?
    fun localFile(relativePath: String): File?
    fun publish(relativePath: String, temporary: File, format: String?): String
    fun delete(pathOrLocation: String)
}

internal class FileDownloadBackend(private val root: File) : DownloadStorageBackend {
    override val isDocumentTree = false
    override fun checkAccess(write: Boolean) { if (write) root.ensureDirectory() }
    private fun file(path: String): File = if (path.startsWith("file:")) File(URI(path)) else {
        requireDownloadPath(path)
        File(root, path)
    }
    override fun exists(pathOrLocation: String) = file(pathOrLocation).let { it.isFile && it.length() > 0 }
    override fun openRead(pathOrLocation: String): InputStream = file(pathOrLocation).inputStream()
    override fun location(relativePath: String): String = file(relativePath).toURI().toString()
    override fun localFile(relativePath: String): File = file(relativePath)
    override fun publish(relativePath: String, temporary: File, format: String?): String {
        val destination = file(relativePath)
        destination.parentFile!!.ensureDirectory()
        atomicMove(temporary, destination)
        return destination.toURI().toString()
    }
    override fun delete(pathOrLocation: String) {
        val file = file(pathOrLocation)
        if (file.exists() && !file.deleteRecursively()) throw IOException("删除下载文件失败")
    }
}

internal data class DownloadDocument(val id: String, val name: String, val mimeType: String)

/** Android's DocumentsContract adapter is replaceable with an in-memory provider in JVM tests. */
internal interface DownloadTreeAccess {
    val rootDocumentId: String
    fun checkAccess(write: Boolean)
    fun children(parentId: String): List<DownloadDocument>
    fun create(parentId: String, name: String, mimeType: String): DownloadDocument
    fun exists(documentId: String): Boolean
    fun openRead(documentId: String): InputStream
    fun openWrite(documentId: String): OutputStream
    fun delete(documentId: String)
    fun location(documentId: String): String
    fun documentId(location: String): String
}

/** Each directory is enumerated once. Published page URIs bypass even this index when reading. */
internal class TreeDownloadBackend(private val access: DownloadTreeAccess) : DownloadStorageBackend {
    override val isDocumentTree = true
    private val lock = Any()
    private val children = mutableMapOf<String, MutableMap<String, DownloadDocument>>()
    private val directoryIds = mutableMapOf("" to access.rootDocumentId)

    override fun checkAccess(write: Boolean) = access.checkAccess(write)
    override fun localFile(relativePath: String): File? = null

    private fun childrenOf(id: String): MutableMap<String, DownloadDocument> = children.getOrPut(id) {
        access.children(id).associateBy { it.name }.toMutableMap()
    }

    private fun directory(path: String, create: Boolean): String? {
        directoryIds[path]?.let { return it }
        val parentPath = path.substringBeforeLast('/', "")
        val parent = directory(parentPath, create) ?: return null
        val name = path.substringAfterLast('/')
        val siblings = childrenOf(parent)
        val document = siblings[name] ?: if (create) access.create(parent, name, DIRECTORY_MIME).also {
            siblings[name] = it
        } else return null
        if (document.mimeType != DIRECTORY_MIME) throw IOException("下载目录被同名文件占用")
        directoryIds[path] = document.id
        return document.id
    }

    private fun find(path: String): DownloadDocument? {
        requireDownloadPath(path)
        val parent = directory(path.substringBeforeLast('/', ""), create = false) ?: return null
        return childrenOf(parent)[path.substringAfterLast('/')]
    }

    private fun id(pathOrLocation: String): String? = if (pathOrLocation.startsWith("content://")) {
        access.documentId(pathOrLocation)
    } else synchronized(lock) { find(pathOrLocation)?.id }

    override fun exists(pathOrLocation: String): Boolean {
        checkAccess(write = false)
        return id(pathOrLocation)?.let(access::exists) ?: false
    }

    override fun openRead(pathOrLocation: String): InputStream {
        checkAccess(write = false)
        return access.openRead(id(pathOrLocation) ?: throw IOException("未找到下载文件"))
    }

    override fun location(relativePath: String): String? {
        checkAccess(write = false)
        return id(relativePath)?.let(access::location)
    }

    override fun publish(relativePath: String, temporary: File, format: String?): String {
        requireDownloadPath(relativePath)
        checkAccess(write = true)
        val document = synchronized(lock) {
            val parent = checkNotNull(directory(relativePath.substringBeforeLast('/', ""), create = true))
            val name = relativePath.substringAfterLast('/')
            val siblings = childrenOf(parent)
            siblings[name]?.let { previous ->
                if (access.exists(previous.id)) access.delete(previous.id)
                siblings.remove(name)
            }
            access.create(parent, name, imageMimeType(format)).also { siblings[name] = it }
        }
        try {
            // The engine publishes this URI to Room only after the stream has closed successfully.
            // A process-death orphan is overwritten on retry, never treated as a completed cover/page.
            access.openWrite(document.id).use { output -> temporary.inputStream().use { it.copyTo(output, 64 * 1024) } }
            return access.location(document.id)
        } catch (exception: Exception) {
            try { access.delete(document.id) } catch (cleanup: Exception) { exception.addSuppressed(cleanup) }
            synchronized(lock) { children.values.forEach { it.entries.removeAll { entry -> entry.value.id == document.id } } }
            throw exception
        }
    }

    override fun delete(pathOrLocation: String) {
        checkAccess(write = true)
        synchronized(lock) {
            val id = id(pathOrLocation) ?: return
            if (access.exists(id)) access.delete(id)
            children.values.forEach { it.entries.removeAll { entry -> entry.value.id == id } }
            if (!pathOrLocation.startsWith("content://")) {
                directoryIds.keys.filter { it == pathOrLocation || it.startsWith("$pathOrLocation/") }.forEach {
                    directoryIds.remove(it)?.let(children::remove)
                }
            }
        }
    }

    companion object { private const val DIRECTORY_MIME = "vnd.android.document/directory" }
}

internal class DownloadDestination(
    private val backend: DownloadStorageBackend,
    private val relativePath: String,
    private val savedLocation: String? = null,
) {
    val localFile: File? get() = backend.localFile(relativePath)
    fun exists(): Boolean = if (backend.isDocumentTree && savedLocation == null) false else backend.exists(savedLocation ?: relativePath)
    fun openRead(): InputStream = backend.openRead(savedLocation ?: relativePath)
    fun publish(temporary: File, format: String?): String = backend.publish(relativePath, temporary, format)
    fun delete(location: String) = backend.delete(location)
}

internal fun requireDownloadPath(path: String) {
    require(path.isNotBlank() && '\\' !in path && path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) {
        "Invalid download path"
    }
}

private fun imageMimeType(format: String?): String = when (format) {
    "jpg", "jpeg" -> "image/jpeg"
    null -> "application/octet-stream"
    else -> "image/$format"
}
