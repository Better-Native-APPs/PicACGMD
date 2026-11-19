package app.picacgmd.reader

import app.picacgmd.feature.reader.ImageQuality
import app.picacgmd.feature.reader.ReaderConfig
import app.picacgmd.feature.reader.ReaderFilter
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderDataSaverPolicyTest {
    @Test fun qualityAndPreloadFollowAllFourNetworkCombinations() {
        for (dataSaver in listOf(false, true)) {
            for (metered in listOf(false, true)) {
                for (quality in ImageQuality.entries) {
                    val effective = effectiveReaderLoading(dataSaver, metered, quality, userPreloadAhead = 5)
                    assertEquals(if (dataSaver) ImageQuality.Low else quality, effective.quality)
                    assertEquals(if (dataSaver || metered) 1 else 5, effective.preloadAhead)
                }
            }
        }
    }

    @Test fun restrictingPreloadNeverIncreasesTheUsersValue() {
        for (preload in 0..5) {
            assertEquals(minOf(preload, 1), effectiveReaderLoading(true, false, ImageQuality.High, preload).preloadAhead)
            assertEquals(minOf(preload, 1), effectiveReaderLoading(false, true, ImageQuality.High, preload).preloadAhead)
            assertEquals(preload, effectiveReaderLoading(false, false, ImageQuality.High, preload).preloadAhead)
        }
    }

    @Test fun changingAnotherReaderOptionKeepsUserQualityAndPreload() {
        val user = ReaderConfig(quality = ImageQuality.Original, preloadAhead = 5)
        val displayed = user.withNetworkPreload(ReaderNetworkState(dataSaverEnabled = true, metered = false))
        assertEquals(ImageQuality.Original, displayed.quality)
        assertEquals(1, displayed.preloadAhead)

        val next = displayed.copy(filter = ReaderFilter.Grayscale).withUserLoadingPreferences(displayed, user)
        assertEquals(ImageQuality.Original, next.quality)
        assertEquals(5, next.preloadAhead)
        assertEquals(ReaderFilter.Grayscale, next.filter)
        assertEquals(5, next.withNetworkPreload(ReaderNetworkState(false, false)).preloadAhead)
    }

    @Test fun anExplicitQualityOrPreloadChangeStillChangesTheUsersPreference() {
        val user = ReaderConfig(quality = ImageQuality.High, preloadAhead = 5)
        val displayed = user.withNetworkPreload(ReaderNetworkState(true, true))
        val next = displayed.copy(quality = ImageQuality.Original, preloadAhead = 3)
            .withUserLoadingPreferences(displayed, user)

        assertEquals(ImageQuality.Original, next.quality)
        assertEquals(3, next.preloadAhead)
    }
}
