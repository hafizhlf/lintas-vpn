package app.lintas.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val Paper = Color(0xFFF2EBDD)
val PaperLight = Color(0xFFFAF4E8)
val PaperDeep = Color(0xFFEADFCC)
val PaperInk = Color(0xFF1E2B24)
val PaperInkSoft = Color(0xFF4F5E53)
val PaperMuted = Color(0xFF6D7A6E)
val PaperFaint = Color(0xFF8C988D)
val PaperLine = Color(0xFFD2C4AE)
val PaperLineStrong = Color(0xFFB9AA92)
val StampOrange = Color(0xFFC8502C)
val StampGreen = Color(0xFF2E6A4E)
val StampBlue = Color(0xFF3B6580)

private val BukuLintasColors = lightColorScheme(
    primary = StampOrange,
    onPrimary = PaperLight,
    primaryContainer = PaperDeep,
    onPrimaryContainer = PaperInk,
    secondary = StampGreen,
    onSecondary = PaperLight,
    secondaryContainer = Color(0xFFDCE7D8),
    onSecondaryContainer = Color(0xFF163A2A),
    tertiary = StampBlue,
    onTertiary = PaperLight,
    background = Paper,
    onBackground = PaperInk,
    surface = PaperLight,
    onSurface = PaperInk,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = PaperMuted,
    outline = PaperLineStrong,
    outlineVariant = PaperLine,
    error = StampOrange,
    onError = PaperLight,
    scrim = PaperInk,
    surfaceTint = StampOrange,
)

private val LintasTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 46.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 38.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 34.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 21.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun LintasTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
    MaterialTheme(
        colorScheme = BukuLintasColors,
        typography = LintasTypography,
        content = content,
    )
}
