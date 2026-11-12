package app.picacgmd.core.domain.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal const val WIFI_WAIT_GRACE_MILLIS = 30_000L

private class WifiWaitGraceExpired : RuntimeException()

internal suspend fun runWithWifiWaitWatch(
    waitingForWifi: Flow<Boolean>,
    graceMillis: Long,
    block: suspend () -> Unit,
): Boolean = try {
    coroutineScope {
        val watch = launch(start = CoroutineStart.UNDISPATCHED) {
            waitingForWifi.distinctUntilChanged().collectLatest { waiting ->
                if (waiting) {
                    delay(graceMillis)
                    // Fail the scope, not the engine, so it cannot consume the hand-over signal.
                    throw WifiWaitGraceExpired()
                }
            }
        }
        try {
            block()
        } finally {
            watch.cancel()
        }
    }
    true
} catch (_: WifiWaitGraceExpired) {
    currentCoroutineContext().ensureActive()
    false
}
