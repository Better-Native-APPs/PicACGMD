package app.picacgmd.feature.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import app.picacgmd.R

/** A category shown on the home page: pinned shortcut or grid tile. */
@Immutable
data class HomeCategory(val name: String, val thumbUrl: String? = null)

/** One item of the recommendation carousel (first group of the official collections). */
@Immutable
data class CarouselComic(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String? = null,
)

/** Everything the discover page renders below the fixed channels. */
@Immutable
data class DiscoverContent(
    /** Categories the user pinned behind the fixed channels (「固定到首页」). */
    val pinnedCategories: List<HomeCategory>,
    /** Carousel source; the section is hidden when empty. */
    val carousel: List<CarouselComic>,
    /** Server categories, shown after the built-in 「推荐」 tile. */
    val categories: List<HomeCategory>,
)

/** Static content for the shell until the discover data layer is wired in. */
@Composable
fun rememberSampleDiscoverContent(): DiscoverContent {
    val resources = LocalResources.current
    return remember(resources) {
        val titles = resources.getStringArray(R.array.discover_sample_titles)
        val authors = resources.getStringArray(R.array.discover_sample_authors)
        DiscoverContent(
            pinnedCategories = resources.getStringArray(R.array.discover_sample_pinned).map { HomeCategory(it) },
            carousel = titles.mapIndexed { index, title ->
                CarouselComic(id = "sample-$index", title = title, author = authors.getOrElse(index) { "" })
            },
            categories = resources.getStringArray(R.array.discover_sample_categories).map { HomeCategory(it) },
        )
    }
}
