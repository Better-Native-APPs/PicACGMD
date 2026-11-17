package app.picacgmd.feature.discover

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The rule that gives a pinned category its outline, and the outlines it can pick from. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class DiscoverPinnedShapeTest {
    @Test
    fun aNameAlwaysMapsToTheSameOutline() {
        // Fixed expectations: a change of the rule would reshuffle what people already pinned.
        assertEquals(1, pinnedCategoryShapeIndex("大家都在看", 6))
        assertEquals(2, pinnedCategoryShapeIndex("大濕推薦", 6))
        assertEquals(0, pinnedCategoryShapeIndex("全分类", 6))
        assertEquals(3, pinnedCategoryShapeIndex("Fate", 6))
        assertSame(MaterialShapes.Sunny, pinnedCategoryShape("大家都在看"))
        assertSame(MaterialShapes.Gem, pinnedCategoryShape("大濕推薦"))
    }

    @Test
    fun theOutlineDoesNotDependOnTheOtherPinsOrTheirOrder() {
        val alone = CatalogueNames.associateWith(::pinnedCategoryShape)
        val pinnedRows = listOf(
            CatalogueNames.take(2),
            CatalogueNames.take(2).reversed(),
            CatalogueNames.take(5),
            CatalogueNames.drop(1).take(4),
            CatalogueNames.reversed(),
        )
        pinnedRows.forEach { row ->
            row.forEach { name -> assertSame(alone.getValue(name), pinnedCategoryShape(name), name) }
        }
    }

    @Test
    fun everyNameGetsAnOutlineInRange() {
        val names = CatalogueNames + listOf("", " ", "a", "\uD83D\uDE00", "x".repeat(4096))
        for (count in 1..12) {
            names.forEach { name -> assertTrue(pinnedCategoryShapeIndex(name, count) in 0 until count, "$name / $count") }
        }
        assertFailsWith<IllegalArgumentException> { pinnedCategoryShapeIndex("全彩", 0) }
    }

    @Test
    fun theCatalogueIsSpreadOverAllOutlines() {
        val perOutline = CatalogueNames.groupingBy(::pinnedCategoryShape).eachCount()
        assertEquals(PinnedCategoryShapes.size, perOutline.size, "every outline is used")
        assertTrue(perOutline.values.max() <= CatalogueNames.size / 3, "no outline takes more than a third: $perOutline")
    }

    @Test
    fun outlinesAreDistinctAndNotThoseOfTheFixedEntries() {
        assertEquals(PinnedCategoryShapes.size, PinnedCategoryShapes.toSet().size)
        // 排行榜, 随机, 游戏专区 and the profile avatar in the search bar above the row.
        val taken = listOf(
            MaterialShapes.Cookie6Sided,
            MaterialShapes.Clover4Leaf,
            MaterialShapes.Flower,
            MaterialShapes.Cookie9Sided,
            MaterialShapes.Circle,
        )
        taken.forEach { shape -> assertTrue(PinnedCategoryShapes.none { it === shape || it == shape }) }
    }

    @Test
    fun everyOutlineLeavesTheMiddleOfThePictureWhole() {
        PinnedCategoryShapes.forEachIndexed { index, polygon ->
            val points = polygon.outlinePoints()
            val minX = points.minOf { it.first }
            val maxX = points.maxOf { it.first }
            val minY = points.minOf { it.second }
            val maxY = points.maxOf { it.second }
            // The badge is square, and the outline is centred in it.
            val centreX = (minX + maxX) / 2
            val centreY = (minY + maxY) / 2
            assertTrue(minX >= -0.001f && minY >= -0.001f && maxX <= 1.001f && maxY <= 1.001f, "outline $index fits the badge")
            val nearest = points.minOf { hypot(it.first - centreX, it.second - centreY) }
            assertTrue(
                nearest * 2 >= PinnedShapeSafeDiameter,
                "outline $index cuts into the middle: only ${nearest * 2} of the width is whole",
            )
        }
    }

    /** Points along the outline, in the unit square the polygon is normalised to. */
    private fun RoundedPolygon.outlinePoints(): List<Pair<Float, Float>> = buildList {
        cubics.forEach { cubic ->
            for (step in 0 until 32) {
                val t = step / 32f
                val u = 1 - t
                add(
                    Pair(
                        u * u * u * cubic.anchor0X + 3 * u * u * t * cubic.control0X +
                            3 * u * t * t * cubic.control1X + t * t * t * cubic.anchor1X,
                        u * u * u * cubic.anchor0Y + 3 * u * u * t * cubic.control0Y +
                            3 * u * t * t * cubic.control1Y + t * t * t * cubic.anchor1Y,
                    ),
                )
            }
        }
    }

    private companion object {
        /** Category names of the kind the service lists, plus the built-in 全分类. */
        val CatalogueNames = listOf(
            "大家都在看", "大濕推薦", "那年今天", "官方都在看", "嗶咔漢化", "全彩", "長篇", "同人", "短篇",
            "圓神領域", "碧藍幻想", "CG雜圖", "英語 ENG", "生肉", "純愛", "百合花園", "耽美花園", "偽娘哲學",
            "後宮閃光", "扶他樂園", "單行本", "姐姐系", "妹妹系", "性轉換", "足の恋", "人妻", "非人類",
            "艦隊收藏", "Love Live", "SAO 刀劍神域", "Fate", "東方", "WEBTOON", "禁書目錄", "歐美", "Cosplay",
            "重口地帶", "全分类",
        )
    }
}
