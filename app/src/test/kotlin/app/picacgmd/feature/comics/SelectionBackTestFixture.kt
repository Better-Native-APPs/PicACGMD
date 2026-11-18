package app.picacgmd.feature.comics

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.picacgmd.core.data.settings.ListLayout
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.PicaApi
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.robolectric.RuntimeEnvironment

internal const val SELECTION_BACK_CATEGORY = "测试分类"
internal const val SELECTION_BACK_KEYWORD = "测试搜索"
internal const val SELECTION_BACK_PREVIOUS_PAGE = "上一页"
internal val selectionBackComics = (1..24).map { ComicSimple(id = "comic-$it", title = "测试漫画 $it") }

internal fun selectionBackString(id: Int, vararg arguments: Any): String =
    RuntimeEnvironment.getApplication().getString(id, *arguments)

internal class SelectionBackTestFixture : AutoCloseable {
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val data = when (chain.request().url.encodedPath) {
            "/comics", "/comics/advanced-search" ->
                """{"comics":{"docs":${Json.encodeToString(selectionBackComics)},"page":1,"pages":1,"total":24}}"""
            "/keywords" -> """{"keywords":[]}"""
            "/categories" -> """{"categories":[]}"""
            else -> error("Unexpected selection test request: ${chain.request().url.encodedPath}")
        }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("""{"code":200,"message":"success","data":$data}""".toResponseBody("application/json".toMediaType()))
            .build()
    }.build()
    val container = AppContainer(RuntimeEnvironment.getApplication()).also { container ->
        val api = PicaApi(baseUrl = "https://selection.test/", client = client)
        val apiProvider: suspend () -> PicaApi = { api }
        // The container is concrete; replace its API boundary before composing to avoid socket requests.
        SessionManager::class.java.getDeclaredField("apiProvider").apply {
            isAccessible = true
            set(container.sessionManager, apiProvider)
        }
        runBlocking { container.settingsRepository.setListLayout(ListLayout.DETAILS) }
    }
    lateinit var activity: ComponentActivity
        private set
    var leaves = 0
        private set

    @Composable
    fun Content(content: @Composable (() -> Unit) -> Unit) {
        activity = LocalActivity.current as ComponentActivity
        var onPage by remember { mutableStateOf(true) }
        val leave = { leaves++; onPage = false }
        BackHandler(enabled = onPage, onBack = leave)
        CompositionLocalProvider(LocalAppContainer provides container) {
            PicaTheme(themeChoice = ThemeChoice.Brand, darkMode = DarkMode.Light) {
                if (onPage) content(leave) else Text(SELECTION_BACK_PREVIOUS_PAGE)
            }
        }
    }

    override fun close() {
        container.close()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
