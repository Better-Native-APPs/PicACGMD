package app.picacgmd.core.designsystem.theme

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/** Color source of the app theme (board 05, appearance settings 25). */
enum class ThemeChoice {
    /** Wallpaper colors on Android 12+; falls back to [Brand] on Android 10/11. Default. */
    Dynamic,

    /** 品牌粉, seed #E8467C. */
    Brand,

    /** 书本, seed #A5824F. */
    Book,

    /** 灰蓝, seed #5F7F9E. */
    Dusty,
}

/** 跟随系统 / 浅色 / 深色. */
enum class DarkMode { FollowSystem, Light, Dark }

/** Whether [this] mode currently resolves to a dark theme. */
@Composable
@ReadOnlyComposable
fun DarkMode.isDark(): Boolean = when (this) {
    DarkMode.FollowSystem -> isSystemInDarkTheme()
    DarkMode.Light -> false
    DarkMode.Dark -> true
}

/** True when [ThemeChoice.Dynamic] can actually use wallpaper colors on this device. */
@get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
val isDynamicColorSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Root theme: Material 3 Expressive with the expressive motion scheme, the board-01 type and shape
 * scales, and the color scheme picked by [themeChoice] / [darkMode].
 *
 * @param pureBlack only applies in dark mode: page backgrounds become #000000 for OLED screens,
 * while the surface-container steps stay distinguishable.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PikapikaTheme(
    themeChoice: ThemeChoice = ThemeChoice.Dynamic,
    darkMode: DarkMode = DarkMode.FollowSystem,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = darkMode.isDark()
    val context = LocalContext.current
    val baseScheme = if (themeChoice == ThemeChoice.Dynamic && isDynamicColorSupported) {
        // Wallpaper color changes recreate the activity, so remembering per context is safe.
        remember(context, dark) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
    } else {
        staticColorScheme(themeChoice, dark)
    }
    val colorScheme = remember(baseScheme, dark, pureBlack) {
        if (dark && pureBlack) baseScheme.toPureBlack() else baseScheme
    }
    val typography = remember { pikapikaTypography() }
    CompositionLocalProvider(LocalPikapikaExtendedColors provides extendedColors(themeChoice, dark)) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = PikapikaShapes,
            typography = typography,
            content = content,
        )
    }
}

/** Accessors for theme values that [androidx.compose.material3.MaterialTheme] does not carry. */
object PikapikaTheme {
    val extendedColors: PikapikaExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalPikapikaExtendedColors.current
}

/** Static scheme for a palette; [ThemeChoice.Dynamic] maps to the brand fallback. */
fun staticColorScheme(themeChoice: ThemeChoice, dark: Boolean): ColorScheme = when (themeChoice) {
    ThemeChoice.Dynamic, ThemeChoice.Brand -> if (dark) BrandDarkColorScheme else BrandLightColorScheme
    ThemeChoice.Book -> if (dark) BookDarkColorScheme else BookLightColorScheme
    ThemeChoice.Dusty -> if (dark) DustyDarkColorScheme else DustyLightColorScheme
}

/**
 * Success / warning roles. Wallpaper schemes reuse the brand set: harmonizing only shifts the hue
 * by a few degrees, and these roles are never the dominant color on screen.
 */
private fun extendedColors(themeChoice: ThemeChoice, dark: Boolean): PikapikaExtendedColors =
    when (themeChoice) {
        ThemeChoice.Dynamic, ThemeChoice.Brand -> if (dark) BrandDarkExtendedColors else BrandLightExtendedColors
        ThemeChoice.Book -> if (dark) BookDarkExtendedColors else BookLightExtendedColors
        ThemeChoice.Dusty -> if (dark) DustyDarkExtendedColors else DustyLightExtendedColors
    }

/**
 * OLED variant of a dark scheme: page backgrounds go fully black and each container step is pulled
 * toward black, keeping their order so cards, bars and sheets still separate from the page.
 */
internal fun ColorScheme.toPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = lerp(surfaceContainerLow, Color.Black, 0.5f),
    surfaceContainer = lerp(surfaceContainer, Color.Black, 0.45f),
    surfaceContainerHigh = lerp(surfaceContainerHigh, Color.Black, 0.4f),
    surfaceContainerHighest = lerp(surfaceContainerHighest, Color.Black, 0.35f),
)
