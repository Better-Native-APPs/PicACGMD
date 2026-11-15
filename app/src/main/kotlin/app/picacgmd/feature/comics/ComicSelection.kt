package app.picacgmd.feature.comics

import app.picacgmd.core.network.ComicSimple

internal fun listedSelection(selection: List<String>, comics: List<ComicSimple>): List<String> {
    val listed = comics.mapTo(HashSet()) { it.id }
    val kept = selection.filter { it in listed }
    return if (kept.size == selection.size) selection else kept
}
