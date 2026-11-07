package app.picacgmd.core.designsystem.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Type scale from board 01: the 15 standard M3 styles (sizes and line heights match the M3
 * defaults) plus `*Emphasized` variants at weight 700 for large titles and key numbers.
 *
 * [fontFamily] defaults to the system sans-serif, which renders Latin and digits with Roboto and
 * Chinese with the system CJK font. Board 01 also asks for Roboto Flex at 112 % width on
 * emphasized styles; that needs a bundled TTF and can be passed in here once it is added.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun pikapikaTypography(fontFamily: FontFamily = FontFamily.Default): Typography {
    val base = Typography()
    fun TextStyle.regular() = copy(fontFamily = fontFamily)
    fun TextStyle.emphasized() = copy(fontFamily = fontFamily, fontWeight = FontWeight.Bold)
    return Typography(
        displayLarge = base.displayLarge.regular(),
        displayMedium = base.displayMedium.regular(),
        displaySmall = base.displaySmall.regular(),
        headlineLarge = base.headlineLarge.regular(),
        headlineMedium = base.headlineMedium.regular(),
        headlineSmall = base.headlineSmall.regular(),
        titleLarge = base.titleLarge.regular(),
        titleMedium = base.titleMedium.regular(),
        titleSmall = base.titleSmall.regular(),
        bodyLarge = base.bodyLarge.regular(),
        bodyMedium = base.bodyMedium.regular(),
        bodySmall = base.bodySmall.regular(),
        labelLarge = base.labelLarge.regular(),
        labelMedium = base.labelMedium.regular(),
        labelSmall = base.labelSmall.regular(),
        displayLargeEmphasized = base.displayLarge.emphasized(),
        displayMediumEmphasized = base.displayMedium.emphasized(),
        displaySmallEmphasized = base.displaySmall.emphasized(),
        headlineLargeEmphasized = base.headlineLarge.emphasized(),
        headlineMediumEmphasized = base.headlineMedium.emphasized(),
        headlineSmallEmphasized = base.headlineSmall.emphasized(),
        titleLargeEmphasized = base.titleLarge.emphasized(),
        titleMediumEmphasized = base.titleMedium.emphasized(),
        titleSmallEmphasized = base.titleSmall.emphasized(),
        bodyLargeEmphasized = base.bodyLarge.emphasized(),
        bodyMediumEmphasized = base.bodyMedium.emphasized(),
        bodySmallEmphasized = base.bodySmall.emphasized(),
        labelLargeEmphasized = base.labelLarge.emphasized(),
        labelMediumEmphasized = base.labelMedium.emphasized(),
        labelSmallEmphasized = base.labelSmall.emphasized(),
    )
}

/**
 * Tabular (fixed-width) figures for numbers that change in place: page numbers, reading progress,
 * download percentages, thread counts (board 01).
 */
fun TextStyle.withTabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")
