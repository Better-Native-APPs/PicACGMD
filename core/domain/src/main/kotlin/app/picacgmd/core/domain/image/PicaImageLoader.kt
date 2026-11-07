package app.picacgmd.core.domain.image

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.map.Mapper
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
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
): ImageLoader {
    require(maxDiskCacheBytes > 0) { "图片磁盘缓存大小必须大于 0" }
    // Coil memoizes its network client, so delegate each new Call instead of capturing an old client.
    val imageCalls = object : Call.Factory {
        override fun newCall(request: Request): Call = networkConfigProvider.current.imageClient.newCall(request)
    }
    return ImageLoader.Builder(context.applicationContext)
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
