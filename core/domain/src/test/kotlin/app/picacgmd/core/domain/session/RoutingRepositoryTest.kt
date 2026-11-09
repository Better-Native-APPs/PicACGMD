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
    fun customIsItsOwnOptionAndMissingSyncedSelectionIsRetained() {
        val settings = AppSettings(apiRouteAddress = "203.0.113.9", customApiIp = "203.0.113.8")
        val state = apiRouteState(settings)
        assertEquals(RouteSource.SAVED, state.selected.source)
        assertEquals("203.0.113.9", state.selected.address)
        assertEquals("自定义", state.options.last().label)
        assertEquals(RouteSource.CUSTOM, state.options.last().source)
        assertEquals("203.0.113.8", state.customIp)
        assertEquals(RouteSource.CUSTOM, apiRouteState(settings.copy(apiRouteAddress = "203.0.113.8")).selected.source)
        assertEquals(ApiRoute.None, apiRouteState(settings.copy(apiRouteAddress = null)).selected.value)
    }

    @Test
    fun customAddressMatchingACatalogueEntryHasOnlyOneProbeAndRow() {
        val address = defaultApiAddresses.first()
        val state = apiRouteState(AppSettings(
            apiRouteAddress = address, customApiIp = address, syncedApiAddresses = listOf(address),
        ))
        assertEquals(state.options.size, state.options.map { it.value }.distinct().size)
        assertEquals(RouteSource.CUSTOM, state.selected.source)
        assertEquals("自定义", state.selected.label)
    }

    @Test
    fun imageOptionsHaveTheSameShapeAndKeepTheFixedOrder() {
        assertEquals(4, imageRouteState(AppSettings.DEFAULT).options.size)
        val state = imageRouteState(AppSettings(customImageHost = "images.example.com"))
        assertEquals(listOf(ImageServer.Original, ImageServer.Split1, ImageServer.Split2, ImageServer.Split3, ImageServer.Custom("https://images.example.com")), state.options.map { it.value })
        assertEquals(listOf("不分流", "分流 1", "分流 2", "分流 3", "自定义"), state.options.map { it.label })
        assertEquals("https://storage1.picacomic.com", state.selected.address)
        assertEquals(ImageServer.Original, state.selected.value)
    }

    @Test
    fun blankPersistedCustomImageHostResolvesToOriginalWithoutPlaceholder() {
        val settings = AppSettings(imageRoutingMode = RoutingMode.CUSTOM, customImageHost = "  ")
        assertEquals(ImageServer.Original, settings.toImageServer())
        assertEquals(ImageServer.Original, NetworkConfiguration.from(settings).imageServer)
        val state = imageRouteState(settings)
        assertEquals(4, state.options.size)
        assertEquals(ImageServer.Original, state.selected.value)
    }

    @Test
    fun syncPassesProxyPersistsValidAddressesAndResetDoesNotMoveSelection() = runTest {
        val settings = SettingsRepository(RouteMemoryStore())
        val provider = NetworkConfigProvider(settings.settings, backgroundScope)
        try {
            settings.setProxyUrl("http://127.0.0.1:8888")
            var receivedProxy: String? = null
            val repository = RoutingRepository(settings, provider, fetchAddresses = {
                receivedProxy = it
                listOf("104.19.53.76", "invalid", "104.19.53.76", defaultApiAddresses[0])
            }, nowMillis = { 456L })
            repository.syncApiRoutes()
            assertEquals("http://127.0.0.1:8888", receivedProxy)
            assertEquals(listOf("104.19.53.76", "172.67.7.24"), settings.settings.first().syncedApiAddresses)
            assertEquals(456L, repository.apiRoutes.first().lastSyncedAtMillis)
            repository.selectApiRoute(ApiRoute.Address("104.19.53.76"))
            repository.resetApiRoutes()
            val state = repository.apiRoutes.first()
            assertNull(state.lastSyncedAtMillis)
            assertEquals("104.19.53.76", state.selected.address)
            assertEquals(RouteSource.SAVED, state.selected.source)
            assertEquals(emptyList(), settings.settings.first().syncedApiAddresses)
            repository.selectApiRoute(ApiRoute.Address(defaultApiAddresses[0]))
            assertEquals(defaultApiAddresses, repository.apiRoutes.first().options.mapNotNull { it.address })
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
    fun customValidationAndSelectionsUseTheSameValuesAsTransports() = runTest {
        val settings = SettingsRepository(RouteMemoryStore())
        val provider = NetworkConfigProvider(settings.settings, backgroundScope)
        val repository = RoutingRepository(settings, provider)
        try {
            val before = settings.settings.first()
            assertFailsWith<IllegalArgumentException> { repository.setCustomApiRoute("example.com") }
            assertFailsWith<IllegalArgumentException> { repository.setCustomImageHost("https://user:pass@images.example.com") }
            assertEquals(before, settings.settings.first())
            repository.setCustomApiRoute(" 203.0.113.8 ")
            assertEquals("203.0.113.8", repository.apiRoutes.first().selected.address)
            repository.selectApiRoute(ApiRoute.None)
            assertEquals("203.0.113.8", repository.apiRoutes.first().customIp)
            assertEquals(ApiRoute.None, NetworkConfiguration.from(settings.settings.first()).networkConfig.apiRoute)
            repository.setCustomImageHost("images.example.com")
            val custom = ImageServer.Custom("https://images.example.com")
            assertEquals(custom, repository.imageRoutes.first().selected.value)
            assertEquals(custom, NetworkConfiguration.from(settings.settings.first()).imageServer)
            repository.selectImageRoute(ImageServer.Split3)
            assertEquals(ImageServer.Split3, repository.imageRoutes.first().selected.value)
            assertEquals("https://images.example.com", repository.imageRoutes.first().customHost)
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
    fun failedApiSelectionFallsBackToFastestReachableWithoutChangingRememberedCustomRoute() = runTest {
        val settings = SettingsRepository(RouteMemoryStore())
        val provider = NetworkConfigProvider(settings.settings, backgroundScope)
        val repository = RoutingRepository(settings, provider)
        try {
            repository.setCustomApiRoute("203.0.113.8")
            val fastest = ApiRoute.Address(defaultApiAddresses[1])
            val result = repository.fallbackApiRoute(mapOf(
                ApiRoute.Address("203.0.113.8") to null,
                ApiRoute.None to 900L,
                ApiRoute.Address(defaultApiAddresses[0]) to 850L,
                fastest to 700L,
            ))
            assertEquals(fastest, result?.value)
            assertEquals(fastest, repository.apiRoutes.first().selected.value)
            assertEquals("203.0.113.8", repository.apiRoutes.first().customIp)
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
