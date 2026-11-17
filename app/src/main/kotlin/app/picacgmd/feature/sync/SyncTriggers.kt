package app.picacgmd.feature.sync

import app.picacgmd.core.domain.sync.HistorySyncActions
import app.picacgmd.core.network.webdav.WebDavError
import kotlinx.coroutines.CancellationException

internal suspend fun requestHistorySyncAfterSessionReady(
    actions: HistorySyncActions,
    awaitReady: suspend () -> Unit,
    onFailure: (WebDavError) -> Unit,
) {
    try {
        awaitReady()
        actions.requestAutomaticSync()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        onFailure(WebDavError.STORAGE)
    }
}
