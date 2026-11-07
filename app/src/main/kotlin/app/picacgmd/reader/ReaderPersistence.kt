package app.picacgmd.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Ordered app-lifetime writes: the final progress save must survive the reader leaving composition. */
internal object ReaderPersistence {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun launch(onError: (Exception) -> Unit, block: suspend () -> Unit) {
        scope.launch {
            try {
                mutex.withLock { block() }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                onError(exception)
            }
        }
    }
}
