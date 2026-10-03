package pl.kajakapp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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

/*
 * Motyw KajakApp.
 *
 * Kolory: chłodne, jasne tło (woda w słońcu), głęboki błękit jako kolor główny i jeden mocny akcent –
 * pomarańcz kamizelki ratunkowej (tertiary) – zarezerwowany dla nagrywania trasy, żeby najważniejsza
 * czynność była widoczna z daleka, także w słońcu i mokrymi rękami.
 * Liczby (dystans, czas, prędkość) mają cyfry o stałej szerokości, więc nie "pływają" podczas odświeżania.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5C73),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFEAF2),
    onPrimaryContainer = Color(0xFF062F3B),
    secondary = Color(0xFF4A6670),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E6EC),
    onSecondaryContainer = Color(0xFF0F232B),
    tertiary = Color(0xFFCC4408),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBC9),
    onTertiaryContainer = Color(0xFF3B1400),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5F9FA),
    onBackground = Color(0xFF0F1D23),
    surface = Color(0xFFF5F9FA),
    onSurface = Color(0xFF0F1D23),
    surfaceVariant = Color(0xFFDCE8EC),
    onSurfaceVariant = Color(0xFF40525A),
    outline = Color(0xFF70838C),
    outlineVariant = Color(0xFFC2D2D8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F7),
    surfaceContainer = Color(0xFFE9F1F4),
    surfaceContainerHigh = Color(0xFFE3EDF1),
    surfaceContainerHighest = Color(0xFFDDE8ED)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CCBE3),
    onPrimary = Color(0xFF003543),
    primaryContainer = Color(0xFF0F4A5C),
    onPrimaryContainer = Color(0xFFCFEAF2),
    secondary = Color(0xFFB3CBD3),
    onSecondary = Color(0xFF1C333A),
    secondaryContainer = Color(0xFF2F454C),
    onSecondaryContainer = Color(0xFFD3E6EC),
    tertiary = Color(0xFFFF9B66),
    onTertiary = Color(0xFF4A1B00),
    tertiaryContainer = Color(0xFF8F3200),
    onTertiaryContainer = Color(0xFFFFDBC9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0C171C),
    onBackground = Color(0xFFE1ECF0),
    surface = Color(0xFF0C171C),
    onSurface = Color(0xFFE1ECF0),
    surfaceVariant = Color(0xFF2B3D44),
    onSurfaceVariant = Color(0xFFB7CAD1),
    outline = Color(0xFF8A9EA6),
    outlineVariant = Color(0xFF3A4D55),
    surfaceContainerLowest = Color(0xFF081115),
    surfaceContainerLow = Color(0xFF111E24),
    surfaceContainer = Color(0xFF152329),
    surfaceContainerHigh = Color(0xFF1B2B32),
    surfaceContainerHighest = Color(0xFF22343B)
)

private const val TABULAR = "tnum"

private val Sans = FontFamily.Default

private val KajakTypography = Typography(
    // Duże liczby (czas trasy, dystans).
    displayLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 60.sp,
        letterSpacing = (-1).sp, fontFeatureSettings = TABULAR
    ),
    displayMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp,
        letterSpacing = (-0.5).sp, fontFeatureSettings = TABULAR
    ),
    displaySmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp,
        letterSpacing = (-0.5).sp, fontFeatureSettings = TABULAR
    ),
    headlineLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.25).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.25).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp,
        fontFeatureSettings = TABULAR
    ),
    titleLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp,
        fontFeatureSettings = TABULAR
    ),
    titleSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.1.sp),
    labelLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp
    )
)

private val KajakShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun KajakTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = KajakTypography,
        shapes = KajakShapes,
        content = content
    )
}
