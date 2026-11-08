package app.picacgmd.core.domain.image

import android.content.Context
import android.content.pm.ApplicationInfo
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.map.Mapper
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.util.DebugLogger
import app.picacgmd.core.domain.isLowRamDevice
import app.picacgmd.core.domain.session.NetworkConfigProvider
import app.picacgmd.core.network.PicaImage
import okhttp3.Call
import okhttp3.Request
import okio.Path.Companion.toOkioPath

/** One loader/cache per application; its fetcher picks up image-client changes for new requests. */
fun createImageLoader(
    context: Context,
    networkConfigProvider: NetworkConfigProvider,
    maxDiskCacheBytes: Long = 512L * 1024 * 1024,
    lowRamDevice: Boolean = isLowRamDevice(context),
): ImageLoader {
    require(maxDiskCacheBytes > 0) { "图片磁盘缓存大小必须大于 0" }
    // Coil memoizes its network client, so delegate each new Call instead of capturing an old client.
    val imageCalls = object : Call.Factory {
        override fun newCall(request: Request): Call = networkConfigProvider.current.imageClient.newCall(request)
    }
    val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    return ImageLoader.Builder(context.applicationContext)
        .allowRgb565(lowRamDevice)
        .allowHardware(true)
        // Debug builds log failed requests (URL + cause) to logcat under the "ImageLoader" tag.
        .apply { if (debuggable) logger(DebugLogger()) }
        .components {
            add(PicaImageMapper(networkConfigProvider))
            add(OkHttpNetworkFetcherFactory(callFactory = { imageCalls }))
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.applicationContext.cacheDir.resolve("coil_images").toOkioPath())
                .maxSizeBytes(maxDiskCacheBytes)
                .build()
        }
        .build()
}

class PicaImageMapper(private val networkConfigProvider: NetworkConfigProvider) : Mapper<PicaImage, String> {
    override fun map(data: PicaImage, options: Options): String = networkConfigProvider.imageUrl(data)
}
