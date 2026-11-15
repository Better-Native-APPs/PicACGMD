package app.picacgmd.util

import kotlin.coroutines.cancellation.CancellationException

internal suspend fun <T> suspendRunCatching(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
