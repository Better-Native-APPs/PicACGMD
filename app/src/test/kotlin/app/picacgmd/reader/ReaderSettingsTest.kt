package app.picacgmd.reader

import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.FullScreenDisplay
import app.picacgmd.feature.reader.SystemBarsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderSettingsTest {
    @Test fun systemBarModesRemainDistinct() {
        assertEquals(SystemBarsMode.Visible, AppSettings(fullScreenDisplay = FullScreenDisplay.OFF).toReaderConfig().systemBars)
        assertEquals(SystemBarsMode.HiddenNavigation, AppSettings(fullScreenDisplay = FullScreenDisplay.HIDE_NAVIGATION).toReaderConfig().systemBars)
        assertEquals(SystemBarsMode.HiddenAll, AppSettings(fullScreenDisplay = FullScreenDisplay.HIDE_SYSTEM_BARS).toReaderConfig().systemBars)
    }

    @Test fun mapsEInkPreferencesWithoutChangingNormalPagingDefaults() {
        val config = AppSettings(eInkScrollByScreen = true, eInkScrollAmount = 0.6f, eInkTriggerDistanceCm = 1.2f).toReaderConfig()
        assertTrue(config.eInkScrollByScreen)
        assertEquals(0.6f, config.eInkScrollAmount)
        assertEquals(1.2f, config.eInkTriggerDistanceCm)
        assertEquals(0.8f, config.scrollFraction)
    }
}
