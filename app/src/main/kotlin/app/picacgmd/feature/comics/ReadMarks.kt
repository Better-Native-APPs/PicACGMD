package app.picacgmd.feature.comics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.picacgmd.R
import app.picacgmd.core.domain.repository.HistoryRepository
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** View history of the comics a list shows: who has been opened, and the last chapter read. */
@Immutable
class ReadMarks(private val chapters: Map<String, Int>) {
    /** null = never opened; 0 = only the details were opened; otherwise the one-based chapter. */
    fun chapterOf(comicId: String): Int? = chapters[comicId]

    companion object {
        val None = ReadMarks(emptyMap())
    }
}

/** Texts of one 「已读」 cover mark, ready for `progressLabel` / `progressDetail` / `progressDetailShort`. */
@Immutable
data class ReadMarkText(val label: String, val detail: String?, val detailShort: String?)

/**
 * 「已读 · 第 3 话」 for a comic read up to [chapter] (from [ReadMarks.chapterOf]); null when it has no
 * history. Detail-only visits and one-chapter comics ([epsCount] = 1) get the bare 「已读」.
 */
@Composable
fun readMarkText(chapter: Int?, epsCount: Int = 0): ReadMarkText? {
    if (chapter == null) return null
    val label = stringResource(R.string.viewed_mark)
    if (chapter < 1 || epsCount == 1) return ReadMarkText(label, detail = null, detailShort = null)
    return ReadMarkText(
        label = label,
        detail = stringResource(R.string.viewed_mark_chapter, chapter),
        detailShort = stringResource(R.string.viewed_mark_chapter_short, chapter),
    )
}

/**
 * Live 「已读」 marks for a list. Pass every loaded comic id, not only the visible ones: one narrow Room
 * query per id set follows view-history writes, so a comic read in the meantime is marked as soon as
 * the list is visible again, without a refresh. The marks are held in the screen's ViewModelStore, so
 * they are on screen at once after navigating back, and nothing is queried while the screen waits in
 * the back stack.
 */
@Composable
fun rememberReadMarks(comicIds: List<String>): ReadMarks {
    val history = LocalAppContainer.current.historyRepository
    // One holder per call site, stable across navigation like rememberSaveable's key.
    val holder = viewModel(key = "read-marks-${currentCompositeKeyHashCode.toString(36)}") { ReadMarksHolder(history) }
    SideEffect { holder.track(comicIds) }
    val marks by holder.marks.collectAsStateWithLifecycle()
    return marks
}

internal class ReadMarksHolder(history: HistoryRepository) : ViewModel() {
    private val ids = MutableStateFlow<List<String>>(emptyList())

    /** Keeps the previous marks while a grown id list is re-queried, so appending a page never blinks. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val marks: StateFlow<ReadMarks> = ids
        .flatMapLatest(history::observeReadChapters)
        .map(::ReadMarks)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadMarks.None)

    fun track(comicIds: List<String>) {
        ids.value = comicIds
    }
}
