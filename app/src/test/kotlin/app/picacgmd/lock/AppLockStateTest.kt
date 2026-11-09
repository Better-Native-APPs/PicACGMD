package app.picacgmd.lock

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockStateTest {
    @Test
    fun coldStartRequiresAuthenticationRegardlessOfTimeout() {
        for (timeout in listOf(0, 1, 10, 60, 180, 600, 3_600)) {
            val state = AppLockState()
            state.configure(enabled = true, deviceSecure = true)
            state.onForeground(nowMillis = 10_000, timeoutSeconds = timeout)
            assertTrue(state.isLocked)
        }
    }

    @Test
    fun disabledOrUnsecuredDevicesAreNeverTrapped() {
        for ((enabled, secure) in listOf(false to true, true to false, false to false)) {
            val state = AppLockState()
            state.configure(enabled, secure)
            state.onBackground(0, authenticating = false)
            state.onForeground(10_000, timeoutSeconds = 0)
            assertFalse(state.isLocked)
        }
    }

    @Test
    fun allTimeoutsLockAtTheExactBoundary() {
        for (timeout in listOf(0, 1, 10, 60, 180, 600, 3_600)) {
            val state = unlockedState()
            state.onBackground(5_000, authenticating = false)
            state.onForeground(5_000 + timeout * 1_000L, timeout)
            assertTrue(state.isLocked)
        }
    }

    @Test
    fun returningBeforeTimeoutKeepsTheSessionUnlocked() {
        val state = unlockedState()
        state.onBackground(5_000, authenticating = false)
        state.onForeground(14_999, timeoutSeconds = 10)
        assertFalse(state.isLocked)
        state.onForeground(60_000, timeoutSeconds = 10)
        assertFalse(state.isLocked, "A foreground stay must not reuse an earlier background timestamp")
    }

    @Test
    fun authenticationUiDoesNotStartANewTimeout() {
        val state = unlockedState()
        state.onBackground(5_000, authenticating = true)
        state.onForeground(60_000, timeoutSeconds = 0)
        assertFalse(state.isLocked)
    }

    @Test
    fun successfulAuthenticationClearsBackgroundTime() {
        val state = unlockedState()
        state.onBackground(5_000, authenticating = false)
        state.unlock()
        state.onForeground(60_000, timeoutSeconds = 0)
        assertFalse(state.isLocked)
    }

    @Test
    fun settingsUpdatesDoNotRelockAnAuthenticatedSession() {
        val state = unlockedState()
        state.configure(enabled = true, deviceSecure = true)
        assertFalse(state.isLocked)
        state.configure(enabled = false, deviceSecure = true)
        state.configure(enabled = true, deviceSecure = true)
        assertTrue(state.isLocked)
    }

    private fun unlockedState() = AppLockState().apply {
        configure(enabled = true, deviceSecure = true)
        unlock()
    }
}
