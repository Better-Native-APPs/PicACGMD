package app.picacgmd.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Custom color roles that Material 3 does not define (design board 01):
 * success (e.g. the 「完结」 tag, finished downloads) and warning.
 * Read them through [PikapikaTheme.extendedColors].
 */
@Immutable
data class PikapikaExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val LocalPikapikaExtendedColors = staticCompositionLocalOf { BrandLightExtendedColors }

/**
 * Fixed reader backgrounds, independent of the app theme (board 01 / 16).
 *
 * [chromeColorScheme] is the scheme the floating reader toolbars use on top of that background:
 * brand-dark on black / gray, book-light on paper, brand-light on white.
 */
enum class ReaderBackground(val background: Color, val content: Color) {
    Black(Color(0xFF000000), Color(0xFFFFFFFF)),
    Gray(Color(0xFF3A3A3A), Color(0xFFFFFFFF)),
    Paper(Color(0xFFF4EEDF), Color(0xFF4A3B22)),
    White(Color(0xFFFFFFFF), Color(0xFF333333)),
    ;

    val chromeColorScheme: ColorScheme
        get() = when (this) {
            Black, Gray -> BrandDarkColorScheme
            Paper -> BookLightColorScheme
            White -> BrandLightColorScheme
        }

    companion object {
        /** Placeholder behind pages that are still loading, on every reader background. */
        val LoadingPlaceholder = Color(0xFF17151A)
    }
}
