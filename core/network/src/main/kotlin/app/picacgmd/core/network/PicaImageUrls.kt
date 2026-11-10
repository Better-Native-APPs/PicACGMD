package app.picacgmd.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl

sealed interface ImageServer {
    data object Original : ImageServer
    data object Split1 : ImageServer
    data object Split2 : ImageServer
    data object Split3 : ImageServer
}

object PicaImageUrls {
    fun build(image: PicaImage, server: ImageServer = ImageServer.Original): String =
        build(image.fileServer, image.path, server)

    /** Accepts servers/paths already containing /static/ without duplicating that segment. */
    fun build(fileServer: String, path: String, server: ImageServer = ImageServer.Original): String {
        val base = when (server) {
            ImageServer.Original -> fileServer
            ImageServer.Split1 -> "https://storage.wikawika.xyz"
            ImageServer.Split2 -> "https://s2.picacomic.com"
            ImageServer.Split3 -> "https://s3.picacomic.com"
        }.trimEnd('/')
        val normalizedBase = base.removeSuffix("/static")
        val normalizedPath = path.trimStart('/').removePrefix("static/")
        return "$normalizedBase/static/$normalizedPath".toHttpUrl().toString()
    }
}
