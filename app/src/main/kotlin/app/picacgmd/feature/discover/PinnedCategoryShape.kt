package app.picacgmd.feature.discover

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.RoundedPolygon

/**
 * Outlines a category pinned to the home row can take. None of them is used by the fixed channels
 * (排行榜 / 随机 / 游戏专区) or by the profile avatar in the search bar, so a pinned category is never
 * mistaken for one of those. Every outline keeps the centred circle of [PinnedShapeSafeDiameter] of
 * the badge untouched, so a picture only loses its corners.
 *
 * The order is part of the rule in [pinnedCategoryShapeIndex]: changing it or the size of the list
 * changes the shape of categories people already pinned.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val PinnedCategoryShapes: List<RoundedPolygon> by lazy(LazyThreadSafetyMode.NONE) {
    listOf(
        MaterialShapes.Arch,
        MaterialShapes.Sunny,
        MaterialShapes.Gem,
        MaterialShapes.Cookie12Sided,
        MaterialShapes.Slanted,
        MaterialShapes.Pentagon,
    )
}

/** Share of the badge width that every outline in [PinnedCategoryShapes] leaves whole around the centre. */
internal const val PinnedShapeSafeDiameter = 0.8f

/**
 * Which of [shapeCount] outlines the category called [name] gets. It depends on the name alone, so
 * the category keeps its outline across launches, when the pinned row is reordered and when other
 * categories are pinned or unpinned. Two categories can share an outline.
 */
internal fun pinnedCategoryShapeIndex(name: String, shapeCount: Int): Int {
    require(shapeCount > 0) { "shapeCount must be positive" }
    // FNV-1a over the UTF-16 units, written out so the result never depends on the runtime.
    var hash = -0x7EE3623B // 0x811C9DC5
    for (char in name) hash = (hash xor char.code) * 0x01000193
    // Fold the high bits down so the remainder depends on every character.
    hash = hash xor (hash ushr 15)
    return Math.floorMod(hash, shapeCount)
}

/** The outline of the pinned category called [name]. */
internal fun pinnedCategoryShape(name: String): RoundedPolygon =
    PinnedCategoryShapes[pinnedCategoryShapeIndex(name, PinnedCategoryShapes.size)]
