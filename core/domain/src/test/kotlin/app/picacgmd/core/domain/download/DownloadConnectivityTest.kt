package app.picacgmd.core.domain.download

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadConnectivityTest {
    @Test
    fun restrictionBlocksOnlyWhenEnabledWithoutAnUnmeteredNetwork() {
        for (restricted in listOf(false, true)) {
            for (connected in listOf(false, true)) {
                for (unmetered in listOf(false, true)) {
                    assertEquals(restricted && !unmetered, wifiRuleBlocks(restricted, DownloadNetworkState(connected, unmetered)))
                }
            }
        }
    }
}
