package com.pankqx.tonefer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.pankqx.tonefer.R
import com.pankqx.tonefer.data.AppSettings
import com.pankqx.tonefer.data.Backdrop
import com.pankqx.tonefer.data.ThemeMode
import com.pankqx.tonefer.data.Typeface
import com.pankqx.tonefer.data.WindowStyle

/** Tonefer's palette: paper, ink and a single status light. */
data class Tone(
    val desk: Color,
    val paper: Color,
    val ink: Color,
    val muted: Color,
    val soft: Color,
    val mine: Color,
    val mineInk: Color,
    val mineMuted: Color,
    val signal: Color,
    val danger: Color,
    val shadow: Color,
    val line: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val sun: Color,
    val dark: Boolean,
    val classic: Boolean = true,
    val dots: Boolean = true,
    val scale: Float = 1f,
    val family: FontFamily = PlexMono,
)

val PlexMono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
    Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
    Font(R.font.plex_mono_italic, FontWeight.Normal, FontStyle.Italic),
)

private val Light = Tone(
    desk = Color(0xFFF0EFED), paper = Color(0xFFF7F6F4), ink = Color(0xFF262423),
    muted = Color(0xFF77736D), soft = Color(0xFFE7E5E1),
    mine = Color(0xFF279595), mineInk = Color(0xFFF7F6F4), mineMuted = Color(0xB8F7F6F4),
    signal = Color(0xFF279595), danger = Color(0xFFE5533F), shadow = Color(0x33262423),
    line = Color(0x2E262423), accent = Color(0xFF279595), accentInk = Color(0xFFF7F6F4),
    accentSoft = Color(0x1F279595), sun = Color(0xFFFDD751), dark = false,
)

private val Dark = Tone(
    desk = Color(0xFF1B1A19), paper = Color(0xFF232120), ink = Color(0xFFE6E3DE),
    muted = Color(0xFF9B968F), soft = Color(0xFF2C2A28),
    mine = Color(0xFF1F4F4D), mineInk = Color(0xFFE6F1EF), mineMuted = Color(0x9EE6F1EF),
    signal = Color(0xFF3AA9A6), danger = Color(0xFFF2765F), shadow = Color(0x66000000),
    line = Color(0x24E6E3DE), accent = Color(0xFF3AA9A6), accentInk = Color(0xFF102221),
    accentSoft = Color(0x243AA9A6), sun = Color(0xFFE9C95A), dark = true,
)

val LocalTone = staticCompositionLocalOf { Light }

/** Scaled font size that follows Settings → Text size. */
@Composable
fun ts(size: Float): TextUnit = (size * LocalTone.current.scale).sp

@Composable
fun ToneferTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val family = if (settings.typeface == Typeface.MONO) PlexMono else FontFamily.Default
    val tone = (if (dark) Dark else Light).copy(
        classic = settings.windowStyle == WindowStyle.CLASSIC,
        dots = settings.backdrop == Backdrop.DOTS,
        scale = settings.textSize.scale,
        family = family,
    )
    val base = TextStyle(
        fontFamily = family,
        fontSize = (14 * tone.scale).sp,
        lineHeight = (21 * tone.scale).sp,
        color = tone.ink,
    )
    val scheme = if (dark) {
        darkColorScheme(primary = tone.ink, onPrimary = tone.paper, background = tone.desk, onBackground = tone.ink,
            surface = tone.paper, onSurface = tone.ink, surfaceVariant = tone.soft, outline = tone.ink)
    } else {
        lightColorScheme(primary = tone.ink, onPrimary = tone.paper, background = tone.desk, onBackground = tone.ink,
            surface = tone.paper, onSurface = tone.ink, surfaceVariant = tone.soft, outline = tone.ink)
    }
    val typography = Typography(
        bodyLarge = base, bodyMedium = base, bodySmall = base.copy(fontSize = (12 * tone.scale).sp),
        labelLarge = base, labelMedium = base, titleMedium = base.copy(fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(LocalTone provides tone, LocalTextStyle provides base, content = content)
    }
}
