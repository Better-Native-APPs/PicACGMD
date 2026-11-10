package app.picacgmd.lock

/** Activity-independent lock decisions; timestamps use the monotonic elapsed-realtime clock. */
internal class AppLockState {
    var isLocked: Boolean = true
        private set
    var isEnabled: Boolean = false
        private set
    private var backgroundAtMillis: Long? = null

    /**
     * Set when the app itself starts an activity for a result (SAF folder/document picker, photo picker,
     * system credential screen…). The stop/start round trip that follows is not a real "leave the app",
     * so it must not relock — relocking would swap the UI to the lock screen and drop the result.
     */
    private var externalActivityInFlight = false
    private var backgroundedForExternalActivity = false

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

    /** Called right before the app launches an activity for a result. */
    fun onExternalActivityLaunch() {
        externalActivityInFlight = true
    }

    /** The launch failed (e.g. no activity handles the intent), so no round trip will happen. */
    fun onExternalActivityLaunchFailed() {
        externalActivityInFlight = false
    }

    /**
     * Called on resume. A launched activity that never stopped us (translucent/dialog) ends here; clearing
     * the marker keeps a later home/recents leave locking normally.
     */
    fun onResumed() {
        externalActivityInFlight = false
    }

    fun onBackground(nowMillis: Long, authenticating: Boolean) {
        backgroundedForExternalActivity = externalActivityInFlight
        externalActivityInFlight = false
        if (!authenticating) backgroundAtMillis = nowMillis
    }

    fun onForeground(nowMillis: Long, timeoutSeconds: Int) {
        val backgroundAt = backgroundAtMillis
        if (isEnabled && backgroundAt != null) {
            val away = nowMillis - backgroundAt
            // Safety cap: a picker left open for longer than EXTERNAL_ACTIVITY_GRACE_MILLIS (e.g. the user went
            // home from the picker and forgot about it) is treated as a normal leave and the timeout applies.
            val exempt = backgroundedForExternalActivity && away < EXTERNAL_ACTIVITY_GRACE_MILLIS
            if (!exempt && away >= timeoutSeconds * 1_000L) isLocked = true
        }
        backgroundAtMillis = null
        backgroundedForExternalActivity = false
        externalActivityInFlight = false
    }

    fun unlock() {
        isLocked = false
        backgroundAtMillis = null
    }

    companion object {
        /** Longest stay in an app-launched external activity that is still exempt from relocking. */
        const val EXTERNAL_ACTIVITY_GRACE_MILLIS: Long = 10 * 60 * 1_000L
    }
}
