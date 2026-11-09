package app.picacgmd.feature.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import app.picacgmd.R

/** A category shown on the home page: pinned shortcut or grid tile. */
@Immutable
data class HomeCategory(val name: String, val thumbUrl: String? = null)

/** Everything the discover page renders below the fixed channels. */
@Immutable
data class DiscoverContent(
    /** Categories the user pinned behind the fixed channels (「固定到首页」). */
    val pinnedCategories: List<HomeCategory>,
    /** Server categories in the home grid. */
    val categories: List<HomeCategory>,
)

/** Static content for the discover preview. */
@Composable
fun rememberSampleDiscoverContent(): DiscoverContent {
    val resources = LocalResources.current
    return remember(resources) {
        DiscoverContent(
            pinnedCategories = resources.getStringArray(R.array.discover_sample_pinned).map { HomeCategory(it) },
            categories = resources.getStringArray(R.array.discover_sample_categories).map { HomeCategory(it) },
        )
    }
}
