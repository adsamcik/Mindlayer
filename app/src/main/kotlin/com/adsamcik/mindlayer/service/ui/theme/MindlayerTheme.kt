package com.adsamcik.mindlayer.service.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Palette ──────────────────────────────────────────────────────────────────

private object Palette {
    // Indigo/purple primaries
    val Indigo10 = Color(0xFF0C0066)
    val Indigo20 = Color(0xFF1D0099)
    val Indigo40 = Color(0xFF4B3BCB)
    val Indigo80 = Color(0xFFC3BEFF)
    val Indigo90 = Color(0xFFDFDAFF)
    val Indigo95 = Color(0xFFEFEBFF)

    // Cyan secondaries
    val Cyan10 = Color(0xFF001F2A)
    val Cyan40 = Color(0xFF006782)
    val Cyan80 = Color(0xFF68D4F9)
    val Cyan90 = Color(0xFFBCE9FF)

    // Amber tertiaries
    val Amber10 = Color(0xFF241A00)
    val Amber40 = Color(0xFF745B00)
    val Amber80 = Color(0xFFEFBE00)
    val Amber90 = Color(0xFFFFDF9B)

    // Error reds (M3 baseline)
    val Red10 = Color(0xFF410002)
    val Red40 = Color(0xFFBA1A1A)
    val Red80 = Color(0xFFFFB4AB)
    val Red90 = Color(0xFFFFDAD6)

    // Neutral surfaces — slightly warm dark, slightly cool light
    val NeutralDark   = Color(0xFF12111D)
    val NeutralDarkSurf = Color(0xFF1A1928)
    val NeutralLight  = Color(0xFFFBF8FF)
    val NeutralContainer = Color(0xFFE4E1F0)
    val NeutralContainerDark = Color(0xFF2C2B3E)

    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)
}

// ── Branded color schemes ─────────────────────────────────────────────────────

private val BrandedDarkColorScheme: ColorScheme = darkColorScheme(
    primary              = Palette.Indigo80,
    onPrimary            = Palette.Indigo20,
    primaryContainer     = Palette.Indigo40,
    onPrimaryContainer   = Palette.Indigo90,
    secondary            = Palette.Cyan80,
    onSecondary          = Palette.Cyan10,
    secondaryContainer   = Color(0xFF004D63),
    onSecondaryContainer = Palette.Cyan90,
    tertiary             = Palette.Amber80,
    onTertiary           = Palette.Amber10,
    tertiaryContainer    = Color(0xFF574400),
    onTertiaryContainer  = Palette.Amber90,
    error                = Palette.Red80,
    onError              = Palette.Red10,
    errorContainer       = Color(0xFF93000A),
    onErrorContainer     = Palette.Red90,
    background           = Palette.NeutralDark,
    onBackground         = Palette.Indigo95,
    surface              = Palette.NeutralDark,
    onSurface            = Palette.Indigo95,
    surfaceVariant       = Palette.NeutralContainerDark,
    onSurfaceVariant     = Color(0xFFCAC4D0),
    outline              = Color(0xFF938F99),
    outlineVariant       = Palette.NeutralContainerDark,
)

private val BrandedLightColorScheme: ColorScheme = lightColorScheme(
    primary              = Palette.Indigo40,
    onPrimary            = Palette.White,
    primaryContainer     = Palette.Indigo90,
    onPrimaryContainer   = Palette.Indigo10,
    secondary            = Palette.Cyan40,
    onSecondary          = Palette.White,
    secondaryContainer   = Palette.Cyan90,
    onSecondaryContainer = Palette.Cyan10,
    tertiary             = Palette.Amber40,
    onTertiary           = Palette.White,
    tertiaryContainer    = Palette.Amber90,
    onTertiaryContainer  = Palette.Amber10,
    error                = Palette.Red40,
    onError              = Palette.White,
    errorContainer       = Palette.Red90,
    onErrorContainer     = Palette.Red10,
    background           = Palette.NeutralLight,
    onBackground         = Color(0xFF1C1B1F),
    surface              = Palette.NeutralLight,
    onSurface            = Color(0xFF1C1B1F),
    surfaceVariant       = Palette.NeutralContainer,
    onSurfaceVariant     = Color(0xFF49454E),
    outline              = Color(0xFF7A757F),
    outlineVariant       = Palette.NeutralContainer,
)

// ── Typography ────────────────────────────────────────────────────────────────

private val BaselineTypography = Typography()

private val MindlayerTypography = Typography(
    headlineLarge = BaselineTypography.headlineLarge.copy(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    titleLarge = BaselineTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = BaselineTypography.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = BaselineTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaselineTypography.labelLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.15.sp,
    ),
)

private val MindlayerMonoFamily = FontFamily.Monospace

/**
 * Explicit monospace [TextStyle] tokens for data-heavy UI:
 * metric values, token counts, session IDs, timestamps, durations.
 *
 * Usage:  `Text("42 tok/s", style = MindlayerType.Mono.LabelSmall)`
 */
object MindlayerType {
    object Mono {
        val LabelSmall: TextStyle = TextStyle(
            fontFamily = MindlayerMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp,
        )
        val LabelMedium: TextStyle = TextStyle(
            fontFamily = MindlayerMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp,
        )
        val BodySmall: TextStyle = TextStyle(
            fontFamily = MindlayerMonoFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp,
        )
    }
}

// ── Shapes ────────────────────────────────────────────────────────────────────

// Expressive rounding keeps the console approachable while preserving dense data rows.
private val MindlayerShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small      = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium     = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large      = ShapeDefaults.LargeIncreased,
    extraLarge = ShapeDefaults.ExtraLargeIncreased,
)

// ── Semantic color tokens ──────────────────────────────────────────────────────
// Import these in any screen that needs status-aware coloring.

object MindlayerColors {
    /** Green — inference running, service bound, healthy. */
    val StatusOk      = Color(0xFF1DB954)
    /** Amber — warm/throttled, partial degradation. */
    val StatusWarning = Palette.Amber80
    /** Red — error, service crashed, critical thermal. */
    val StatusError   = Palette.Red40
    /** Cyan — informational, idle, ready. */
    val StatusInfo    = Palette.Cyan80

    /** Convenience accessor for the primary brand color from within composition. */
    val primary: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

    // ── Dark-mode-aware status/thermal/pressure palette ──────────────────────
    // Hardcoded amber/green/red values look muted on dark surfaces.
    // These pairs expose (light, dark) variants and a composable accessor.

    object Warning {
        val Light = Color(0xFFB26A00)
        val Dark  = Color(0xFFFFA726)  // vibrant orange-amber for dark bg
        val color: Color @Composable @ReadOnlyComposable get() =
            if (MaterialTheme.colorScheme.background.luminance() < 0.3f) Dark else Light
    }

    object Thermal {
        val CoolLight  = Color(0xFF2E7D32); val CoolDark  = Color(0xFF66BB6A)
        val WarmLight   = Color(0xFFB26A00); val WarmDark   = Color(0xFFFFA726)
        val HotLight    = Color(0xFFE65100); val HotDark    = Color(0xFFFF7043)
        val CritLight   = Color(0xFFC62828); val CritDark   = Color(0xFFEF5350)

        @Composable @ReadOnlyComposable
        fun color(band: String): Color {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
            return when (band.uppercase()) {
                "COOL"     -> if (dark) CoolDark else CoolLight
                "WARM"     -> if (dark) WarmDark else WarmLight
                "HOT"      -> if (dark) HotDark else HotLight
                "CRITICAL" -> if (dark) CritDark else CritLight
                else       -> if (dark) Color(0xFF9E9E9E) else Color(0xFF616161)
            }
        }
    }

    object Pressure {
        val NormalLight   = Color(0xFF2E7D32); val NormalDark   = Color(0xFF66BB6A)
        val WarningLight  = Color(0xFFB26A00); val WarningDark  = Color(0xFFFFA726)
        val CritLight     = Color(0xFFE65100); val CritDark     = Color(0xFFFF7043)
        val EmergLight    = Color(0xFFC62828); val EmergDark    = Color(0xFFEF5350)

        @Composable @ReadOnlyComposable
        fun color(pressure: String): Color {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
            return when (pressure.uppercase()) {
                "NORMAL"    -> if (dark) NormalDark else NormalLight
                "WARNING"   -> if (dark) WarningDark else WarningLight
                "CRITICAL"  -> if (dark) CritDark else CritLight
                "EMERGENCY" -> if (dark) EmergDark else EmergLight
                else        -> if (dark) Color(0xFF9E9E9E) else Color(0xFF616161)
            }
        }
    }
}

// ── Theme composable ──────────────────────────────────────────────────────────

@Composable
fun MindlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else      -> expressiveLightColorScheme()
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography  = MindlayerTypography,
        shapes      = MindlayerShapes,
        content     = content,
    )
}
