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
    desk = Color(0xFFE9E6DD), paper = Color(0xFFFBFAF6), ink = Color(0xFF141414),
    muted = Color(0xFF6E6B64), soft = Color(0xFFDCD8CC),
    mine = Color(0xFF141414), mineInk = Color(0xFFFBFAF6), mineMuted = Color(0x99FBFAF6),
    signal = Color(0xFF1A7F37), danger = Color(0xFFB3261E), shadow = Color(0xFF141414), dark = false,
)

private val Dark = Tone(
    desk = Color(0xFF0D0D0C), paper = Color(0xFF171716), ink = Color(0xFFECEBE6),
    muted = Color(0xFF8D8A83), soft = Color(0xFF262624),
    mine = Color(0xFFECEBE6), mineInk = Color(0xFF141414), mineMuted = Color(0x99141414),
    signal = Color(0xFF4CCF6A), danger = Color(0xFFFF6B5E), shadow = Color(0xFF000000), dark = true,
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
