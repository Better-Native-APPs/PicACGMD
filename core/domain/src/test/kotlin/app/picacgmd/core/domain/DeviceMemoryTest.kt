package app.picacgmd.core.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceMemoryTest {
    @Test
    fun lessThanThreeGiBIsLowRamEvenWithoutTheSystemFlag() {
        assertTrue(isLowRamDevice(systemLowRam = false, totalMemoryBytes = 2L * GIB))
        assertTrue(isLowRamDevice(systemLowRam = false, totalMemoryBytes = 3L * GIB - 1))
    }

    @Test
    fun exactlyThreeGiBAndLargerDevicesKeepTheNormalPolicy() {
        assertFalse(isLowRamDevice(systemLowRam = false, totalMemoryBytes = 3L * GIB))
        assertFalse(isLowRamDevice(systemLowRam = false, totalMemoryBytes = 4L * GIB))
    }

    @Test
    fun theSystemLowRamFlagAlwaysApplies() {
        assertTrue(isLowRamDevice(systemLowRam = true, totalMemoryBytes = 4L * GIB))
    }

    companion object {
        private const val GIB = 1024L * 1024 * 1024
    }
}
