package com.readaloud.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Paper and ink: a warm off-white page, deep teal ink and an amber highlighter
private val Teal = Color(0xFF1F4E54)
private val TealSoft = Color(0xFFCFE6E8)
private val Amber = Color(0xFFB5651D)
private val AmberSoft = Color(0xFFFBE3C0)
private val Paper = Color(0xFFFBF7F0)
private val PaperRaised = Color(0xFFF3ECDF)
private val Ink = Color(0xFF1B1F1E)

private val LightColors = lightColorScheme(
    primary = Teal, onPrimary = Color.White,
    primaryContainer = TealSoft, onPrimaryContainer = Color(0xFF062A2E),
    secondary = Amber, onSecondary = Color.White,
    secondaryContainer = AmberSoft, onSecondaryContainer = Color(0xFF3B1D00),
    tertiary = Color(0xFF6B5B95), onTertiary = Color.White,
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    surfaceVariant = PaperRaised, onSurfaceVariant = Color(0xFF4B524F),
    surfaceContainer = PaperRaised, surfaceContainerHigh = Color(0xFFEDE4D3), surfaceContainerLow = Color(0xFFF7F1E6),
    outline = Color(0xFF8A918D), outlineVariant = Color(0xFFD9D0BF),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FC9CF), onPrimary = Color(0xFF003338),
    primaryContainer = Color(0xFF254B50), onPrimaryContainer = TealSoft,
    secondary = Color(0xFFF2B84B), onSecondary = Color(0xFF3B2600),
    secondaryContainer = Color(0xFF5A3A12), onSecondaryContainer = AmberSoft,
    tertiary = Color(0xFFCBBEFF), onTertiary = Color(0xFF332A5C),
    background = Ink, onBackground = Color(0xFFE8E4DB),
    surface = Ink, onSurface = Color(0xFFE8E4DB),
    surfaceVariant = Color(0xFF262B2A), onSurfaceVariant = Color(0xFFB9C0BC),
    surfaceContainer = Color(0xFF232827), surfaceContainerHigh = Color(0xFF2C3231), surfaceContainerLow = Color(0xFF1F2322),
    outline = Color(0xFF7C8481), outlineVariant = Color(0xFF3B4240),
    error = Color(0xFFF2B8B5),
)

private val Serif = FontFamily.Serif

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** The reading text is serif and generously spaced; it is the part of the app people look at longest. */
val ReadingStyle = TextStyle(fontFamily = Serif, fontSize = 19.sp, lineHeight = 31.sp)

@Composable
fun ReadAloudTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
}
