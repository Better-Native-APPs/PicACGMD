package app.picacgmd.feature.reader.media

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.R
import app.picacgmd.feature.reader.logic.CropRect
import app.picacgmd.feature.reader.logic.PAGE_FILE_HEADER_SIZE
import app.picacgmd.feature.reader.logic.PageFileType
import app.picacgmd.feature.reader.logic.pageFileName
import app.picacgmd.feature.reader.logic.sniffPageFileType
import app.picacgmd.feature.reader.logic.toPixelRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import coil3.size.Size as CoilSize

/** Lets other apps read the page the user chose to share, and nothing else. */
class ReaderFileProvider : FileProvider(R.xml.reader_file_paths)

/** What names a page on disk. */
internal data class PageLabel(val comicTitle: String, val chapterTitle: String, val pageNumber: Int)

/** Pixel size and file size of a page's original image. */
internal data class PageFileInfo(val width: Int, val height: Int, val bytes: Long)

private const val GALLERY_FOLDER = "PicACG"
private const val SHARE_FOLDER = "reader_share"
private const val CROP_SUFFIX = "-crop"
private const val JPEG_QUALITY = 95

private fun fileAuthority(context: Context) = "${context.packageName}.reader.files"

/** Size of the page's original file, if it is on disk already. Never touches the network. */
internal suspend fun readPageFileInfo(context: Context, image: PageImage): PageFileInfo? =
    guarded(null) {
        withPageFile(context, image, download = false) { file ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            PageFileInfo(bounds.outWidth.coerceAtLeast(0), bounds.outHeight.coerceAtLeast(0), file.length())
        }
    }

/** 保存到相册: copies the original file into `Pictures/PicACG`; needs no storage permission. */
internal suspend fun savePageToGallery(context: Context, image: PageImage, label: PageLabel): Boolean =
    guarded(false) {
        withPageFile(context, image) { file ->
            val type = file.pageFileType()
            writeToGallery(context, label.fileName(type), type) { out -> file.inputStream().use { it.copyTo(out) } }
        } ?: false
    }

/** 分享: copies the original file where the file provider can serve it, then opens the share sheet. */
internal suspend fun sharePage(context: Context, image: PageImage, label: PageLabel): Boolean =
    guarded(false) {
        val shared = withPageFile(context, image) { file ->
            val type = file.pageFileType()
            val target = shareTarget(context, label.fileName(type))
            file.copyTo(target, overwrite = true)
            target to type
        } ?: return@guarded false
        openShareSheet(context, shared.first, shared.second)
        true
    }

/** Saves the part of the page inside [crop] to the gallery, cut from the original file. */
internal suspend fun saveCropToGallery(context: Context, image: PageImage, label: PageLabel, crop: CropRect): Boolean =
    guarded(false) {
        withPageFile(context, image) { file ->
            val type = file.pageFileType().cropOutput()
            val bitmap = decodeCrop(file, crop) ?: return@withPageFile false
            try {
                writeToGallery(context, label.fileName(type, CROP_SUFFIX), type) { out -> bitmap.compressTo(type, out) }
            } finally {
                bitmap.recycle()
            }
        } ?: false
    }

/** Shares the part of the page inside [crop], cut from the original file. */
internal suspend fun shareCrop(context: Context, image: PageImage, label: PageLabel, crop: CropRect): Boolean =
    guarded(false) {
        val shared = withPageFile(context, image) { file ->
            val type = file.pageFileType().cropOutput()
            val bitmap = decodeCrop(file, crop) ?: return@withPageFile null
            try {
                val target = shareTarget(context, label.fileName(type, CROP_SUFFIX))
                target.outputStream().use { out -> bitmap.compressTo(type, out) }
                target to type
            } finally {
                bitmap.recycle()
            }
        } ?: return@guarded false
        openShareSheet(context, shared.first, shared.second)
        true
    }

/** Anything going wrong with a file becomes [fallback]; the reader tells the user it did not work. */
private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        fallback
    } catch (_: IllegalArgumentException) {
        // Includes a share target outside the provider's paths and malformed cache entries.
        fallback
    } catch (_: SecurityException) {
        fallback
    } catch (_: IllegalStateException) {
        fallback
    }

/**
 * Runs [block] with the original file of the page, off the main thread. A remote page is read straight
 * from Coil's disk cache and is only valid inside [block]. With [download], a remote page that is not
 * cached is fetched first, or re-encoded from the decoded image if the loader keeps no disk cache.
 */
private suspend fun <T> withPageFile(
    context: Context,
    image: PageImage,
    download: Boolean = true,
    block: (File) -> T,
): T? = withContext(Dispatchers.IO) {
    when (image) {
        is PageImage.Local -> if (image.file.isFile) block(image.file) else null
        is PageImage.Remote -> {
            val loader = SingletonImageLoader.get(context)
            val cached = loader.readCached(image.url, block)
            when {
                cached != null -> cached.value
                !download -> null
                else -> fetchThenRead(context, loader, image.url, block)
            }
        }
    }
}

/** A wrapper so that a null result from the block is not mistaken for a cache miss. */
private class Found<T>(val value: T)

private fun <T> ImageLoader.readCached(url: String, block: (File) -> T): Found<T>? =
    diskCache?.openSnapshot(url)?.use { snapshot -> Found(block(snapshot.data.toFile())) }

private suspend fun <T> fetchThenRead(context: Context, loader: ImageLoader, url: String, block: (File) -> T): T? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(CoilSize.ORIGINAL)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .allowHardware(false)
        .build()
    val result = loader.execute(request) as? SuccessResult ?: return null
    loader.readCached(url, block)?.let { return it.value }
    val temporary = File.createTempFile("page", ".png", context.cacheDir)
    return try {
        temporary.outputStream().use { out -> result.image.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, out) }
        block(temporary)
    } finally {
        temporary.delete()
    }
}

private fun File.pageFileType(): PageFileType {
    val header = ByteArray(PAGE_FILE_HEADER_SIZE)
    val read = inputStream().use { it.read(header) }
    return sniffPageFileType(if (read <= 0) ByteArray(0) else header.copyOf(read))
}

/** Lossless pages stay lossless; everything else (WebP, GIF frames) is written as JPEG. */
private fun PageFileType.cropOutput(): PageFileType = if (this == PageFileType.Png) PageFileType.Png else PageFileType.Jpeg

private fun PageLabel.fileName(type: PageFileType, suffix: String = ""): String =
    pageFileName(comicTitle, chapterTitle, pageNumber, type.extension, suffix)

private fun Bitmap.compressTo(type: PageFileType, out: OutputStream) {
    val format = if (type == PageFileType.Png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    if (!compress(format, JPEG_QUALITY, out)) throw IOException("Could not encode the image")
}

/** Decodes only the cropped region, so a very large page does not have to fit in memory whole. */
private fun decodeCrop(file: File, crop: CropRect): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val pixels = crop.toPixelRect(bounds.outWidth, bounds.outHeight)
    val region = Rect(pixels.left, pixels.top, pixels.right, pixels.bottom)
    return try {
        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(file.path)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(file.path, false)
        }
        try {
            decoder.decodeRegion(region, null)
        } finally {
            decoder.recycle()
        }
    } catch (_: IOException) {
        // Formats the region decoder cannot read, such as GIF.
        val whole = BitmapFactory.decodeFile(file.path) ?: return null
        val part = Bitmap.createBitmap(whole, pixels.left, pixels.top, pixels.width, pixels.height)
        if (part !== whole) whole.recycle()
        part
    }
}

private fun writeToGallery(context: Context, name: String, type: PageFileType, write: (OutputStream) -> Unit): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, type.mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val item = resolver.insert(collection, values) ?: return false
    return try {
        val stream = resolver.openOutputStream(item) ?: throw IOException("No output stream for $item")
        stream.use(write)
        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        resolver.update(item, done, null, null)
        true
    } catch (error: IOException) {
        resolver.delete(item, null, null)
        throw error
    }
}

/** Only the latest shared page is kept; older copies are no longer needed by anyone. */
private fun shareTarget(context: Context, name: String): File {
    val folder = File(context.cacheDir, SHARE_FOLDER)
    folder.mkdirs()
    folder.listFiles()?.forEach(File::delete)
    return File(folder, name)
}

private fun openShareSheet(context: Context, file: File, fileType: PageFileType) {
    val uri = FileProvider.getUriForFile(context, fileAuthority(context), file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = fileType.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, null)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
