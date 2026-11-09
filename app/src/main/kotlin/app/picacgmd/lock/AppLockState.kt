package app.picacgmd.lock

/** Activity-independent lock decisions; timestamps use the monotonic elapsed-realtime clock. */
internal class AppLockState {
    var isLocked: Boolean = true
        private set
    var isEnabled: Boolean = false
        private set
    private var backgroundAtMillis: Long? = null

    fun configure(enabled: Boolean, deviceSecure: Boolean) {
        val shouldEnable = enabled && deviceSecure
        if (!shouldEnable) {
            isLocked = false
            backgroundAtMillis = null
        } else if (!isEnabled) {
            // Both a cold start and enabling the setting require a first authentication.
            isLocked = true
        }
        isEnabled = shouldEnable
    }

    fun onBackground(nowMillis: Long, authenticating: Boolean) {
        if (!authenticating) backgroundAtMillis = nowMillis
    }

    fun onForeground(nowMillis: Long, timeoutSeconds: Int) {
        val backgroundAt = backgroundAtMillis
        if (isEnabled && backgroundAt != null && nowMillis - backgroundAt >= timeoutSeconds * 1_000L) {
            isLocked = true
        }
        backgroundAtMillis = null
    }

    fun unlock() {
        isLocked = false
        backgroundAtMillis = null
    }
}
