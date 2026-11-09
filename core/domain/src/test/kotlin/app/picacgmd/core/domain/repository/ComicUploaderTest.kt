package app.picacgmd.core.domain.repository

import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.PicaJson
import app.picacgmd.core.network.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComicUploaderTest {
    @Test
    fun uploaderIsTheCreatorFromTheDetailPayload() {
        val comic = PicaJson.decodeFromString<ComicInfo>(
            """{"_id":"comic-1","title":"星屑列车","_creator":{"_id":"knight-1","gender":"f","name":"小夜",
                "title":"骑士","verified":false,"exp":5210,"level":12,"characters":[],"role":"knight",
                "avatar":{"originalName":"a.jpg","path":"avatar/a.jpg","fileServer":"https://storage1.picacomic.com"},
                "slogan":"慢慢更新"}}""",
        )
        val uploader = comic.uploader()
        assertEquals("knight-1", uploader?.id)
        assertEquals("小夜", uploader?.name)
        assertEquals(12, uploader?.level)
        assertEquals("骑士", uploader?.title)
        assertEquals("avatar/a.jpg", uploader?.avatar?.path)
    }

    @Test
    fun missingCreatorMeansNoUploader() {
        assertNull(PicaJson.decodeFromString<ComicInfo>("""{"_id":"comic-1"}""").uploader())
        assertNull(PicaJson.decodeFromString<ComicInfo>("""{"_id":"comic-1","_creator":null}""").uploader())
    }

    @Test
    fun creatorWithoutIdOrNameIsLeftOut() {
        assertNull(ComicInfo(creator = UserProfile(id = "", name = "小夜")).uploader())
        assertNull(ComicInfo(creator = UserProfile(id = "knight-1", name = " ")).uploader())
    }
}
