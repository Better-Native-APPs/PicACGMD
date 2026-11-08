package app.picacgmd.feature.reader.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.picacgmd.feature.reader.ReaderBackground

/**
 * Reader colours are fixed and independent of the app theme: black and gray backgrounds use the
 * dark brand chrome, paper uses the book-light chrome and white uses the light brand chrome.
 */
@Immutable
internal data class ReaderPalette(
    /** Page background behind and between images. */
    val background: Color,
    /** Text drawn straight on the background (chapter-level states). */
    val onBackground: Color,
    /** Scheme for floating chrome (capsules, sheets, buttons). */
    val chrome: ColorScheme,
    /** 「已下载」 marks; Material has no success role. */
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

private val DarkSuccess = Triple(Color(0xFF92D877), Color(0xFF334E28), Color(0xFFD4E9C6))
private val LightSuccess = Triple(Color(0xFF2C6C18), Color(0xFFD2EAC3), Color(0xFF042100))

private fun readerPalette(
    background: Color,
    onBackground: Color,
    chrome: ColorScheme,
    success: Triple<Color, Color, Color>,
) = ReaderPalette(background, onBackground, chrome, success.first, success.second, success.third)

internal fun ReaderBackground.palette(): ReaderPalette = when (this) {
    ReaderBackground.Black -> readerPalette(Color(0xFF000000), Color(0xFFFFFFFF), ReaderDarkScheme, DarkSuccess)
    ReaderBackground.Gray -> readerPalette(Color(0xFF3A3A3A), Color(0xFFFFFFFF), ReaderDarkScheme, DarkSuccess)
    ReaderBackground.Paper -> readerPalette(Color(0xFFF4EEDF), Color(0xFF4A3B22), ReaderBookLightScheme, LightSuccess)
    ReaderBackground.White -> readerPalette(Color(0xFFFFFFFF), Color(0xFF333333), ReaderBrandLightScheme, LightSuccess)
}

/** The palette of the reader subtree; provided by [ReaderTheme]. */
internal val LocalReaderPalette = staticCompositionLocalOf { ReaderBackground.Black.palette() }

/** 减少动画 (e-ink screens): changes happen at once instead of fading. Provided by [ReaderTheme]. */
internal val LocalReaderReduceMotion = staticCompositionLocalOf { false }

/** A fade for things that come and go, unless motion is reduced. */
@Composable
internal fun readerFadeIn(): EnterTransition =
    if (LocalReaderReduceMotion.current) EnterTransition.None else fadeIn()

@Composable
internal fun readerFadeOut(): ExitTransition =
    if (LocalReaderReduceMotion.current) ExitTransition.None else fadeOut()

/** Fixed colours of the per-page loading / failure block (always dark, on every background). */
internal object PageBlockColors {
    val Background = Color(0xFF17151A)
    val Label = Color(0xFFBBBBBB)
    val Title = Color(0xFFEEEEEE)
    val Body = Color(0xFFAAAAAA)
}

/** Page badge and short hint pills: translucent black with white text on every background. */
internal object ReaderPillColors {
    val Badge = Color(0x99000000)
    val Hint = Color(0xA6000000)
    val Content = Color(0xFFFFFFFF)
}

/** Wraps the reader subtree in its own expressive theme so it ignores the app's colours. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReaderTheme(
    background: ReaderBackground,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = background.palette()
    CompositionLocalProvider(
        LocalReaderPalette provides palette,
        LocalReaderReduceMotion provides reduceMotion,
    ) {
        MaterialExpressiveTheme(
            colorScheme = palette.chrome,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

/** `brand-dark` tokens. */
internal val ReaderDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB1C2),
    onPrimary = Color(0xFF66002C),
    primaryContainer = Color(0xFF8F0040),
    onPrimaryContainer = Color(0xFFFFD9E0),
    inversePrimary = Color(0xFFBB0056),
    secondary = Color(0xFFF5B7B2),
    onSecondary = Color(0xFF4C2523),
    secondaryContainer = Color(0xFF663B38),
    onSecondaryContainer = Color(0xFFFFDAD7),
    tertiary = Color(0xFFFFB599),
    onTertiary = Color(0xFF52220E),
    tertiaryContainer = Color(0xFF6E3822),
    onTertiaryContainer = Color(0xFFFFDBCE),
    surface = Color(0xFF1D1013),
    onSurface = Color(0xFFF6DCE0),
    surfaceVariant = Color(0xFF564145),
    onSurfaceVariant = Color(0xFFDDBFC4),
    inverseSurface = Color(0xFFF6DCE0),
    inverseOnSurface = Color(0xFF3C2C2F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA58A8F),
    outlineVariant = Color(0xFF564145),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF463538),
    surfaceContainer = Color(0xFF2A1C1F),
    surfaceContainerHigh = Color(0xFF352629),
    surfaceContainerHighest = Color(0xFF413034),
    surfaceContainerLow = Color(0xFF26181B),
    surfaceContainerLowest = Color(0xFF170B0D),
    surfaceDim = Color(0xFF1D1013),
    background = Color(0xFF1D1013),
    onBackground = Color(0xFFF6DCE0),
    surfaceTint = Color(0xFFFFB1C2),
)

/** `book-light` tokens. */
internal val ReaderBookLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF7E570F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB1),
    onPrimaryContainer = Color(0xFF624000),
    inversePrimary = Color(0xFFF3BD6E),
    secondary = Color(0xFF6F5B40),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFADEBC),
    onSecondaryContainer = Color(0xFF56442A),
    tertiary = Color(0xFF506440),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3EABD),
    onTertiaryContainer = Color(0xFF394C2B),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF201B13),
    surfaceVariant = Color(0xFFEFE0CF),
    onSurfaceVariant = Color(0xFF4F4539),
    inverseSurface = Color(0xFF362F27),
    inverseOnSurface = Color(0xFFFBEFE2),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF817567),
    outlineVariant = Color(0xFFD3C4B4),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F4),
    surfaceContainer = Color(0xFFF8ECDF),
    surfaceContainerHigh = Color(0xFFF3E6DA),
    surfaceContainerHighest = Color(0xFFEDE1D4),
    surfaceContainerLow = Color(0xFFFEF2E5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE4D8CC),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF201B13),
    surfaceTint = Color(0xFF7E570F),
)

/** `brand-light` tokens. */
internal val ReaderBrandLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFBB0056),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E0),
    onPrimaryContainer = Color(0xFF8F0040),
    inversePrimary = Color(0xFFFFB1C2),
    secondary = Color(0xFF82524E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD7),
    onSecondaryContainer = Color(0xFF663B38),
    tertiary = Color(0xFF8A4F37),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBCE),
    onTertiaryContainer = Color(0xFF6E3822),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF26181B),
    surfaceVariant = Color(0xFFFADBE0),
    onSurfaceVariant = Color(0xFF564145),
    inverseSurface = Color(0xFF3C2C2F),
    inverseOnSurface = Color(0xFFFFECEF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF8A7175),
    outlineVariant = Color(0xFFDDBFC4),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F7),
    surfaceContainer = Color(0xFFFFE8EC),
    surfaceContainerHigh = Color(0xFFFCE2E6),
    surfaceContainerHighest = Color(0xFFF6DCE0),
    surfaceContainerLow = Color(0xFFFFF0F1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFEED4D8),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF26181B),
    surfaceTint = Color(0xFFBB0056),
)
