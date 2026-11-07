package app.picacgmd.ui

import androidx.compose.material3.SnackbarHostState
import app.picacgmd.core.domain.download.DownloadManager
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.Ep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Download feedback shared by search, comic lists and the chapter picker. */
internal class DownloadActions(
    private val manager: DownloadManager,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
) {
    fun enqueueBatch(comics: List<ComicSimple>) {
        enqueue { manager.enqueueBatch(comics) }
    }

    fun enqueueChapters(comic: ComicInfo, eps: List<Ep>) {
        enqueue { manager.enqueue(comic, eps) }
    }

    private fun enqueue(block: suspend () -> Unit) {
        scope.launch {
            val message = try {
                block()
                "已加入下载"
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                "加入下载失败：${exception.message ?: "请重试"}"
            }
            snackbar.showSnackbar(message)
        }
    }
}
