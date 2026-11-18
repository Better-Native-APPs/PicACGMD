package app.picacgmd.feature.reader.ui

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.picacgmd.feature.reader.SystemBarsMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderSheetSystemBarsTest {
    @Test
    fun everyFullscreenOptionHidesTheSameBarsInTheSheetAndReader() {
        val fixture = Fixture()
        val reader = Any()
        val status = WindowInsetsCompat.Type.statusBars()
        val navigation = WindowInsetsCompat.Type.navigationBars()
        val all = WindowInsetsCompat.Type.systemBars()
        val expected = mapOf(
            SystemBarsMode.Visible to SystemBarsVisibility(shownTypes = all, hiddenTypes = 0),
            SystemBarsMode.HiddenStatus to SystemBarsVisibility(shownTypes = navigation, hiddenTypes = status),
            SystemBarsMode.HiddenNavigation to SystemBarsVisibility(shownTypes = status, hiddenTypes = navigation),
            SystemBarsMode.HiddenAll to SystemBarsVisibility(shownTypes = 0, hiddenTypes = all),
        )

        SystemBarsMode.entries.forEach { mode ->
            fixture.ownership.applyReader(reader, mode, lightBackground = false)
            val sheet = fixture.ownership.currentAppearance
            assertEquals(expected.getValue(mode), sheet.mode.visibility(), "Sheet opened in $mode")
            assertEquals(fixture.readerWindow.mode.visibility(), sheet.mode.visibility())
            assertEquals(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, sheet.behavior)
        }
    }

    @Test
    fun changingTheAppThemeWhileASheetIsOpenDoesNotRevealReaderBars() {
        val fixture = Fixture()
        fixture.ownership.applyReader(Any(), SystemBarsMode.HiddenAll, lightBackground = false)
        fixture.ownership.updateAppAppearance(lightBackground = true)

        val sheet = fixture.ownership.currentAppearance
        assertEquals(SystemBarsMode.HiddenAll, sheet.mode)
        assertEquals(fixture.readerWindow, sheet)
    }

    @Test
    fun leavingTheReaderRestoresVisibleBarsForBothWindows() {
        val fixture = Fixture()
        val reader = Any()
        fixture.ownership.applyReader(reader, SystemBarsMode.HiddenNavigation, lightBackground = false)
        val beforeSheet = fixture.readerWindow

        assertEquals(beforeSheet, fixture.ownership.currentAppearance)
        assertEquals(beforeSheet, fixture.readerWindow)

        fixture.ownership.releaseReader(reader)
        assertEquals(fixture.baseline, fixture.ownership.currentAppearance)
        assertEquals(fixture.baseline, fixture.readerWindow)
        assertEquals(0, fixture.ownership.currentAppearance.mode.visibility().hiddenTypes)
    }

    private class Fixture {
        val baseline = SystemBarsAppearance(
            mode = SystemBarsMode.Visible,
            lightStatusBars = true,
            lightNavigationBars = false,
            behavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
        )
        val applied = mutableListOf<SystemBarsAppearance>()
        val ownership = SystemBarsOwnership(baseline, applied::add)
        val readerWindow: SystemBarsAppearance get() = applied.last()
    }
}
