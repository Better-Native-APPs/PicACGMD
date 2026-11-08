package app.picacgmd.feature.reader.logic

internal enum class VolumeKey { Up, Down }

internal enum class VolumeAction { NextPage, PreviousPage, NextChapter, PreviousChapter }

/** Two presses of the same key this close together jump a chapter. */
internal const val VOLUME_DOUBLE_PRESS_MILLIS: Long = 350L

/**
 * Turns volume key presses into reader actions. Volume down goes forward and volume up goes back.
 *
 * A press always acts at once, so paging never waits to see whether a second press follows. With
 * chapter jumping on, a second press of the same key inside the window jumps a chapter in that
 * direction; the press after that starts over.
 */
internal class VolumeKeyInterpreter(private val doublePressMillis: Long = VOLUME_DOUBLE_PRESS_MILLIS) {
    private var lastKey: VolumeKey? = null
    private var lastPressMillis: Long = 0L

    fun onPress(key: VolumeKey, timeMillis: Long, chapterJump: Boolean): VolumeAction {
        val second = chapterJump && key == lastKey && timeMillis - lastPressMillis in 0..doublePressMillis
        if (second) {
            lastKey = null
            return if (key == VolumeKey.Down) VolumeAction.NextChapter else VolumeAction.PreviousChapter
        }
        lastKey = key
        lastPressMillis = timeMillis
        return if (key == VolumeKey.Down) VolumeAction.NextPage else VolumeAction.PreviousPage
    }
}
