package me.vwave.vpn.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.vwave.vpn.R

// Shared with the desktop client (archived desktop theme): monochrome with one electric-blue
// accent, light and dark following the system. Keep the two in step.

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)
val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

@Immutable
data class Tokens(
    val bg: Color,
    val sidebar: Color,
    val surface: Color,
    val hover: Color,
    val pressed: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accentHover: Color,
    val onAccent: Color,
    /** Accent used as a text or icon color on the page background. */
    val accentFg: Color,
    val accentSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val warning: Color,
    /** Server quality scale (load bars, ping): good / ok / bad. */
    val good: Color,
    val ok: Color,
    val bad: Color,
)

val DarkTokens = Tokens(
    bg = Color(0xFF0A0A0A),
    sidebar = Color(0xFF111111),
    surface = Color(0xFF141414),
    hover = Color(0xFF1C1C1C),
    pressed = Color(0xFF242424),
    border = Color(0xFF262626),
    borderStrong = Color(0xFF3A3A3A),
    text = Color(0xFFEDEDED),
    muted = Color(0xFFA1A1A1),
    faint = Color(0xFF6B6B6B),
    accent = Color(0xFF2F6BFF),
    accentHover = Color(0xFF4A7FFF),
    onAccent = Color.White,
    accentFg = Color(0xFF6E9BFF),
    accentSoft = Color(0x292F6BFF),
    danger = Color(0xFFFF5C5C),
    dangerSoft = Color(0x1AFF5C5C),
    warning = Color(0xFFF5A524),
    good = Color(0xFF3FCF8E),
    ok = Color(0xFFF5C518),
    bad = Color(0xFFFF5C5C),
)

val LightTokens = Tokens(
    bg = Color.White,
    sidebar = Color(0xFFF7F7F7),
    surface = Color(0xFFFAFAFA),
    hover = Color(0xFFF0F0F0),
    pressed = Color(0xFFE8E8E8),
    border = Color(0xFFE6E6E6),
    borderStrong = Color(0xFFD0D0D0),
    text = Color(0xFF0A0A0A),
    muted = Color(0xFF5F5F5F),
    faint = Color(0xFFA3A3A3),
    accent = Color(0xFF2F6BFF),
    accentHover = Color(0xFF1F57E6),
    onAccent = Color.White,
    accentFg = Color(0xFF1F57E6),
    accentSoft = Color(0x1A2F6BFF),
    danger = Color(0xFFD92D20),
    dangerSoft = Color(0x14D92D20),
    warning = Color(0xFFB45309),
    good = Color(0xFF16A34A),
    ok = Color(0xFFCA8A04),
    bad = Color(0xFFDC2626),
)

val LocalTokens = staticCompositionLocalOf { DarkTokens }

/** The active color tokens. */
val tokens: Tokens @Composable get() = LocalTokens.current

/** Spacing and corner radii, the desktop's scale in dp. */
object Dimens {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val radiusSm = 6.dp
    val radiusMd = 8.dp
    val radiusLg = 12.dp
}

/**
 * The desktop's type scale (12 / 13 / 14 / 16 / 20 / 34), one step larger for body copy, since a
 * phone is read at arm's length and tapped rather than clicked.
 */
private fun style(size: Int, weight: FontWeight = FontWeight.Normal, family: FontFamily = Geist) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * 1.4f).sp,
    letterSpacing = 0.sp,
)

private val WaveTypography = Typography(
    displaySmall = style(34, FontWeight.SemiBold).copy(lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineSmall = style(20, FontWeight.SemiBold),
    titleLarge = style(20, FontWeight.SemiBold),
    titleMedium = style(16, FontWeight.Medium),
    titleSmall = style(14, FontWeight.SemiBold),
    bodyLarge = style(16),
    bodyMedium = style(14),
    bodySmall = style(13),
    labelLarge = style(15, FontWeight.Medium),
    labelMedium = style(12, FontWeight.Medium),
    labelSmall = style(12),
)

/** Numbers that are measured or counted: speeds, totals, ping, load, log lines. */
val MonoSmall = style(13, family = GeistMono)
val MonoLarge = style(18, family = GeistMono)

private fun schemeFor(t: Tokens, dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = t.accent,
    onPrimary = t.onAccent,
    primaryContainer = t.accentSoft,
    onPrimaryContainer = t.accentFg,
    secondary = t.text,
    onSecondary = t.bg,
    secondaryContainer = t.pressed,
    onSecondaryContainer = t.text,
    background = t.bg,
    onBackground = t.text,
    surface = t.bg,
    onSurface = t.text,
    surfaceVariant = t.hover,
    onSurfaceVariant = t.muted,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = t.bg,
    surfaceContainerLow = t.surface,
    surfaceContainer = t.sidebar,
    surfaceContainerHigh = t.surface,
    surfaceContainerHighest = t.hover,
    outline = t.borderStrong,
    outlineVariant = t.border,
    error = t.danger,
    onError = Color.White,
    errorContainer = t.dangerSoft,
    onErrorContainer = t.danger,
    inverseSurface = t.text,
    inverseOnSurface = t.bg,
    inversePrimary = t.accentFg,
)

@Composable
fun WaveTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val t = if (dark) DarkTokens else LightTokens
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(
            colorScheme = schemeFor(t, dark),
            typography = WaveTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(Dimens.radiusSm),
                small = RoundedCornerShape(Dimens.radiusMd),
                medium = RoundedCornerShape(Dimens.radiusLg),
                large = RoundedCornerShape(Dimens.radiusLg),
                extraLarge = RoundedCornerShape(16.dp),
            ),
            content = content,
        )
    }
}
