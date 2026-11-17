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
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val defaults: AppSettings = AppSettings.DEFAULT,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        val current = if (Keys.legacyStartupImageUri in preferences.asMap()) {
            dataStore.edit { it.remove(Keys.legacyStartupImageUri) }
        } else preferences
        SettingsPreferencesMapper.decode(current, defaults)
    }.distinctUntilChanged()

    suspend fun setAccountEmail(value: String) = set(Keys.accountEmail, value)

    // TODO: If remembering passwords is added, use EncryptedFile; never persist a password in Preferences.
    suspend fun setAuthToken(token: String?, updatedAt: Long = System.currentTimeMillis()) {
        require(token == null || token.isNotBlank()) { "Auth token must be nonblank or null" }
        dataStore.edit { preferences ->
            preferences.setNullable(Keys.authToken, token)
            preferences.setNullable(Keys.authTokenUpdatedAt, if (token != null) updatedAt else null)
        }
    }

    /** Empty is the persisted sentinel for system DNS, distinct from an absent (legacy) key. */
    suspend fun setApiRouteAddress(address: String?) {
        require(address == null || address.isNotBlank()) { "请填写分流 IP 地址" }
        set(Keys.apiRouteAddress, address.orEmpty())
    }

    suspend fun setSyncedApiAddresses(addresses: List<String>, syncedAt: Long) {
        require(addresses.isNotEmpty() && addresses.all(String::isNotBlank)) { "未获取到分流地址" }
        dataStore.edit { preferences ->
            preferences[Keys.syncedApiAddresses] = Json.encodeToString(addresses.distinct())
            preferences[Keys.apiRoutesSyncedAt] = syncedAt
        }
    }

    suspend fun setImageRouting(mode: RoutingMode) = setEnum(Keys.imageRoutingMode, mode)
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
    suspend fun setLastSeenAnnouncementId(value: String?) = setNullable(Keys.lastSeenAnnouncementId, value)
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
    suspend fun setHideAiComics(value: Boolean) = set(Keys.hideAiComics, value)
    suspend fun setHiddenCategories(values: List<String>) = setList(Keys.hiddenCategories, values)
    suspend fun setCategoryOrder(values: List<String>) = setList(Keys.categoryOrder, values)
    suspend fun setPinnedHomeCategories(values: List<String>) = setList(Keys.pinnedHomeCategories, values)

    suspend fun setPinnedHomeChannels(values: Set<String>) {
        SettingsValidation.requireHomeChannels(values)
        set(Keys.pinnedHomeChannels, values.toSet())
    }

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
    suspend fun setReaderAutoScrollSpeed(value: Int) = set(Keys.readerAutoScrollSpeed, value.coerceIn(1..5))
    suspend fun setReaderAutoScrollPauseOnTouch(value: Boolean) = set(Keys.readerAutoScrollPauseOnTouch, value)
    suspend fun setReaderAutoNextChapter(value: Boolean) = set(Keys.readerAutoNextChapter, value)

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

    suspend fun setDownloadTreeUri(value: String?) {
        require(value == null || value.startsWith("content://") && value.isNotBlank()) { "Download location must be a content URI or null" }
        setNullable(Keys.downloadTreeUri, value)
    }

    /** A revoked old selection must not clear a newer folder chosen while validation was running. */
    suspend fun clearDownloadTreeUriIfMatches(expected: String) {
        dataStore.edit { if (it[Keys.downloadTreeUri] == expected) it.remove(Keys.downloadTreeUri) }
    }
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

    /** [address] is the canonical collection URL; folder changes do not change the account identity. */
    suspend fun setWebDavAccount(address: String, username: String, folder: String) {
        dataStore.edit {
            if (it[Keys.webDavAddress] != address || it[Keys.webDavUsername] != username) it.remove(Keys.webDavLastSyncedAt)
            it[Keys.webDavAddress] = address
            it[Keys.webDavUsername] = username
            it[Keys.webDavFolder] = folder
        }
    }

    suspend fun setWebDavAutoSync(value: Boolean) = set(Keys.webDavAutoSync, value)
    suspend fun setWebDavWifiOnly(value: Boolean) = set(Keys.webDavWifiOnly, value)
    suspend fun setWebDavLastSyncedAt(value: Long) = set(Keys.webDavLastSyncedAt, value)

    /** Unknown keys stay out until their backup policy has been explicitly audited. */
    suspend fun filterForSystemBackup(includeAuthentication: Boolean = false) {
        dataStore.edit { preferences ->
            preferences.remove(Keys.legacyStartupImageUri)
            val allowed = (portableBackupKeys + deviceBoundRestoreKeys +
                if (includeAuthentication) authenticationKeys else emptyList()).toSet()
            preferences.asMap().keys.filterNot { it in allowed }.forEach { preferences.remove(it) }
        }
    }

    /** Remove unknown/device-bound values before a restored store is used by the application. */
    suspend fun resetAfterSystemRestore(keepAuthentication: Boolean = false) {
        dataStore.edit { preferences ->
            val allowed = (portableBackupKeys + if (keepAuthentication) authenticationKeys else emptyList()).toSet()
            preferences.asMap().keys.filterNot { it in allowed }.forEach { preferences.remove(it) }
        }
    }

    /** Install authentication from a protected snapshot in one atomic edit, including an absent timestamp. */
    suspend fun restoreSystemBackupAuthentication(email: String, token: String?, updatedAt: Long?) {
        require(token == null || token.isNotBlank()) { "Auth token must be nonblank or null" }
        dataStore.edit { preferences ->
            preferences.setNullable(Keys.accountEmail, email.takeIf(String::isNotEmpty))
            preferences.setNullable(Keys.authToken, token)
            preferences.setNullable(Keys.authTokenUpdatedAt, if (token != null) updatedAt else null)
        }
    }

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
        private val authenticationKeys = listOf(Keys.accountEmail, Keys.authToken, Keys.authTokenUpdatedAt)
        private val deviceBoundRestoreKeys = listOf(
            Keys.legacyCustomApiIp, Keys.displayModeId, Keys.legacyStartupImageUri, Keys.downloadTreeUri,
            Keys.appLockEnabled, Keys.biometricUnlockEnabled, Keys.appLockTimeoutSeconds, Keys.webDavLastSyncedAt,
        )
        private val portableBackupKeys = listOf(
            Keys.apiRoutingMode, Keys.apiRouteAddress, Keys.syncedApiAddresses, Keys.apiRoutesSyncedAt,
            Keys.imageRoutingMode, Keys.timeOffsetHours,
            Keys.imageQuality, Keys.connectTimeoutSeconds, Keys.readTimeoutSeconds, Keys.useApiClientForImages,
            Keys.theme, Keys.darkMode, Keys.pureBlack, Keys.appOrientation, Keys.fullScreenDisplay,
            Keys.lastSeenAnnouncementId, Keys.listLayout, Keys.listColumnCount, Keys.categoryColumnCount,
            Keys.paginationMode, Keys.failedReloadAction, Keys.shieldedCategories, Keys.categoryShieldMode,
            Keys.hiddenKeywords, Keys.hideViewedComics, Keys.hideAiComics, Keys.hiddenCategories, Keys.categoryOrder,
            Keys.pinnedHomeCategories, Keys.pinnedHomeChannels, Keys.showAuthorSearch, Keys.showSubscriptions, Keys.showTagFavourites,
            Keys.showOnlineFavourites, Keys.copyNameTemplate, Keys.confirmCopy,
            Keys.readerMode, Keys.readerDirection, Keys.readerTapZones, Keys.volumeKeyPaging, Keys.volumeKeyChapterPaging,
            Keys.readerBackground, Keys.readerFilter, Keys.keepScreenOn, Keys.readerFullScreen, Keys.fullScreenOnForward,
            Keys.readerPageAnimation, Keys.threeZoneNextOnRight, Keys.readerScrollMode, Keys.scrollScreenFraction,
            Keys.readerSliderPosition, Keys.twoPagePlacement, Keys.twoPageCoverAlone, Keys.readerPreloadAhead,
            Keys.readerAutoTurnSeconds, Keys.readerAutoScrollSpeed, Keys.readerAutoScrollPauseOnTouch,
            Keys.readerAutoNextChapter, Keys.gestureSpeed, Keys.minZoom, Keys.maxZoom, Keys.doubleTapZoom, Keys.lockDragBounds,
            Keys.eInkReduceAnimations, Keys.eInkScrollByScreen, Keys.eInkScrollAmount, Keys.eInkTriggerDistanceCm,
            Keys.downloadThreads, Keys.downloadWifiOnly, Keys.autoDownloadOnFavourite, Keys.autoDownloadWifiOnly,
            Keys.autoDeleteDownloadOnUnfavourite, Keys.showCommentsInDownloads, Keys.autoPunchIn, Keys.preventScreenshots,
            Keys.historyEnabled, Keys.recordDetailViews, Keys.autoCleanCacheInterval,
            Keys.webDavAddress, Keys.webDavUsername, Keys.webDavFolder, Keys.webDavAutoSync, Keys.webDavWifiOnly,
        )

        fun create(context: Context, defaults: AppSettings = AppSettings.DEFAULT): SettingsRepository =
            SettingsRepository(context.applicationContext.appSettingsDataStore, defaults)
    }
}
