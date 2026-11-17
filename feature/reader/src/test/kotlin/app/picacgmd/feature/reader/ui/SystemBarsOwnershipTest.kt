package app.picacgmd.feature.reader.ui

import androidx.core.view.WindowInsetsControllerCompat
import app.picacgmd.feature.reader.SystemBarsMode
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemBarsOwnershipTest {
    @Test
    fun appWithoutAReaderAlwaysShowsBothBars() {
        val fixture = Fixture()
        fixture.ownership.updateAppAppearance(lightBackground = true)
        assertEquals(fixture.baseline.copy(lightNavigationBars = true), fixture.last)
        fixture.ownership.updateAppAppearance(lightBackground = false)
        assertEquals(fixture.baseline.copy(lightStatusBars = false), fixture.last)
    }

    @Test
    fun everyReaderModeTakesOwnershipWithoutChangingItsMeaning() {
        val fixture = Fixture()
        val reader = Any()
        SystemBarsMode.entries.forEach { mode ->
            fixture.ownership.applyReader(reader, mode, lightBackground = false)
            assertEquals(
                SystemBarsAppearance(
                    mode = mode,
                    lightStatusBars = false,
                    lightNavigationBars = false,
                    behavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                ),
                fixture.last,
            )
        }
    }

    @Test
    fun releasingReaderShowsBothBarsWithTheLatestAppAppearance() {
        val fixture = Fixture()
        val reader = Any()
        fixture.ownership.applyReader(reader, SystemBarsMode.HiddenStatus, lightBackground = false)
        fixture.ownership.updateAppAppearance(lightBackground = true)
        assertEquals(SystemBarsMode.HiddenStatus, fixture.last.mode)
        assertEquals(false, fixture.last.lightStatusBars)
        fixture.ownership.releaseReader(reader)
        assertEquals(fixture.baseline.copy(lightNavigationBars = true), fixture.last)
    }

    @Test
    fun anEarlierReaderCannotReleaseANewerReadersBars() {
        val fixture = Fixture()
        val earlier = Any()
        val current = Any()
        fixture.ownership.applyReader(earlier, SystemBarsMode.HiddenAll, lightBackground = false)
        fixture.ownership.applyReader(current, SystemBarsMode.HiddenStatus, lightBackground = true)
        val beforeRelease = fixture.last
        fixture.ownership.releaseReader(earlier)
        assertEquals(beforeRelease, fixture.last)
        fixture.ownership.releaseReader(current)
        assertEquals(fixture.baseline, fixture.last)
    }

    @Test
    fun readerCanReapplyItsModeAfterACancelledExit() {
        val fixture = Fixture()
        val reader = Any()
        fixture.ownership.applyReader(reader, SystemBarsMode.HiddenNavigation, lightBackground = false)
        val beforeExit = fixture.last
        fixture.ownership.releaseReader(reader)
        assertEquals(SystemBarsMode.Visible, fixture.last.mode)
        fixture.ownership.applyReader(reader, SystemBarsMode.HiddenNavigation, lightBackground = false)
        assertEquals(beforeExit, fixture.last)
    }

    @Test
    fun disposingTheHostClearsReaderOwnership() {
        val fixture = Fixture()
        val reader = Any()
        fixture.ownership.applyReader(reader, SystemBarsMode.HiddenAll, lightBackground = false)
        fixture.ownership.restoreApp()
        fixture.ownership.updateAppAppearance(lightBackground = true)
        fixture.ownership.releaseReader(reader)
        assertEquals(fixture.baseline.copy(lightNavigationBars = true), fixture.last)
    }

    private class Fixture {
        val baseline = SystemBarsAppearance(
            mode = SystemBarsMode.Visible,
            lightStatusBars = true,
            lightNavigationBars = false,
            behavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
        )
        private val applied = mutableListOf<SystemBarsAppearance>()
        val ownership = SystemBarsOwnership(baseline, applied::add)
        val last: SystemBarsAppearance get() = applied.last()
    }
}
