package app.picacgmd.core.domain.download

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadConnectivityTest {
    @Test
    fun wifiRuleBlocksOnlyWhenWifiOnlyIsEnabledWithoutWifi() {
        for (wifiOnly in listOf(false, true)) {
            for (connected in listOf(false, true)) {
                for (wifi in listOf(false, true)) {
                    assertEquals(wifiOnly && !wifi, wifiRuleBlocks(wifiOnly, DownloadNetworkState(connected, wifi)))
                }
            }
        }
    }
}
