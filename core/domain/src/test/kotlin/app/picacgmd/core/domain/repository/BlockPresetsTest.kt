package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.network.ComicSimple
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockPresetsTest {
    @Test
    fun theFourGroupsInOrder() {
        assertEquals(listOf("AI作画", "耽美相关", "重口味", "NTR相关"), BlockPresets.all.map { it.name })
    }

    @Test
    fun aiGroupIsTheAiSwitchMarkers() {
        assertEquals(aiComicMarkers, BlockPresets.Ai.keywords)
    }

    @Test
    fun wordsAreTrimmedAndAppearOnlyOnce() {
        BlockPresets.all.forEach { preset ->
            assertTrue(preset.keywords.isNotEmpty(), preset.name)
            preset.keywords.forEach { assertTrue(it.isNotEmpty() && it == it.trim(), "${preset.name}: 「$it」") }
        }
        // Across groups too, so removing one group never takes a word away from another.
        val words = BlockPresets.all.flatMap { it.keywords }.map { it.lowercase() }
        assertEquals(words.size, words.distinct().size)
    }

    @Test
    fun simplifiedAndTraditionalFormsAreBothThere() {
        val words = BlockPresets.all.flatMap { it.keywords }.toSet()
        listOf(
            "AI作画" to "AI作畫", "AI创作" to "AI創作", "AI画作" to "AI畫作", "AI绘画" to "AI繪畫", "AI艺术" to "AI藝術",
            "猎奇" to "獵奇", "人体改造" to "人體改造", "寝取" to "寢取", "绿帽" to "綠帽",
        ).forEach { (simplified, traditional) ->
            assertTrue(simplified in words && traditional in words, "$simplified / $traditional")
        }
    }

    @Test
    fun everyWordHidesItsOwnTagButNotLookalikes() {
        BlockPresets.all.forEach { preset ->
            val settings = AppSettings(hiddenKeywords = preset.keywords)
            preset.keywords.forEach { word ->
                assertTrue(isComicHidden(ComicSimple(tags = listOf(word)), settings), "${preset.name}: $word")
            }
        }
        val everything = AppSettings(hiddenKeywords = BlockPresets.all.flatMap { it.keywords })
        listOf("Blue Archive", "BLEACH", "ENTRY", "Mind Control", "Public Library").forEach { title ->
            assertFalse(isComicHidden(ComicSimple(title = title), everything), title)
        }
    }

    @Test
    fun missingFromIgnoresCaseAndSpaces() {
        val preset = BlockPreset("组", listOf("BL", "耽美", "腐向"))
        assertEquals(listOf("BL", "耽美", "腐向"), preset.missingFrom(emptyList()))
        assertEquals(listOf("腐向"), preset.missingFrom(listOf(" bl ", "耽美")))
        assertTrue(preset.missingFrom(listOf("bl", "耽美", "腐向", "其他")).isEmpty())
    }

    @Test
    fun findKeywordReturnsTheStoredSpelling() {
        assertEquals("BL", listOf("纯爱", "BL").findKeyword(" bl "))
        assertNull(listOf("BL").findKeyword("NTR"))
        assertTrue(listOf("BL").containsKeyword("bl"))
        assertFalse(listOf("BL").containsKeyword("BLUE"))
    }

    @Test
    fun plusKeywordsAppendsOnlyNewWordsTrimmedAndInOrder() {
        assertEquals(listOf("BL", "耽美", "NTR"), listOf("BL", "耽美").plusKeywords(listOf("bl", " NTR ", "", "ntr")))
        assertEquals(listOf("BL"), listOf("BL").plusKeywords(emptyList()))
    }

    @Test
    fun minusKeywordsRemovesEverySpelling() {
        assertEquals(listOf("纯爱"), listOf("BL", "纯爱", "ntr").minusKeywords(listOf("bl", " NTR")))
        assertEquals(listOf("纯爱"), listOf("纯爱").minusKeywords(listOf("BL")))
    }
}
