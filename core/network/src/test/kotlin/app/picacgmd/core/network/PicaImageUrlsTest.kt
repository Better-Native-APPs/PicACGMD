package app.picacgmd.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

class PicaImageUrlsTest {
    @Test
    fun normalizesStaticSegmentAndSlashes() {
        listOf("https://s2.picacomic.com", "https://s2.picacomic.com/", "https://s2.picacomic.com/static/")
            .forEach { server ->
                listOf("a/b.jpg", "/a/b.jpg", "/static/a/b.jpg").forEach { path ->
                    assertEquals("https://s2.picacomic.com/static/a/b.jpg", PicaImageUrls.build(server, path))
                }
            }
    }

    @Test
    fun supportsEveryBuiltInImageServerAndCustomOverride() {
        val image = PicaImage("https://original.picacomic.com", "a.jpg")
        listOf(
            ImageServer.Original to "https://original.picacomic.com",
            ImageServer.Split1 to "https://storage.wikawika.xyz",
            ImageServer.Split2 to "https://s2.picacomic.com",
            ImageServer.Split3 to "https://s3.picacomic.com",
            ImageServer.Custom("https://custom.picacomic.com/static/") to "https://custom.picacomic.com",
        ).forEach { (mode, host) -> assertEquals("$host/static/a.jpg", PicaImageUrls.build(image, mode)) }
    }
}
