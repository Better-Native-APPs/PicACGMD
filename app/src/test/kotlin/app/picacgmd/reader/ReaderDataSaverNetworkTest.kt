package app.picacgmd.reader

import android.content.Intent
import android.net.ConnectivityManager
import android.os.Looper
import app.picacgmd.core.data.settings.ImageQuality
import app.picacgmd.feature.reader.ReaderFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderDataSaverNetworkTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)

    @Test fun enabledRequestsLowButWhitelistingAndDisablingRestoreTheStoredQuality() = runTest {
        setReaderTestNetwork(unmetered = true)
        val monitor = ReaderNetworkMonitor(context)
        for (stored in listOf(ImageQuality.HIGH, ImageQuality.ORIGINAL)) {
            ReaderDataSaverFixture(stored).use { fixture ->
                val user = fixture.settings.settings.first().toReaderConfig()
                val source = fixture.source {
                    val state = monitor.currentState()
                    effectiveReaderLoading(state.dataSaverEnabled, state.metered, user.quality, user.preloadAhead).quality
                }
                val chapters = source.loadChapters()

                for (status in listOf(
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED,
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
                )) {
                    shadowOf(connectivity).setRestrictBackgroundStatus(status)
                    source.loadPages(chapters.first())
                    assertEquals(
                        if (status == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) "low" else stored.name.lowercase(),
                        fixture.requests.last().header("image-quality"),
                    )
                    assertEquals(stored, fixture.settings.settings.first().imageQuality)
                }
            }
        }
    }

    @Test fun meteredNetworksLimitPreloadEvenWhenDataSaverIsOff() = runTest {
        shadowOf(connectivity).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)
        setReaderTestNetwork(unmetered = false)
        val state = ReaderNetworkMonitor(context).states.first()

        assertTrue(state.metered)
        assertFalse(state.dataSaverEnabled)
        assertEquals(1, ReaderDataSaverFixture().use { it.defaults.toReaderConfig().withNetworkPreload(state).preloadAhead })
    }

    @Test fun readerWriteBackDoesNotPersistTheEffectivePreloadOrQuality() = runTest {
        setReaderTestNetwork(unmetered = true)
        shadowOf(connectivity).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        val state = ReaderNetworkMonitor(context).states.first()

        for (stored in listOf(ImageQuality.HIGH, ImageQuality.ORIGINAL)) {
            ReaderDataSaverFixture(stored).use { fixture ->
                val user = fixture.settings.settings.first().toReaderConfig()
                val displayed = user.withNetworkPreload(state)
                val next = displayed.copy(filter = ReaderFilter.Grayscale).withUserLoadingPreferences(displayed, user)
                fixture.settings.saveReaderConfig(user, next)

                val saved = fixture.settings.settings.first()
                assertEquals(stored, saved.imageQuality)
                assertEquals(5, saved.readerPreloadAhead)
                assertEquals(ReaderFilter.Grayscale, saved.toReaderConfig().filter)
            }
        }
    }

    @Test fun openReaderObservesBroadcastsAndDefaultNetworkChanges() = runTest {
        setReaderTestNetwork(unmetered = true)
        shadowOf(connectivity).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)
        val states = mutableListOf<ReaderNetworkState>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ReaderNetworkMonitor(context).states.toList(states)
        }
        runCurrent()
        assertEquals(ReaderNetworkState(false, false), states.last())

        shadowOf(connectivity).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        context.sendBroadcast(Intent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(ReaderNetworkState(true, false), states.last())

        setReaderTestNetwork(unmetered = false)
        val active = checkNotNull(connectivity.activeNetwork)
        val capabilities = checkNotNull(connectivity.getNetworkCapabilities(active))
        shadowOf(connectivity).networkCallbacks.toList().forEach { it.onCapabilitiesChanged(active, capabilities) }
        runCurrent()
        assertEquals(ReaderNetworkState(true, true), states.last())

        shadowOf(connectivity).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED)
        context.sendBroadcast(Intent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(ReaderNetworkState(false, true), states.last())

        setReaderTestNetwork(unmetered = true)
        shadowOf(connectivity).networkCallbacks.toList().forEach {
            it.onCapabilitiesChanged(checkNotNull(connectivity.activeNetwork), checkNotNull(connectivity.getNetworkCapabilities(connectivity.activeNetwork)))
        }
        runCurrent()
        assertEquals(ReaderNetworkState(false, false), states.last())
        observer.cancel()
        runCurrent()
    }
}
