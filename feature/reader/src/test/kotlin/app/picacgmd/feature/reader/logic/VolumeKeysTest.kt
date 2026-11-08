package app.picacgmd.feature.reader.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class VolumeKeysTest {
    @Test
    fun volumeDownGoesForwardAndUpGoesBack() {
        val keys = VolumeKeyInterpreter()
        assertEquals(VolumeAction.NextPage, keys.onPress(VolumeKey.Down, 1_000L, chapterJump = false))
        assertEquals(VolumeAction.PreviousPage, keys.onPress(VolumeKey.Up, 5_000L, chapterJump = false))
    }

    @Test
    fun withoutChapterJumpEveryPressTurnsAPage() {
        val keys = VolumeKeyInterpreter()
        repeat(3) { step ->
            assertEquals(VolumeAction.NextPage, keys.onPress(VolumeKey.Down, 1_000L + step * 50L, chapterJump = false))
        }
    }

    @Test
    fun aQuickSecondPressJumpsAChapter() {
        val keys = VolumeKeyInterpreter()
        assertEquals(VolumeAction.NextPage, keys.onPress(VolumeKey.Down, 1_000L, chapterJump = true))
        assertEquals(VolumeAction.NextChapter, keys.onPress(VolumeKey.Down, 1_200L, chapterJump = true))
        assertEquals(VolumeAction.PreviousPage, keys.onPress(VolumeKey.Up, 3_000L, chapterJump = true))
        assertEquals(VolumeAction.PreviousChapter, keys.onPress(VolumeKey.Up, 3_100L, chapterJump = true))
    }

    @Test
    fun theWindowIsInclusiveAndThenCloses() {
        val inside = VolumeKeyInterpreter()
        inside.onPress(VolumeKey.Down, 1_000L, chapterJump = true)
        assertEquals(
            VolumeAction.NextChapter,
            inside.onPress(VolumeKey.Down, 1_000L + VOLUME_DOUBLE_PRESS_MILLIS, chapterJump = true),
        )
        val outside = VolumeKeyInterpreter()
        outside.onPress(VolumeKey.Down, 1_000L, chapterJump = true)
        assertEquals(
            VolumeAction.NextPage,
            outside.onPress(VolumeKey.Down, 1_001L + VOLUME_DOUBLE_PRESS_MILLIS, chapterJump = true),
        )
    }

    @Test
    fun differentKeysDoNotPair() {
        val keys = VolumeKeyInterpreter()
        keys.onPress(VolumeKey.Down, 1_000L, chapterJump = true)
        assertEquals(VolumeAction.PreviousPage, keys.onPress(VolumeKey.Up, 1_100L, chapterJump = true))
        assertEquals(VolumeAction.NextPage, keys.onPress(VolumeKey.Down, 1_200L, chapterJump = true))
    }

    @Test
    fun theThirdPressStartsOver() {
        val keys = VolumeKeyInterpreter()
        keys.onPress(VolumeKey.Down, 1_000L, chapterJump = true)
        keys.onPress(VolumeKey.Down, 1_100L, chapterJump = true)
        assertEquals(VolumeAction.NextPage, keys.onPress(VolumeKey.Down, 1_200L, chapterJump = true))
    }

    @Test
    fun turningTheSwitchOffBetweenPressesDoesNotJump() {
        val keys = VolumeKeyInterpreter()
        keys.onPress(VolumeKey.Down, 1_000L, chapterJump = true)
        assertEquals(VolumeAction.NextPage, keys.onPress(VolumeKey.Down, 1_100L, chapterJump = false))
    }
}
