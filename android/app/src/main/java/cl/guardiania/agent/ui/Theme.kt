package cl.guardiania.agent.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Pine950 = Color(0xFF081B17)
val Pine900 = Color(0xFF0B241E)
val Pine800 = Color(0xFF123029)
val Pine700 = Color(0xFF1B4A3E)
val Pine600 = Color(0xFF236052)
val Sage500 = Color(0xFF5E8F7B)
val Sage300 = Color(0xFFA9C6B8)
val SageSoft = Color(0xFFE5EEE8)
val Paper = Color(0xFFEEF2ED)
val Card = Color(0xFFFBFCFA)
val Card2 = Color(0xFFF5F8F4)
val Ink = Color(0xFF10201B)
val Ink2 = Color(0xFF344A42)
val Muted = Color(0xFF61756C)
val Line = Color(0xFFD7E0D9)
val LineStrong = Color(0xFFC3D1C8)
val Oxblood = Color(0xFFA33128)
val OxbloodInk = Color(0xFF8A251D)
val OxbloodSoft = Color(0xFFF7E7E3)
val Amber = Color(0xFF8D620F)
val AmberSoft = Color(0xFFF7EEDB)

val Mono = FontFamily.Monospace

private val colors = lightColorScheme(
    primary = Pine700,
    onPrimary = Color.White,
    primaryContainer = SageSoft,
    onPrimaryContainer = Pine900,
    secondary = Sage500,
    onSecondary = Color.White,
    secondaryContainer = SageSoft,
    onSecondaryContainer = Pine900,
    tertiary = Amber,
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = Card2,
    onSurfaceVariant = Muted,
    outline = LineStrong,
    outlineVariant = Line,
    error = Oxblood,
    onError = Color.White,
    errorContainer = OxbloodSoft,
    onErrorContainer = OxbloodInk,
)

private val typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.7).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
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
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun GuardianTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}
