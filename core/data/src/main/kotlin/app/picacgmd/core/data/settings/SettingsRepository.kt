package app.picacgmd.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.picacgmd.core.data.settings.SettingsKeys as Keys

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * Typed, atomic Preferences writes. Read/write failures propagate to the caller; no empty-data fallback.
 * A Context factory shares the process-wide DataStore delegate; the constructor also accepts test stores.
 * Ordered category/keyword lists retain server spelling, remove duplicates, and reject blank entries.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = dataStore.data.map(SettingsPreferencesMapper::decode).distinctUntilChanged()

    suspend fun setAccountEmail(value: String) = set(Keys.accountEmail, value)

    // TODO: If remembering passwords is added, use EncryptedFile; never persist a password in Preferences.
    suspend fun setAuthToken(token: String?, updatedAt: Long = System.currentTimeMillis()) {
        require(token == null || token.isNotBlank()) { "Auth token must be nonblank or null" }
        dataStore.edit { preferences ->
            preferences.setNullable(Keys.authToken, token)
            preferences.setNullable(Keys.authTokenUpdatedAt, if (token != null) updatedAt else null)
        }
    }

    /** A null custom value preserves the previously configured address when switching built-in routes. */
    suspend fun setApiRouting(mode: RoutingMode, customIp: String? = null) {
        dataStore.edit { preferences ->
            require(mode != RoutingMode.CUSTOM || !(customIp ?: preferences[Keys.customApiIp]).isNullOrBlank()) {
                "Custom API routing requires an IP address"
            }
            preferences[Keys.apiRoutingMode] = mode.name
            if (customIp != null) preferences[Keys.customApiIp] = customIp
        }
    }

    suspend fun setImageRouting(mode: RoutingMode, customHost: String? = null) {
        dataStore.edit { preferences ->
            require(mode != RoutingMode.CUSTOM || !(customHost ?: preferences[Keys.customImageHost]).isNullOrBlank()) {
                "Custom image routing requires a host"
            }
            preferences[Keys.imageRoutingMode] = mode.name
            if (customHost != null) preferences[Keys.customImageHost] = customHost
        }
    }

    /** Empty disables the proxy; supported URL validation/testing belongs to the network/settings UI. */
    suspend fun setProxyUrl(value: String) = set(Keys.proxyUrl, value)
    suspend fun setImageQuality(value: ImageQuality) = setEnum(Keys.imageQuality, value)

    suspend fun setConnectTimeoutSeconds(value: Int) {
        SettingsValidation.requireTimeout(value)
        set(Keys.connectTimeoutSeconds, value)
    }

    suspend fun setReadTimeoutSeconds(value: Int) {
        SettingsValidation.requireTimeout(value)
        set(Keys.readTimeoutSeconds, value)
    }

    suspend fun setTimeOffsetHours(value: Int) {
        require(value in -12..12) { "Time offset must be -12..12 hours" }
        set(Keys.timeOffsetHours, value)
    }

    suspend fun setUseApiClientForImages(value: Boolean) = set(Keys.useApiClientForImages, value)
    suspend fun setTheme(value: AppTheme) = setEnum(Keys.theme, value)
    suspend fun setDarkMode(value: DarkMode) = setEnum(Keys.darkMode, value)
    suspend fun setPureBlack(value: Boolean) = set(Keys.pureBlack, value)
    suspend fun setAppOrientation(value: AppOrientation) = setEnum(Keys.appOrientation, value)

    suspend fun setDisplayModeId(value: Int?) {
        require(value == null || value > 0) { "Display mode ID must be positive or null (system)" }
        setNullable(Keys.displayModeId, value)
    }

    suspend fun setFullScreenDisplay(value: FullScreenDisplay) = setEnum(Keys.fullScreenDisplay, value)
    suspend fun setDoubleBackToExit(value: Boolean) = set(Keys.doubleBackToExit, value)
    suspend fun setStartupImageUri(value: String?) = setNullable(Keys.startupImageUri, value)
    suspend fun setListLayout(value: ListLayout) = setEnum(Keys.listLayout, value)

    suspend fun setListColumnCount(value: Int) {
        SettingsValidation.requireColumns(value)
        set(Keys.listColumnCount, value)
    }

    suspend fun setCategoryColumnCount(value: Int) {
        SettingsValidation.requireColumns(value)
        set(Keys.categoryColumnCount, value)
    }

    suspend fun setPaginationMode(value: PaginationMode) = setEnum(Keys.paginationMode, value)
    suspend fun setFailedReloadAction(value: FailedReloadAction) = setEnum(Keys.failedReloadAction, value)

    suspend fun setShieldedCategories(values: List<String>) = setList(Keys.shieldedCategories, values)
    suspend fun setCategoryShieldMode(value: CategoryShieldMode) = setEnum(Keys.categoryShieldMode, value)
    suspend fun setHiddenKeywords(values: List<String>) = setList(Keys.hiddenKeywords, values)
    suspend fun setHideViewedComics(value: Boolean) = set(Keys.hideViewedComics, value)
    suspend fun setHiddenCategories(values: List<String>) = setList(Keys.hiddenCategories, values)
    suspend fun setCategoryOrder(values: List<String>) = setList(Keys.categoryOrder, values)
    suspend fun setPinnedHomeCategories(values: List<String>) = setList(Keys.pinnedHomeCategories, values)
    suspend fun setShowAuthorSearch(value: Boolean) = set(Keys.showAuthorSearch, value)
    suspend fun setShowSubscriptions(value: Boolean) = set(Keys.showSubscriptions, value)
    suspend fun setShowTagFavourites(value: Boolean) = set(Keys.showTagFavourites, value)
    suspend fun setShowOnlineFavourites(value: Boolean) = set(Keys.showOnlineFavourites, value)

    suspend fun setCopyNameTemplate(value: String) = set(Keys.copyNameTemplate, value)
    suspend fun setConfirmCopy(value: Boolean) = set(Keys.confirmCopy, value)
    suspend fun setReaderMode(value: ReaderMode) = setEnum(Keys.readerMode, value)
    suspend fun setReaderDirection(value: ReaderDirection) = setEnum(Keys.readerDirection, value)
    suspend fun setReaderTapZones(value: ReaderTapZones) = setEnum(Keys.readerTapZones, value)
    suspend fun setVolumeKeyPaging(value: Boolean) = set(Keys.volumeKeyPaging, value)
    suspend fun setVolumeKeyChapterPaging(value: Boolean) = set(Keys.volumeKeyChapterPaging, value)
    suspend fun setReaderBackground(value: ReaderBackground) = setEnum(Keys.readerBackground, value)
    suspend fun setReaderFilter(value: ReaderFilter) = setEnum(Keys.readerFilter, value)
    suspend fun setKeepScreenOn(value: Boolean) = set(Keys.keepScreenOn, value)
    suspend fun setReaderFullScreen(value: Boolean) = set(Keys.readerFullScreen, value)
    suspend fun setFullScreenOnForward(value: Boolean) = set(Keys.fullScreenOnForward, value)
    suspend fun setReaderPageAnimation(value: Boolean) = set(Keys.readerPageAnimation, value)
    suspend fun setThreeZoneNextOnRight(value: Boolean) = set(Keys.threeZoneNextOnRight, value)
    suspend fun setReaderScrollMode(value: ReaderScrollMode) = setEnum(Keys.readerScrollMode, value)

    suspend fun setScrollScreenFraction(value: Float) {
        require(value in 0.1f..1f) { "Scroll screen fraction must be 0.1..1" }
        set(Keys.scrollScreenFraction, value)
    }

    suspend fun setReaderSliderPosition(value: ReaderSliderPosition) = setEnum(Keys.readerSliderPosition, value)
    suspend fun setTwoPagePlacement(value: TwoPagePlacement) = setEnum(Keys.twoPagePlacement, value)
    suspend fun setTwoPageCoverAlone(value: Boolean) = set(Keys.twoPageCoverAlone, value)
    suspend fun setReaderPreloadAhead(value: Int) = set(Keys.readerPreloadAhead, value.coerceIn(1..5))
    suspend fun setReaderAutoTurnSeconds(value: Int) = set(Keys.readerAutoTurnSeconds, value.coerceIn(2..30))

    suspend fun setGestureSpeed(value: Float) {
        require(value in 0.5f..3f) { "Gesture speed must be 0.5..3" }
        set(Keys.gestureSpeed, value)
    }

    suspend fun setReaderZoom(minimum: Float, maximum: Float, doubleTap: Float) {
        SettingsValidation.requireZoom(minimum, maximum, doubleTap)
        dataStore.edit { preferences ->
            preferences[Keys.minZoom] = minimum
            preferences[Keys.maxZoom] = maximum
            preferences[Keys.doubleTapZoom] = doubleTap
        }
    }

    suspend fun setLockDragBounds(value: Boolean) = set(Keys.lockDragBounds, value)
    suspend fun setEInkReduceAnimations(value: Boolean) = set(Keys.eInkReduceAnimations, value)
    suspend fun setEInkScrollByScreen(value: Boolean) = set(Keys.eInkScrollByScreen, value)

    suspend fun setEInkScrollAmount(value: Float) {
        require(value in 0.1f..1f) { "E-ink scroll amount must be 0.1..1" }
        set(Keys.eInkScrollAmount, value)
    }

    suspend fun setEInkTriggerDistanceCm(value: Float) {
        require(value in 0.1f..2f) { "E-ink trigger distance must be 0.1..2 cm" }
        set(Keys.eInkTriggerDistanceCm, value)
    }

    suspend fun setDownloadThreads(value: Int) {
        SettingsValidation.requireThreads(value)
        set(Keys.downloadThreads, value)
    }

    suspend fun setDownloadWifiOnly(value: Boolean) = set(Keys.downloadWifiOnly, value)
    suspend fun setAutoDownloadOnFavourite(value: Boolean) = set(Keys.autoDownloadOnFavourite, value)
    suspend fun setAutoDownloadWifiOnly(value: Boolean) = set(Keys.autoDownloadWifiOnly, value)
    suspend fun setAutoDeleteDownloadOnUnfavourite(value: Boolean) = set(Keys.autoDeleteDownloadOnUnfavourite, value)
    suspend fun setShowCommentsInDownloads(value: Boolean) = set(Keys.showCommentsInDownloads, value)
    suspend fun setAutoPunchIn(value: Boolean) = set(Keys.autoPunchIn, value)
    suspend fun setAppLockEnabled(value: Boolean) = set(Keys.appLockEnabled, value)
    suspend fun setBiometricUnlockEnabled(value: Boolean) = set(Keys.biometricUnlockEnabled, value)

    suspend fun setAppLockTimeoutSeconds(value: Int) {
        SettingsValidation.requireLockTimeout(value)
        set(Keys.appLockTimeoutSeconds, value)
    }

    suspend fun setPreventScreenshots(value: Boolean) = set(Keys.preventScreenshots, value)
    suspend fun setHistoryEnabled(value: Boolean) = set(Keys.historyEnabled, value)
    suspend fun setRecordDetailViews(value: Boolean) = set(Keys.recordDetailViews, value)
    suspend fun setAutoCleanCacheInterval(value: CacheCleanupInterval) = setEnum(Keys.autoCleanCacheInterval, value)

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    private suspend fun <T> setNullable(key: Preferences.Key<T>, value: T?) {
        dataStore.edit { it.setNullable(key, value) }
    }

    private suspend fun <T : Enum<T>> setEnum(key: Preferences.Key<String>, value: T) = set(key, value.name)

    private suspend fun setList(key: Preferences.Key<String>, values: List<String>) {
        require(values.all { it.isNotBlank() }) { "Preference lists cannot contain blank entries" }
        set(key, Json.encodeToString(values.distinct()))
    }

    private fun <T> MutablePreferences.setNullable(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    companion object {
        fun create(context: Context): SettingsRepository = SettingsRepository(context.applicationContext.appSettingsDataStore)
    }
}
