package app.picacgmd.ui

/** Uses monotonic time; a cancelled predictive gesture never reaches this policy. */
internal class DoubleBackExit {
    private var firstBackAt: Long? = null

    fun shouldExit(nowMillis: Long): Boolean {
        val previous = firstBackAt
        firstBackAt = nowMillis
        return previous != null && nowMillis - previous in 0..2_000
    }
}
