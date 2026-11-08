package app.picacgmd.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PicaDtosTest {
    @Test
    fun announcementsTolerateAbsentNullAndUnknownFields() {
        assertEquals(Announcement(), PicaJson.decodeFromString<Announcement>("{}"))
        assertEquals(
            Announcement(),
            PicaJson.decodeFromString<Announcement>(
                """{"_id":null,"title":null,"content":null,"thumb":null,"created_at":null,"futureField":42}""",
            ),
        )
    }

    @Test
    fun comicListsRetainOptionalTagsAndDefaultMissingOrNullTags() {
        val comics = PicaJson.decodeFromString<List<ComicSimple>>(
            """[{"tags":["ai绘画"]},{"tags":null},{}]""",
        )
        assertEquals(listOf("ai绘画"), comics.first().tags)
        assertEquals(emptyList(), comics[1].tags)
        assertEquals(emptyList(), comics[2].tags)
    }

    @Test
    fun comicsPageDecodesSearchResultsWithMissingCountsAndUnknownFields() {
        val page = PicaJson.decodeFromString<Page<ComicSimple>>(
            """{"total":1,"limit":20,"page":"2","pages":4,"newField":true,"docs":[
                {"_id":"comic-1","title":"测试漫画","author":null,"categories":["漫画"],
                 "thumb":{"fileServer":"https://s2.picacomic.com","path":"cover.jpg"}}]}""",
        )
        assertEquals(2, page.page)
        assertEquals("comic-1", page.docs.single().id)
        assertEquals("", page.docs.single().author)
        assertEquals(0, page.docs.single().epsCount)
        assertEquals(0, page.docs.single().pagesCount)
        assertEquals("cover.jpg", page.docs.single().thumb?.path)
    }

    @Test
    fun comicInfoDecodesCreatorNullableSloganAndInteractionFlags() {
        val comic = PicaJson.decodeFromString<ComicInfo>(
            """{"_id":"comic-2","title":"详情","_creator":{"_id":"knight","name":"骑士","slogan":null},
                "isFavourite":true,"isLiked":true,"description":null,"tags":["标签"],
                "updated_at":"2026-11-07T00:00:00.000Z","newServerField":123}""",
        )
        assertEquals("knight", comic.creator?.id)
        assertNull(comic.creator?.slogan)
        assertTrue(comic.isFavourite)
        assertTrue(comic.isLiked)
        assertEquals("", comic.description)
        assertEquals(listOf("标签"), comic.tags)
    }

    @Test
    fun categoriesRetainIsWebAndLinkWithoutRequiringAnIdOrThumbnail() {
        val categories = PicaJson.decodeFromString<List<Category>>(
            """[{"title":"大家都在看","active":true},
                {"title":"外部链接","isWeb":true,"link":"https://www.picacomic.com","thumb":null}]""",
        )
        assertFalse(categories.first().isWeb)
        assertEquals("", categories.first().id)
        assertTrue(categories.last().isWeb)
        assertEquals("https://www.picacomic.com", categories.last().link)
        assertNull(categories.last().thumb)
    }
}
