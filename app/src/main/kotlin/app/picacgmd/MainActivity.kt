package app.picacgmd

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.designsystem.theme.isDark
import app.picacgmd.ui.PikapikaApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Appearance settings (board 25) will feed these once the preferences store exists.
            val themeChoice = ThemeChoice.Dynamic
            val darkMode = DarkMode.FollowSystem
            val pureBlack = false

            val dark = darkMode.isDark()
            DisposableEffect(dark) {
                // Keep system bar icons readable when the app overrides the system dark setting.
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }

            PikapikaTheme(themeChoice = themeChoice, darkMode = darkMode, pureBlack = pureBlack) {
                PikapikaApp()
            }
        }
    }
}
