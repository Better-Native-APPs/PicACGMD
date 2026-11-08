package app.picacgmd.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.picacgmd.R
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.designsystem.component.ErrorState
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.download.DownloadManager
import app.picacgmd.core.domain.download.DownloadManagerOwner
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.rankings.BackButton
import app.picacgmd.feature.reader.ReaderConfig
import app.picacgmd.feature.reader.ReaderScreen
import app.picacgmd.feature.reader.ReaderSource
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private data class ReaderStart(val source: ReaderSource, val chapter: Int, val page: Int)

private class ReaderViewModel(
    private val container: AppContainer,
    private val comicId: String,
    private val onMessage: (String) -> Unit,
    private val episodeOrder: Int = 1,
    private val page: Int = 0,
    private val downloads: DownloadManager? = null,
) : ViewModel() {
    var start by mutableStateOf<ReaderStart?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var config by mutableStateOf(AppSettings().toReaderConfig())
        private set
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            container.settingsRepository.settings.collect { config = it.toReaderConfig(config) }
        }
        load()
    }

    fun load() {
        if (loadJob?.isActive == true) return
        error = null
        loadJob = viewModelScope.launch {
            try {
                // Hydrate before ReaderScreen remembers its initial fullscreen/control state.
                config = container.settingsRepository.settings.first().toReaderConfig(config)
                start = withContext(Dispatchers.IO) {
                    if (downloads == null) loadOnline() else loadDownloaded(downloads)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: "加载阅读内容失败"
            }
        }
    }

    fun updateConfig(value: ReaderConfig) {
        val previous = config
        val next = value.normalized()
        config = next
        ReaderPersistence.launch(onError = { reportError("保存阅读设置失败", it) }) {
            container.settingsRepository.saveReaderConfig(previous, next)
        }
    }

    private suspend fun loadOnline(): ReaderStart {
        val comic = container.comicRepository.comicInfo(comicId)
        val source = OnlineReaderSource(
            comic = comic,
            comicRepository = container.comicRepository,
            historyRepository = container.historyRepository,
            networkConfigProvider = container.networkConfigProvider,
            onProgressError = { reportError("保存阅读进度失败", it) },
            qualityProvider = { config.quality },
        )
        val index = source.indexOfEpisode(episodeOrder)
        return ReaderStart(source, index.coerceAtLeast(0), if (index >= 0) page else 0)
    }

    private suspend fun loadDownloaded(downloads: DownloadManager): ReaderStart {
        val dao = container.database.downloadDao()
        val comic = dao.getComic(comicId) ?: throw IOException("找不到已下载的漫画")
        val source = DownloadedReaderSource(
            comic = comic,
            downloadDao = dao,
            files = downloads.files,
            historyRepository = container.historyRepository,
            onProgressError = { reportError("保存阅读进度失败", it) },
        )
        val progress = container.historyRepository.getProgress(comicId)
        val index = source.indexOfEpisode(progress?.epOrder ?: 1)
        return ReaderStart(source, index.coerceAtLeast(0), if (index >= 0) (progress?.pictureIndex ?: 1) - 1 else 0)
    }

    private fun reportError(action: String, exception: Exception) {
        onMessage("$action：${exception.message ?: "请重试"}")
    }
}

@Composable
internal fun OnlineReaderDestination(
    comicId: String,
    episodeOrder: Int,
    page: Int,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    onOpenComments: () -> Unit,
    onOpenRelated: () -> Unit,
    onOpenAllSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = "online-reader:$comicId:$episodeOrder:$page") {
        ReaderViewModel(container, comicId, onMessage, episodeOrder, page)
    }
    ReaderDestination(viewModel, onBack, modifier, onOpenComments, onOpenRelated, onOpenAllSettings)
}

@Composable
internal fun DownloadedReaderDestination(
    comicId: String,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    onOpenComments: () -> Unit,
    onOpenRelated: () -> Unit,
    onOpenAllSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val downloads = (LocalContext.current.applicationContext as DownloadManagerOwner).downloadManager
    val viewModel = viewModel(key = "downloaded-reader:$comicId") {
        ReaderViewModel(container, comicId, onMessage, downloads = downloads)
    }
    ReaderDestination(viewModel, onBack, modifier, onOpenComments, onOpenRelated, onOpenAllSettings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderDestination(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
    onOpenComments: () -> Unit,
    onOpenRelated: () -> Unit,
    onOpenAllSettings: () -> Unit,
) {
    val start = viewModel.start
    if (start != null) {
        ReaderScreen(
            source = start.source,
            config = viewModel.config,
            startChapter = start.chapter,
            startPage = start.page,
            onConfigChange = viewModel::updateConfig,
            onBack = onBack,
            modifier = modifier,
            onOpenComments = onOpenComments,
            onOpenRelated = onOpenRelated,
            onOpenAllSettings = onOpenAllSettings,
        )
    } else {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.title_reader)) },
                    navigationIcon = { BackButton(onBack) },
                )
            },
        ) { padding ->
            val error = viewModel.error
            if (error == null) {
                LoadingState(Modifier.padding(padding).fillMaxSize())
            } else {
                ErrorState(onRetry = viewModel::load, description = error, modifier = Modifier.padding(padding))
            }
        }
    }
}
