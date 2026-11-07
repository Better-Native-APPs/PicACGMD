package app.picacgmd.core.domain.download

import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class DownloadLimiterState(
    val configuredLimit: Int,
    val effectiveLimit: Int,
    val inFlight: Int,
    val backoffUntilMillis: Long,
)

/**
 * Admission control shared by manifests, covers and pictures. Decreasing the live limit stops new
 * admissions immediately; already admitted transfers finish rather than throwing away valid bytes.
 * Throttling halves the limit and recovers one slot per healthy recovery interval.
 */
class AdaptiveDownloadLimiter(
    initialLimit: Int = 4,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val baseBackoffMillis: Long = 1_000,
    private val maxBackoffMillis: Long = 60_000,
    private val recoveryIntervalMillis: Long = 30_000,
    private val jitter: (Long) -> Long = { Random.nextLong(it + 1) },
) {
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)
    private var configuredLimit = initialLimit
    private var effectiveLimit = initialLimit
    private var inFlight = 0
    private var enabled = true
    private var throttleCount = 0
    private var blockedUntil = 0L
    private var nextRecoveryAt = Long.MAX_VALUE
    private val mutableState = MutableStateFlow(snapshot())
    val state: StateFlow<DownloadLimiterState> = mutableState.asStateFlow()

    init {
        require(initialLimit in 1..16)
        require(baseBackoffMillis > 0 && maxBackoffMillis >= baseBackoffMillis)
        require(recoveryIntervalMillis > 0)
    }

    suspend fun setLimit(limit: Int) = mutex.withLock {
        require(limit in 1..16) { "Download threads must be 1..16" }
        refreshLocked()
        effectiveLimit = if (throttleCount == 0) limit else minOf(effectiveLimit, limit)
        configuredLimit = limit
        publishLocked()
    }

    suspend fun setEnabled(value: Boolean) = mutex.withLock {
        enabled = value
        publishLocked()
    }

    suspend fun onHttpFailure(status: Int, retryAfterMillis: Long? = null): Long = mutex.withLock {
        if (status != 429 && status != 503) return@withLock 0L
        throttleCount = (throttleCount + 1).coerceAtMost(30)
        effectiveLimit = (effectiveLimit / 2).coerceAtLeast(1)
        val exponential = exponentialDelay(baseBackoffMillis, maxBackoffMillis, throttleCount)
        val backoff = maxOf(
            (exponential + jitter(exponential / 4)).coerceAtMost(maxBackoffMillis),
            retryAfterMillis ?: 0,
        )
        val now = nowMillis()
        blockedUntil = maxOf(blockedUntil, now + backoff)
        nextRecoveryAt = blockedUntil + recoveryIntervalMillis
        publishLocked()
        backoff
    }

    suspend fun onSuccess() = mutex.withLock {
        refreshLocked()
        publishLocked()
    }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    inFlight--
                    publishLocked()
                }
            }
        }
    }

    private suspend fun acquire() {
        while (true) {
            val version = changes.value
            var acquired = false
            val waitMillis = mutex.withLock {
                refreshLocked()
                val now = nowMillis()
                if (enabled && now >= blockedUntil && inFlight < effectiveLimit) {
                    inFlight++
                    acquired = true
                    publishLocked()
                    null
                } else if (enabled && now < blockedUntil) {
                    blockedUntil - now
                } else {
                    null
                }
            }
            if (acquired) return
            if (waitMillis != null) {
                withTimeoutOrNull(waitMillis) { changes.first { it != version } }
            } else {
                changes.first { it != version }
            }
        }
    }

    private fun refreshLocked() {
        val now = nowMillis()
        while (throttleCount > 0 && now >= nextRecoveryAt && effectiveLimit < configuredLimit) {
            effectiveLimit++
            nextRecoveryAt += recoveryIntervalMillis
            if (effectiveLimit == configuredLimit) {
                throttleCount = 0
                nextRecoveryAt = Long.MAX_VALUE
            }
        }
        if (throttleCount > 0 && now >= nextRecoveryAt && effectiveLimit == configuredLimit) {
            throttleCount = 0
            nextRecoveryAt = Long.MAX_VALUE
        }
        val updated = snapshot()
        if (mutableState.value != updated) publishLocked()
    }

    private fun snapshot() = DownloadLimiterState(configuredLimit, effectiveLimit, inFlight, blockedUntil)

    private fun publishLocked() {
        mutableState.value = snapshot()
        changes.value++
    }
}

class DownloadHttpException(
    val status: Int,
    val retryAfterMillis: Long? = null,
) : IOException("图片服务器返回 HTTP $status")

/** Five total attempts by default; backoff is outside the transfer permit. */
class DownloadRetryPolicy(
    val maxAttempts: Int = 5,
    private val baseDelayMillis: Long = 1_000,
    private val maxDelayMillis: Long = 60_000,
    private val jitter: (Long) -> Long = { Random.nextLong(it + 1) },
) {
    init {
        require(maxAttempts > 0)
        require(baseDelayMillis > 0 && maxDelayMillis >= baseDelayMillis)
    }

    fun delayMillis(failedAttempt: Int): Long {
        require(failedAttempt > 0)
        val exponential = exponentialDelay(baseDelayMillis, maxDelayMillis, failedAttempt)
        return (exponential + jitter(exponential / 4)).coerceAtMost(maxDelayMillis)
    }
}

private fun exponentialDelay(base: Long, maximum: Long, attempt: Int): Long {
    var result = base
    repeat((attempt - 1).coerceAtMost(30)) {
        result = if (result >= maximum / 2) maximum else minOf(result * 2, maximum)
    }
    return result
}
