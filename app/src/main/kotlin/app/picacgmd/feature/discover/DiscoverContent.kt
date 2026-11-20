package app.picacgmd.feature.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import app.picacgmd.R
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.domain.repository.ALL_COMICS_CATEGORY
import app.picacgmd.core.domain.repository.GAMES_CATEGORY
import app.picacgmd.core.domain.repository.RANDOM_CATEGORY
import app.picacgmd.core.domain.repository.buildHomeModel
import app.picacgmd.core.network.Category

enum class HomeCategoryKind { CATEGORY, ALL_COMICS, RANDOM, GAMES }

/** A category shown on the home page: pinned shortcut or grid tile. */
@Immutable
data class HomeCategory(val name: String, val thumbUrl: String? = null, val kind: HomeCategoryKind = HomeCategoryKind.CATEGORY)

internal const val ALL_COMICS_THUMB_URL = "file:///android_asset/category_all.png"
internal val AllComicsHomeCategory = HomeCategory(ALL_COMICS_CATEGORY.title, ALL_COMICS_THUMB_URL, HomeCategoryKind.ALL_COMICS)
internal val RandomHomeCategory = HomeCategory(RANDOM_CATEGORY.title, kind = HomeCategoryKind.RANDOM)
internal val GamesHomeCategory = HomeCategory(GAMES_CATEGORY.title, kind = HomeCategoryKind.GAMES)
internal val BuiltInHomeCategories = listOf(AllComicsHomeCategory, RandomHomeCategory, GamesHomeCategory)

/** Destination identity comes from the local IDs, never from a server category's title. */
internal fun Category.toHomeCategory(thumbUrl: String? = null): HomeCategory = when (id) {
    ALL_COMICS_CATEGORY.id -> AllComicsHomeCategory
    RANDOM_CATEGORY.id -> RandomHomeCategory
    GAMES_CATEGORY.id -> GamesHomeCategory
    else -> HomeCategory(title, thumbUrl)
}

/** The grid before the server's categories are known: the local entries, in the saved order and without the hidden ones. */
internal fun localHomeCategories(settings: AppSettings): List<HomeCategory> =
    buildHomeModel(emptyList(), settings).categories.map { it.toHomeCategory() }

/** Category shortcuts and grid entries rendered on the discover page. */
@Immutable
data class DiscoverContent(
    /** Categories the user pinned behind the built-in channels (「固定到首页」). */
    val pinnedCategories: List<HomeCategory>,
    /** The fixed 全分类 / 随机 / 游戏专区 entries followed by server categories in the home grid. */
    val categories: List<HomeCategory>,
)

/** Static content for the discover preview. */
@Composable
fun rememberSampleDiscoverContent(): DiscoverContent {
    val resources = LocalResources.current
    return remember(resources) {
        DiscoverContent(
            pinnedCategories = resources.getStringArray(R.array.discover_sample_pinned).map { HomeCategory(it) },
            categories = BuiltInHomeCategories +
                resources.getStringArray(R.array.discover_sample_categories).map { HomeCategory(it) },
        )
    }
}
