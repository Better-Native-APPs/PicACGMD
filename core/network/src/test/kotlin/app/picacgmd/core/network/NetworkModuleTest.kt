package app.picacgmd.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkModuleTest {
    @Test
    fun moduleNameIsNetwork() {
        assertEquals("network", NetworkModule.name)
    }
}
