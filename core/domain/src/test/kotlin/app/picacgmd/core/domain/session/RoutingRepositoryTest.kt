package app.picacgmd.core.domain.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.RoutingMode
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.data.settings.defaultApiAddresses
import app.picacgmd.core.network.ApiRoute
import app.picacgmd.core.network.ImageServer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class RoutingRepositoryTest {
    @Test
    fun mergedOptionsPutServerAddressesFirstDeduplicateAndKeepSelectionByAddress() {
        val settings = AppSettings(
            syncedApiAddresses = listOf("104.19.53.76", "104.20.180.50", "104.19.53.76", "invalid"),
            apiRoutesSyncedAt = 123L,
        )
        val state = apiRouteState(settings)
        assertEquals(listOf(null, "104.19.53.76", "104.20.180.50", "172.67.7.24", "172.67.208.169"), state.options.map { it.address })
        assertEquals(listOf("不分流", "分流 1", "分流 2", "分流 3", "分流 4"), state.options.map { it.label })
        assertEquals(listOf(RouteSource.SYSTEM, RouteSource.SERVER, RouteSource.SERVER, RouteSource.BUILT_IN, RouteSource.BUILT_IN), state.options.map { it.source })
        assertEquals("172.67.7.24", state.selected.address)
        assertEquals("分流 3", state.selected.label)
        assertEquals(123L, state.lastSyncedAtMillis)
    }

    @Test
    fun selectionMissingFromTheListIsRetainedAsItsOwnOption() {
        val settings = AppSettings(apiRouteAddress = "203.0.113.9")
        val state = apiRouteState(settings)
        assertEquals(RouteSource.SAVED, state.selected.source)
        assertEquals("203.0.113.9", state.selected.address)
        assertEquals("分流 4", state.selected.label)
        assertEquals(state.options.size, state.options.map { it.value }.distinct().size)
        assertEquals(ApiRoute.None, apiRouteState(settings.copy(apiRouteAddress = null)).selected.value)
    }

    @Test
    fun imageOptionsHaveTheSameShapeAndKeepTheFixedOrder() {
        val state = imageRouteState(AppSettings.DEFAULT)
        assertEquals(listOf(ImageServer.Original, ImageServer.Split1, ImageServer.Split2, ImageServer.Split3), state.options.map { it.value })
        assertEquals(listOf("不分流", "分流 1", "分流 2", "分流 3"), state.options.map { it.label })
        assertEquals(listOf(RouteSource.SYSTEM, RouteSource.BUILT_IN, RouteSource.BUILT_IN, RouteSource.BUILT_IN), state.options.map { it.source })
        assertEquals("https://storage1.picacomic.com", state.selected.address)
        assertEquals(ImageServer.Original, state.selected.value)
        assertEquals(ImageServer.Split2, imageRouteState(AppSettings(imageRoutingMode = RoutingMode.ROUTE_2)).selected.value)
    }

    @Test
    fun syncPassesProxyPersistsValidAddressesAndALaterSyncDoesNotMoveSelection() = runTest {
        val settings = SettingsRepository(RouteMemoryStore())
        val provider = NetworkConfigProvider(settings.settings, backgroundScope)
        try {
            settings.setProxyUrl("http://127.0.0.1:8888")
            var receivedProxy: String? = null
            var served = listOf("104.19.53.76", "invalid", "104.19.53.76", defaultApiAddresses[0])
            var now = 456L
            val repository = RoutingRepository(settings, provider, fetchAddresses = {
                receivedProxy = it
                served
            }, nowMillis = { now })
            repository.syncApiRoutes()
            assertEquals("http://127.0.0.1:8888", receivedProxy)
            assertEquals(listOf("104.19.53.76", "172.67.7.24"), settings.settings.first().syncedApiAddresses)
            assertEquals(456L, repository.apiRoutes.first().lastSyncedAtMillis)
            repository.selectApiRoute(ApiRoute.Address("104.19.53.76"))
            assertEquals(RouteSource.SERVER, repository.apiRoutes.first().selected.source)
            // The server stops listing the selected address: it stays selected until the user moves on.
            served = listOf(defaultApiAddresses[1])
            now = 789L
            repository.syncApiRoutes()
            val state = repository.apiRoutes.first()
            assertEquals(789L, state.lastSyncedAtMillis)
            assertEquals("104.19.53.76", state.selected.address)
            assertEquals(RouteSource.SAVED, state.selected.source)
            repository.selectApiRoute(ApiRoute.Address(defaultApiAddresses[0]))
            assertEquals(
                listOf(defaultApiAddresses[1], defaultApiAddresses[0], defaultApiAddresses[2]),
                repository.apiRoutes.first().options.mapNotNull { it.address },
            )
        } finally {
            provider.close()
        }
    }

    @Test
    fun invalidAndFailedSyncLeavePreviousCatalogueAndTimestampUntouched() = runTest {
        val settings = SettingsRepository(RouteMemoryStore())
        val provider = NetworkConfigProvider(settings.settings, backgroundScope)
        try {
            settings.setSyncedApiAddresses(listOf("104.19.53.76"), 100L)
            val before = settings.settings.first()
            val invalid = RoutingRepository(settings, provider, fetchAddresses = { listOf("invalid") })
            assertFailsWith<IllegalArgumentException> { invalid.syncApiRoutes() }
            val failing = RoutingRepository(settings, provider, fetchAddresses = { throw IOException("offline") })
            assertTrue(assertFailsWith<IllegalStateException> { failing.syncApiRoutes() }.message!!.startsWith("分流同步失败"))
            val cancelled = RoutingRepository(settings, provider, fetchAddresses = { throw CancellationException("cancelled") })
            assertFailsWith<CancellationException> { cancelled.syncApiRoutes() }
            assertEquals(before, settings.settings.first())
        } finally {
            provider.close()
        }
    }

    @Test
    fun selectionsAreValidatedAndUseTheSameValuesAsTransports() = runTest {
        val settings = SettingsRepository(RouteMemoryStore())
        val provider = NetworkConfigProvider(settings.settings, backgroundScope)
        val repository = RoutingRepository(settings, provider)
        try {
            val before = settings.settings.first()
            assertFailsWith<IllegalArgumentException> { repository.selectApiRoute(ApiRoute.Address("example.com")) }
            assertEquals(before, settings.settings.first())
            repository.selectApiRoute(ApiRoute.Address(" 104.20.180.50 "))
            assertEquals("104.20.180.50", repository.apiRoutes.first().selected.address)
            assertEquals(ApiRoute.Address("104.20.180.50"), NetworkConfiguration.from(settings.settings.first()).networkConfig.apiRoute)
            repository.selectApiRoute(ApiRoute.None)
            assertEquals(ApiRoute.None, NetworkConfiguration.from(settings.settings.first()).networkConfig.apiRoute)
            repository.selectImageRoute(ImageServer.Split3)
            assertEquals(ImageServer.Split3, repository.imageRoutes.first().selected.value)
            assertEquals(ImageServer.Split3, NetworkConfiguration.from(settings.settings.first()).imageServer)
            repository.selectImageRoute(ImageServer.Original)
            assertEquals(ImageServer.Original, NetworkConfiguration.from(settings.settings.first()).imageServer)
        } finally {
            provider.close()
        }
    }

    @Test
    fun fastestRouteExcludesFailuresAndUnmeasuredOptionsAndRetainsOrderForTies() {
        val options = apiRouteState(AppSettings.DEFAULT).options
        val latencies = mapOf(options[0].value to null, options[1].value to 850L, options[2].value to 700L)
        assertEquals(options[2], fastestReachableRoute(options, latencies))
        assertEquals(options[1], fastestReachableRoute(options, latencies + (options[1].value to 700L)))
        assertNull(fastestReachableRoute(options, mapOf(options[0].value to null)))
    }

    @Test
    fun failedApiSelectionFallsBackToFastestReachableAndDropsTheUnlistedAddress() = runTest {
        val settings = SettingsRepository(RouteMemoryStore())
        val provider = NetworkConfigProvider(settings.settings, backgroundScope)
        val repository = RoutingRepository(settings, provider)
        try {
            repository.selectApiRoute(ApiRoute.Address("203.0.113.8"))
            assertEquals(RouteSource.SAVED, repository.apiRoutes.first().selected.source)
            val fastest = ApiRoute.Address(defaultApiAddresses[1])
            val result = repository.fallbackApiRoute(mapOf(
                ApiRoute.Address("203.0.113.8") to null,
                ApiRoute.None to 900L,
                ApiRoute.Address(defaultApiAddresses[0]) to 850L,
                fastest to 700L,
            ))
            assertEquals(fastest, result?.value)
            assertEquals(fastest, repository.apiRoutes.first().selected.value)
            assertEquals(defaultApiAddresses, repository.apiRoutes.first().options.mapNotNull { it.address })
        } finally {
            provider.close()
        }
    }

    @Test
    fun healthyUnmeasuredAndAllFailedSelectionsAreNotSilentlyChanged() = runTest {
        val settings = SettingsRepository(RouteMemoryStore())
        val provider = NetworkConfigProvider(settings.settings, backgroundScope)
        val repository = RoutingRepository(settings, provider)
        try {
            val original = repository.apiRoutes.first().selected.value
            assertNull(repository.fallbackApiRoute(mapOf(original to 900L, ApiRoute.None to 500L)))
            assertNull(repository.fallbackApiRoute(mapOf(ApiRoute.None to 500L)))
            assertNull(repository.fallbackApiRoute(mapOf(original to null, ApiRoute.None to null)))
            assertEquals(original, repository.apiRoutes.first().selected.value)
        } finally {
            provider.close()
        }
    }

    @Test
    fun failedImageSelectionFallsBackIndependentlyOfApiSelection() = runTest {
        val settings = SettingsRepository(RouteMemoryStore())
        val provider = NetworkConfigProvider(settings.settings, backgroundScope)
        val repository = RoutingRepository(settings, provider)
        try {
            val originalApi = repository.apiRoutes.first().selected.value
            val result = repository.fallbackImageRoute(mapOf(
                ImageServer.Original to null, ImageServer.Split1 to 700L, ImageServer.Split2 to 500L,
            ))
            assertEquals(ImageServer.Split2, result?.value)
            assertEquals(ImageServer.Split2, repository.imageRoutes.first().selected.value)
            assertEquals(originalApi, repository.apiRoutes.first().selected.value)
        } finally {
            provider.close()
        }
    }
}

private class RouteMemoryStore : DataStore<Preferences> {
    private val values = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = values
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(values.value).also { values.value = it }
}
