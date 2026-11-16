package app.picacgmd.feature.detail

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.domain.download.DownloadManager
import app.picacgmd.core.domain.download.DownloadManagerOwner
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.Ep
import app.picacgmd.core.network.UserProfile
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.testing.TestAppEnvironment
import app.picacgmd.testing.TestPicaServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.robolectric.RuntimeEnvironment

internal const val DETAIL_CATEGORY = "冒险"
internal const val DETAIL_TAG = "列车"
internal const val DETAIL_OTHER_TAG = "猫咪"

internal val detailComic = ComicInfo(
    id = "detail-comic",
    title = "测试漫画名称",
    author = "测试作者",
    chineseTeam = "测试汉化",
    creator = UserProfile(id = "uploader-id", name = "测试上传", level = 12, title = "搬运工"),
    categories = listOf(DETAIL_CATEGORY),
    tags = listOf(DETAIL_TAG, DETAIL_OTHER_TAG),
    epsCount = 2,
    pagesCount = 44,
    likesCount = 7,
    viewsCount = 123,
    updatedAt = "2026-11-15T00:00:00Z",
)

internal fun detailChapters(count: Int): List<Ep> =
    (1..count).map { Ep(id = "chapter-$it", title = "第 $it 话", order = it) }

internal fun detailString(id: Int, vararg arguments: Any): String =
    RuntimeEnvironment.getApplication().getString(id, *arguments)

/** Supplies only in-memory repositories and a loopback API; fixture images are deliberately absent. */
internal class DetailTestFixture : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val pica = TestPicaServer()
    val environment = TestAppEnvironment(pica, scope)

    @Composable
    fun Content(fontScale: Float = 1f, dark: Boolean = false, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalAppContainer provides environment.container,
            LocalDensity provides Density(density.density, fontScale),
        ) {
            PicaTheme(themeChoice = ThemeChoice.Brand, darkMode = if (dark) DarkMode.Dark else DarkMode.Light) {
                content()
            }
        }
    }

    override fun close() {
        scope.cancel()
        pica.close()
    }
}

/** The screen obtains downloads from the Application; no real application startup runs in these tests. */
class DetailTestApplication : Application(), DownloadManagerOwner {
    override lateinit var downloadManager: DownloadManager
}
