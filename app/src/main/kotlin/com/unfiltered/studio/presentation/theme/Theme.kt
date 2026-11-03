package com.unfiltered.studio.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unfiltered.studio.domain.models.ThemeMode

object Palette {
    val Charcoal = Color(0xFF0F0F13)
    val Black = Color(0xFF000000)
    val SurfaceLowest = Color(0xFF0B0B0E)
    val SurfaceLow = Color(0xFF141419)
    val SurfaceContainer = Color(0xFF1A1A21)
    val SurfaceHigh = Color(0xFF22222B)
    val SurfaceHighest = Color(0xFF2B2B36)
    val Violet = Color(0xFFA78BFA)
    val VioletStrong = Color(0xFF8B5CF6)
    val VioletContainer = Color(0xFF33285C)
    val Blue = Color(0xFF60A5FA)
    val BlueContainer = Color(0xFF1B3254)
    val Periwinkle = Color(0xFF9DB2FF)
    val OnSurface = Color(0xFFECECF3)
    val OnSurfaceVariant = Color(0xFFA4A4B4)
    val Outline = Color(0xFF3B3B48)
    val OutlineVariant = Color(0xFF2A2A34)
    val Error = Color(0xFFF87171)
    val ErrorContainer = Color(0xFF4A1C1F)
    val Success = Color(0xFF4ADE80)
    val Warning = Color(0xFFFBBF24)
}

private val DarkScheme = darkColorScheme(
    primary = Palette.Violet,
    onPrimary = Color(0xFF1D1047),
    primaryContainer = Palette.VioletContainer,
    onPrimaryContainer = Color(0xFFE7DFFF),
    secondary = Palette.Blue,
    onSecondary = Color(0xFF0A2347),
    secondaryContainer = Palette.BlueContainer,
    onSecondaryContainer = Color(0xFFD6E6FF),
    tertiary = Palette.Periwinkle,
    onTertiary = Color(0xFF14204D),
    tertiaryContainer = Color(0xFF26305C),
    onTertiaryContainer = Color(0xFFDDE3FF),
    background = Palette.Charcoal,
    onBackground = Palette.OnSurface,
    surface = Palette.Charcoal,
    onSurface = Palette.OnSurface,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.OnSurfaceVariant,
    surfaceContainerLowest = Palette.SurfaceLowest,
    surfaceContainerLow = Palette.SurfaceLow,
    surfaceContainer = Palette.SurfaceContainer,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHighest,
    outline = Palette.Outline,
    outlineVariant = Palette.OutlineVariant,
    error = Palette.Error,
    onError = Color(0xFF450A0A),
    errorContainer = Palette.ErrorContainer,
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE4E1EC),
    inverseOnSurface = Color(0xFF1B1B21),
    inversePrimary = Palette.VioletStrong,
    scrim = Color.Black,
)

private val AmoledScheme = DarkScheme.copy(
    background = Palette.Black,
    surface = Palette.Black,
    surfaceContainerLowest = Palette.Black,
    surfaceContainerLow = Color(0xFF0C0C0F),
    surfaceContainer = Color(0xFF121216),
    surfaceContainerHigh = Color(0xFF1A1A20),
    surfaceContainerHighest = Color(0xFF22222A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6D4FD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF2E63B8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3FF),
    onSecondaryContainer = Color(0xFF001B3F),
    tertiary = Color(0xFF4F5BA8),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F1FA),
    surfaceContainer = Color(0xFFEFEBF5),
    surfaceContainerHigh = Color(0xFFE9E5EF),
    surfaceContainerHighest = Color(0xFFE3DFEA),
)

/** Semantic colours Material doesn't define. */
@Immutable
data class ExtendedColors(val success: Color, val warning: Color, val brandGradient: Brush)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(Palette.Success, Palette.Warning, Brush.linearGradient(listOf(Palette.Violet, Palette.Blue)))
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    )
}

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp)

@Composable
fun UnfilteredTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context).let { if (mode == ThemeMode.AMOLED) it.copy(background = Color.Black, surface = Color.Black) else it }
            else dynamicLightColorScheme(context)
        mode == ThemeMode.AMOLED -> AmoledScheme
        dark -> DarkScheme
        else -> LightScheme
    }
    val extended = ExtendedColors(
        success = if (dark) Palette.Success else Color(0xFF15803D),
        warning = if (dark) Palette.Warning else Color(0xFFB45309),
        brandGradient = Brush.linearGradient(listOf(scheme.primary, scheme.secondary)),
    )
    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
