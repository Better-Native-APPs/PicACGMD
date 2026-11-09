package app.picacgmd.core.domain.download

import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadPicture
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Legacy file paths, root-pinned new downloads, and app-private staging for SAF streams. */
class DownloadFileStore(private val rootProvider: () -> File) {
    constructor(root: File) : this({ root })
    private var internalRootProvider: () -> File = rootProvider
    private var stagingRootProvider: () -> File = { File(rootDirectory(), ".staging") }
    private var treeBackendProvider: ((String) -> DownloadStorageBackend)? = null
    private val backends = ConcurrentHashMap<String, DownloadStorageBackend>()
    private val mutableStorageNotice = MutableStateFlow<String?>(null)
    val storageNotice = mutableStorageNotice.asStateFlow()

    internal constructor(
        rootProvider: () -> File,
        internalRootProvider: () -> File,
        stagingRootProvider: () -> File,
        treeBackendProvider: (String) -> DownloadStorageBackend,
    ) : this(rootProvider) {
        this.internalRootProvider = internalRootProvider
        this.stagingRootProvider = stagingRootProvider
        this.treeBackendProvider = treeBackendProvider
    }

    private fun backend(root: String?): DownloadStorageBackend {
        val key = root ?: rootDirectory().toURI().toString()
        return backends.computeIfAbsent(key) {
            if (it.startsWith("content://")) treeBackendProvider?.invoke(it) ?: throw DownloadStorageAccessException(it)
            else FileDownloadBackend(File(URI(it)))
        }
    }

    internal fun selectStorageRoot(treeUri: String?): DownloadStorageSelection {
        if (treeUri != null) {
            try {
                backend(treeUri).checkAccess(write = true)
                return DownloadStorageSelection(treeUri)
            } catch (exception: DownloadStorageAccessException) {
                reportStorageAccessFailure(exception)
            }
        }
        val root = internalRootProvider().also { it.ensureDirectory() }.toURI().toString()
        return DownloadStorageSelection(root, unavailableTree = treeUri)
    }

    internal fun validateTree(treeUri: String) { backend(treeUri).checkAccess(write = true) }

    fun reportStorageAccessFailure(exception: DownloadStorageAccessException) {
        backends.remove(exception.treeUri)
        mutableStorageNotice.value = "下载目录授权已失效，新下载将使用应用内部存储；已有下载请重新授权原目录"
    }

    fun clearStorageNotice() { mutableStorageNotice.value = null }

    fun requireReadAccess(comic: DownloadComic) {
        try { backend(comic.storageRoot).checkAccess(write = false) } catch (exception: DownloadStorageAccessException) {
            reportStorageAccessFailure(exception)
            throw exception
        }
    }

    internal fun coverDestination(comic: DownloadComic): DownloadDestination {
        val storage = backend(comic.storageRoot)
        storage.checkAccess(write = true)
        return DownloadDestination(storage, "${comic.comicId}/logo", comic.thumbFilePath.takeIf(::isStoredLocation))
    }

    internal fun pictureDestination(comic: DownloadComic, picture: DownloadPicture): DownloadDestination {
        val storage = backend(comic.storageRoot)
        storage.checkAccess(write = true)
        return DownloadDestination(storage, "${comic.comicId}/${picture.epOrder}/${picture.pictureIndex}",
            picture.filePath.takeIf(::isStoredLocation))
    }

    fun readLocation(comic: DownloadComic, path: String): DownloadedFileLocation = when {
        path.startsWith("content://") -> DownloadedFileLocation.Document(path)
        path.startsWith("file:") -> DownloadedFileLocation.OnDisk(File(URI(path)))
        comic.storageRoot?.startsWith("content://") == true -> DownloadedFileLocation.Document(
            backend(comic.storageRoot).location(path) ?: throw IOException("未找到下载文件"),
        )
        else -> DownloadedFileLocation.OnDisk(checkNotNull(backend(comic.storageRoot).localFile(path)))
    }

    fun coverLocation(comic: DownloadComic): DownloadedFileLocation? {
        requireReadAccess(comic)
        if (comic.storageRoot?.startsWith("content://") == true && !isStoredLocation(comic.thumbFilePath)) return null
        val location = readLocation(comic, comic.thumbFilePath)
        return when (location) {
            is DownloadedFileLocation.OnDisk -> location.takeIf { it.file.isFile && it.file.length() > 0 }
            is DownloadedFileLocation.Document -> location.takeIf { backend(comic.storageRoot).exists(it.uri) }
        }
    }

    fun rootDirectory(): File = rootProvider().also { it.ensureDirectory() }

    fun comicDirectory(comicId: String): File {
        requireComicId(comicId)
        return File(rootDirectory(), comicId)
    }

    fun coverFile(comicId: String): File = File(comicDirectory(comicId), "logo")

    fun pictureFile(comicId: String, epOrder: Int, pictureIndex: Int): File {
        require(epOrder > 0 && pictureIndex > 0) { "Chapter order and picture index must be one-based" }
        return File(comicDirectory(comicId), "$epOrder/$pictureIndex")
    }

    /** The marker preserves an 'all chapters' request when appending to an existing partial download. */
    fun requestAllEpisodes(comicId: String) {
        val marker = File(comicDirectory(comicId), ALL_EPISODES_MARKER)
        val staged = createTemporaryFile(marker)
        try {
            FileOutputStream(staged).use {
                it.write(UUID.randomUUID().toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            atomicMove(staged, marker)
        } finally {
            staged.delete()
        }
    }

    fun allEpisodesRequest(comicId: String): String? =
        File(comicDirectory(comicId), ALL_EPISODES_MARKER).takeIf { it.isFile }?.readText()

    fun finishAllEpisodesRequest(comicId: String, expected: String?) {
        val marker = File(comicDirectory(comicId), ALL_EPISODES_MARKER)
        if (expected != null && marker.isFile && marker.readText() == expected && !marker.delete()) {
            throw IOException("保存章节清单状态失败")
        }
    }

    /** Once per process/root; new sessions must not rescan a large library or delete live staging files. */
    fun removeInterruptedTemporaryFiles() {
        synchronized(temporaryFileCleanupRoots) {
            for (root in listOf(rootDirectory(), internalRootProvider(), stagingRootProvider()).map { it.absoluteFile }.distinct()) {
                if (root !in temporaryFileCleanupRoots) {
                    root.walkTopDown().filter { it.isFile && it.name.startsWith('.') && it.name.endsWith(".part") }
                        .forEach { if (!it.delete()) throw IOException("无法清理未完成的下载文件") }
                    temporaryFileCleanupRoots.add(root)
                }
            }
        }
    }

    fun deleteComic(comicId: String) {
        val directory = comicDirectory(comicId)
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("删除下载文件失败")
    }

    fun deleteChapter(comicId: String, epOrder: Int) {
        require(epOrder > 0) { "Chapter order must be one-based" }
        val directory = File(comicDirectory(comicId), epOrder.toString())
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("删除章节文件失败")
    }

    fun deleteComic(comic: DownloadComic) {
        backend(comic.storageRoot).delete(comic.comicId)
        // All-chapters request markers are app-owned even when the actual pages live in a tree.
        if (comic.storageRoot != null) deleteComic(comic.comicId)
    }

    fun deleteChapter(comic: DownloadComic, epOrder: Int) {
        require(epOrder > 0) { "Chapter order must be one-based" }
        backend(comic.storageRoot).delete("${comic.comicId}/$epOrder")
    }

    internal fun createTemporaryFile(destination: DownloadDestination): File {
        destination.localFile?.let { return createTemporaryFile(it) }
        val directory = stagingRootProvider().also { it.ensureDirectory() }
        return File.createTempFile(".download.", ".part", directory)
    }

    internal fun createTemporaryFile(destination: File): File {
        destination.parentFile!!.ensureDirectory()
        return File.createTempFile(".${destination.name}.", ".part", destination.parentFile)
    }

    companion object {
        private const val ALL_EPISODES_MARKER = ".all-episodes"
        private val temporaryFileCleanupRoots = mutableSetOf<File>()
        private fun isStoredLocation(path: String): Boolean = path.startsWith("content://") || path.startsWith("file:")

        fun requireComicId(comicId: String) {
            require(comicId.isNotBlank() && comicId != "." && comicId != ".." &&
                '/' !in comicId && '\\' !in comicId) { "Invalid comic ID" }
        }
    }
}

internal data class StagedDownload(
    val temporaryFile: File,
    val destination: DownloadDestination,
    val bytes: Long,
    val format: String?,
) {
    private var committedLocation: String? = null
    fun commit(): String = destination.publish(temporaryFile, format).also { committedLocation = it }
    fun deleteCommitted() { committedLocation?.let(destination::delete) }
    fun discard() { temporaryFile.delete() }
}

/** Streams on OkHttp's I/O threads. Cancellation closes the socket and discards the partial file. */
internal class StreamingImageDownloader(
    private val clientProvider: () -> OkHttpClient,
    private val files: DownloadFileStore,
    private val onBytes: (Int) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun download(url: String, destination: DownloadDestination): StagedDownload = suspendCancellableCoroutine { continuation ->
        val call = clientProvider().newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                var temporary: File? = null
                try {
                    val staged = response.use {
                        if (!it.isSuccessful) {
                            throw DownloadHttpException(it.code, retryAfterMillis(it.header("Retry-After"), nowMillis()))
                        }
                        if (!continuation.isActive) return
                        val file = files.createTemporaryFile(destination)
                        temporary = file
                        var bytes = 0L
                        it.body.byteStream().use { input ->
                            FileOutputStream(file).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("下载已取消")
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    bytes += count
                                    onBytes(count)
                                }
                                output.fd.sync()
                            }
                        }
                        if (bytes == 0L) throw IOException("下载失败：服务器返回空图片")
                        val format = imageFormat(file, it.header("Content-Type"))
                        StagedDownload(file, destination, bytes, format)
                    }
                    continuation.resume(staged) { _, value, _ -> value.discard() }
                    temporary = null
                } catch (exception: Exception) {
                    continuation.resumeWithException(exception)
                } finally {
                    temporary?.delete()
                }
            }
        })
    }
}

/** Only inspect a small header; never decode a bitmap or load the complete image into memory. */
internal fun imageFormat(file: File, contentType: String? = null): String? = imageFormat(file.inputStream(), contentType)

internal fun imageFormat(stream: InputStream, contentType: String? = null): String? {
    val header = stream.use { input ->
        val buffer = ByteArray(32)
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) break
            size += count
        }
        buffer.copyOf(size)
    }
    fun prefix(vararg bytes: Int): Boolean = header.size >= bytes.size &&
        bytes.indices.all { (header[it].toInt() and 0xff) == bytes[it] }
    val ascii = header.toString(Charsets.ISO_8859_1)
    return when {
        prefix(0xff, 0xd8, 0xff) -> "jpg"
        prefix(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "png"
        ascii.startsWith("GIF87a") || ascii.startsWith("GIF89a") -> "gif"
        ascii.length >= 12 && ascii.startsWith("RIFF") && ascii.substring(8, 12) == "WEBP" -> "webp"
        ascii.length >= 12 && ascii.substring(4, 8) == "ftyp" &&
            ("avif" in ascii || "avis" in ascii) -> "avif"
        ascii.startsWith("BM") -> "bmp"
        contentType?.substringBefore(';')?.trim()?.startsWith("image/") == true ->
            contentType.substringBefore(';').substringAfter('/').trim().let { if (it == "jpeg") "jpg" else it }
        else -> throw IOException("下载失败：返回非图片内容")
    }
}

internal fun atomicMove(source: File, destination: File) {
    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

internal fun File.ensureDirectory() {
    if (!isDirectory && !mkdirs() && !isDirectory) throw IOException("创建下载目录失败")
}

private fun retryAfterMillis(value: String?, now: Long): Long? {
    if (value == null) return null
    value.toLongOrNull()?.let { return it.coerceIn(0, Long.MAX_VALUE / 1_000) * 1_000 }
    return try {
        (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now)
            .coerceAtLeast(0)
    } catch (_: DateTimeParseException) {
        null
    }
}
