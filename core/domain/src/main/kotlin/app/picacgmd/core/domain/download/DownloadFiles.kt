package app.picacgmd.core.domain.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** All files, including temporary files, remain in app-specific external storage. */
class DownloadFileStore(private val rootProvider: () -> File) {
    constructor(root: File) : this({ root })

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
            throw IOException("无法保存章节清单状态")
        }
    }

    fun removeInterruptedTemporaryFiles() {
        val root = rootDirectory()
        root.walkTopDown().filter { it.isFile && it.name.startsWith('.') && it.name.endsWith(".part") }
            .forEach { if (!it.delete()) throw IOException("无法清理未完成的下载文件") }
    }

    fun deleteComic(comicId: String) {
        val directory = comicDirectory(comicId)
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("无法删除下载文件")
    }

    internal fun createTemporaryFile(destination: File): File {
        destination.parentFile!!.ensureDirectory()
        return File.createTempFile(".${destination.name}.", ".part", destination.parentFile)
    }

    companion object {
        private const val ALL_EPISODES_MARKER = ".all-episodes"

        fun requireComicId(comicId: String) {
            require(comicId.isNotBlank() && comicId != "." && comicId != ".." &&
                '/' !in comicId && '\\' !in comicId) { "Invalid comic ID" }
        }
    }
}

internal data class StagedDownload(
    val temporaryFile: File,
    val destination: File,
    val bytes: Long,
    val format: String?,
) {
    fun commit() = atomicMove(temporaryFile, destination)
    fun discard() { temporaryFile.delete() }
}

/** Streams on OkHttp's I/O threads. Cancellation closes the socket and discards the partial file. */
internal class StreamingImageDownloader(
    private val clientProvider: () -> OkHttpClient,
    private val files: DownloadFileStore,
    private val onBytes: (Int) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun download(url: String, destination: File): StagedDownload = suspendCancellableCoroutine { continuation ->
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
                        if (bytes == 0L) throw IOException("服务器返回了空图片")
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
internal fun imageFormat(file: File, contentType: String? = null): String? {
    val header = file.inputStream().use { input ->
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
        else -> throw IOException("服务器返回了非图片内容")
    }
}

private fun atomicMove(source: File, destination: File) {
    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

private fun File.ensureDirectory() {
    if (!isDirectory && !mkdirs() && !isDirectory) throw IOException("无法创建下载目录")
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
