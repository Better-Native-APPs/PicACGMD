package app.picacgmd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.Display
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.AppTheme
import app.picacgmd.core.data.settings.AppOrientation
import app.picacgmd.core.data.settings.FailedReloadAction
import app.picacgmd.core.designsystem.component.ImageFailureReloadAction
import app.picacgmd.core.designsystem.component.LocalImageFailureReloadAction
import app.picacgmd.core.designsystem.component.StatusPane
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.designsystem.theme.isDark
import app.picacgmd.core.designsystem.window.LocalSecureWindows
import app.picacgmd.core.domain.session.SessionState
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.account.LoginScreen
import app.picacgmd.lock.AppLockAuthenticator
import app.picacgmd.lock.AppLockScreen
import app.picacgmd.lock.AppLockState
import app.picacgmd.navigation.ExternalNavigation
import app.picacgmd.navigation.externalNavigation
import app.picacgmd.ui.PikapikaApp
import app.picacgmd.reader.toSystemBarsMode
import app.picacgmd.feature.reader.ui.ReaderSystemBars
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import app.picacgmd.core.data.settings.DarkMode as SettingsDarkMode

class MainActivity : ComponentActivity() {
    private val pikaApplication get() = application as PikapikaApplication
    private var settings by mutableStateOf<AppSettings?>(null)
    private var settingsError by mutableStateOf<String?>(null)
    private var pendingNavigation by mutableStateOf<ExternalNavigation?>(null)
    private var settingsJob: Job? = null
    private val lockState = AppLockState()
    private var locked by mutableStateOf(true)
    private var authenticationError by mutableStateOf<String?>(null)
    private lateinit var authenticator: AppLockAuthenticator
    private var obscureForRecents = false

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Stay secure until the first settings snapshot, before any app content can be drawn.
        updateLockWindow()
        authenticator = AppLockAuthenticator(
            this,
            onUnlocked = {
                lockState.unlock()
                authenticationError = null
                updateLockWindow()
            },
            onError = { authenticationError = it },
        )
        splash.setKeepOnScreenCondition { settings == null && settingsError == null }
        enableEdgeToEdge()
        acceptIntent(intent)
        observeSettings()
        setContent {
            val container = pikaApplication.appContainer
            val preferences = settings ?: AppSettings.DEFAULT
            val themeChoice = when (preferences.theme) {
                AppTheme.DYNAMIC -> ThemeChoice.Dynamic
                AppTheme.BRAND_PINK -> ThemeChoice.Brand
                AppTheme.BOOK -> ThemeChoice.Book
                AppTheme.GREY_BLUE -> ThemeChoice.Dusty
            }
            val darkMode = when (preferences.darkMode) {
                SettingsDarkMode.SYSTEM -> DarkMode.FollowSystem
                SettingsDarkMode.LIGHT -> DarkMode.Light
                SettingsDarkMode.DARK -> DarkMode.Dark
            }
            val dark = darkMode.isDark()
            ReaderSystemBars(preferences.fullScreenDisplay.toSystemBarsMode(), lightBackground = !dark)
            var startupImagePending by rememberSaveable { mutableStateOf(savedInstanceState == null) }
            var startupImageLoaded by remember { mutableStateOf(false) }
            val showStartupImage = startupImagePending && settings != null && !locked && preferences.startupImageUri != null
            LaunchedEffect(settings != null, locked, showStartupImage, startupImageLoaded) {
                if (settings != null && !locked) {
                    if (showStartupImage) delay(if (startupImageLoaded) 800 else 2_000)
                    startupImagePending = false
                }
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }
            val session by container.sessionManager.state.collectAsStateWithLifecycle()
            val unlockedContent = rememberSaveableStateHolder()
            // Only whether anything is downloading matters here; collecting progress itself would
            // recompose the whole app on every engine tick.
            val downloadsActive by remember {
                pikaApplication.downloadManager.progress.map { it.activeComics > 0 }.distinctUntilChanged()
            }.collectAsStateWithLifecycle(false)
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(downloadsActive, session, locked) {
                if (!locked && settings != null && Build.VERSION.SDK_INT >= 33 && session is SessionState.SignedIn && downloadsActive) {
                    val history = getSharedPreferences("system_permissions", MODE_PRIVATE)
                    if (!history.getBoolean("notifications_requested", false)) {
                        history.edit().putBoolean("notifications_requested", true).apply()
                        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
            }
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalSecureWindows provides (settings?.preventScreenshots ?: true),
                LocalImageFailureReloadAction provides if (preferences.failedReloadAction == FailedReloadAction.TAP) {
                    ImageFailureReloadAction.Tap
                } else ImageFailureReloadAction.PullDown,
            ) {
                PikapikaTheme(themeChoice, darkMode, preferences.pureBlack) {
                    Surface(Modifier.fillMaxSize()) {
                        when {
                            settingsError != null -> StatusPane(
                                title = "读取设置失败",
                                description = settingsError,
                                actions = { Button(onClick = ::observeSettings) { Text("重试") } },
                            )
                            settings == null -> Box(
                                Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                            ) { LoadingIndicator() }
                            locked -> AppLockScreen(authenticationError, ::unlock, ::finish)
                            showStartupImage -> AsyncImage(
                                model = preferences.startupImageUri,
                                contentDescription = "启动图片",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                                onSuccess = { startupImageLoaded = true },
                                onError = { startupImagePending = false },
                            )
                            session == SessionState.Initializing -> Box(
                                Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                            ) { LoadingIndicator() }
                            session is SessionState.SignedIn -> unlockedContent.SaveableStateProvider("app") {
                                PikapikaApp(
                                    externalNavigation = pendingNavigation,
                                    onExternalNavigationConsumed = { pendingNavigation = null },
                                    onExternalComicExit = ::finish,
                                )
                            }
                            else -> LoginScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        settings?.let {
            lockState.configure(it.appLockEnabled, authenticator.isDeviceSecure)
            lockState.onForeground(SystemClock.elapsedRealtime(), it.appLockTimeoutSeconds)
            updateLockWindow()
        }
    }

    override fun onResume() {
        super.onResume()
        obscureForRecents = false
        updateLockWindow()
    }

    override fun onPause() {
        // Secure the window before the system captures a background/recents thumbnail.
        obscureForRecents = true
        updateLockWindow()
        super.onPause()
    }

    override fun onStop() {
        lockState.onBackground(SystemClock.elapsedRealtime(), authenticator.isAuthenticating)
        super.onStop()
    }

    override fun onDestroy() {
        authenticator.cancel()
        super.onDestroy()
    }

    private fun unlock() {
        authenticationError = null
        authenticator.authenticate(settings?.biometricUnlockEnabled == true)
    }

    private fun updateLockWindow() {
        locked = lockState.isLocked
        val secure = settings == null || locked || settings?.preventScreenshots == true || obscureForRecents && lockState.isEnabled
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!secure)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }

    private fun acceptIntent(intent: Intent) {
        val target = intent.externalNavigation()
        if (target != null) pendingNavigation = target
        else if (intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_VIEW)) {
            Toast.makeText(this, "未找到有效漫画链接", Toast.LENGTH_SHORT).show()
            if (settings == null) finish()
        }
    }

    private fun observeSettings() {
        settingsJob?.cancel()
        settingsError = null
        settingsJob = lifecycleScope.launch {
            try {
                pikaApplication.appContainer.settingsRepository.settings.collect { snapshot ->
                    settings = snapshot
                    applyDisplayPreferences(snapshot)
                    lockState.configure(snapshot.appLockEnabled, authenticator.isDeviceSecure)
                    updateLockWindow()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                settingsError = exception.localizedMessage ?: "读取本机设置失败"
            }
        }
    }

    @android.annotation.SuppressLint("SourceLockedOrientationActivity")
    private fun applyDisplayPreferences(snapshot: AppSettings) {
        // The reader has no separate orientation lock: the application preference wins everywhere.
        val orientation = when (snapshot.appOrientation) {
            AppOrientation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            AppOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            AppOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        if (requestedOrientation != orientation) requestedOrientation = orientation
        // The first settings emission can precede attachment of the decor view.
        val display = window.decorView.display
            ?: getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val modes = display?.supportedModes.orEmpty()
        val mode = snapshot.displayModeId?.takeIf { id -> modes.any { it.modeId == id } } ?: 0
        if (window.attributes.preferredDisplayModeId != mode) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = mode }
        }
    }
}
