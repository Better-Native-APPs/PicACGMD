package app.picacgmd.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoubleBackExitTest {
    @Test fun firstBackNeverExitsEvenAtBoot() {
        assertFalse(DoubleBackExit().shouldExit(0))
    }

    @Test fun secondBackWithinTwoSecondsExits() {
        val policy = DoubleBackExit()
        assertFalse(policy.shouldExit(100))
        assertTrue(policy.shouldExit(2_100))
    }

    @Test fun expiredOrNonMonotonicBackStartsAgain() {
        val policy = DoubleBackExit()
        assertFalse(policy.shouldExit(3_000))
        assertFalse(policy.shouldExit(5_001))
        assertFalse(policy.shouldExit(4_000))
        assertTrue(policy.shouldExit(4_001))
    }
}
