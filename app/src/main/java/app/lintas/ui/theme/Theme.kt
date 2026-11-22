package app.lintas.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val SignalOrange = Color(0xFFFF6A3D)
val SignalOrangeDark = Color(0xFFB83F18)
val SeaInk = Color(0xFF0B171B)
val SeaPanel = Color(0xFF102127)
val SeaPanelHigh = Color(0xFF142A31)
val SeaText = Color(0xFFF2EFE4)
val SeaMuted = Color(0xFF8FA5A4)
val SeaLine = Color(0xFF2E4A4F)
val SeaGreen = Color(0xFF8BD6B2)

val MistBackground = Color(0xFFF1F4EE)
val MistSurface = Color(0xFFF8FAF4)
val MistSurfaceHigh = Color(0xFFE4EBE1)
val MistText = Color(0xFF12292B)
val MistMuted = Color(0xFF5B726F)
val MistLine = Color(0xFFC6D2C3)
val MistGreen = Color(0xFF1F6D54)

private val DarkColors = darkColorScheme(
    primary = SignalOrange,
    onPrimary = SeaInk,
    primaryContainer = Color(0xFF482317),
    onPrimaryContainer = Color(0xFFFFD9CB),
    secondary = SeaGreen,
    onSecondary = SeaInk,
    background = SeaInk,
    onBackground = SeaText,
    surface = SeaPanel,
    onSurface = SeaText,
    surfaceVariant = SeaPanelHigh,
    onSurfaceVariant = SeaMuted,
    outline = SeaLine,
    outlineVariant = Color(0xFF1F373C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = SignalOrangeDark,
    onPrimary = Color(0xFFFFF8F2),
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3B0A00),
    secondary = MistGreen,
    onSecondary = Color(0xFFFFFFFF),
    background = MistBackground,
    onBackground = MistText,
    surface = MistSurface,
    onSurface = MistText,
    surfaceVariant = MistSurfaceHigh,
    onSurfaceVariant = MistMuted,
    outline = MistLine,
    outlineVariant = Color(0xFFDCE5D9),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val LintasTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 52.sp,
        lineHeight = 54.sp,
        letterSpacing = (-1).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 42.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
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
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    ),
)

@Composable
fun LintasTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
    }
    MaterialTheme(
        colorScheme = colors,
        typography = LintasTypography,
        content = content,
    )
}
