package com.pankqx.tonefer.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankqx.tonefer.ui.theme.LocalTone
import com.pankqx.tonefer.ui.theme.ts
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

const val LOADING_MS = 5_000

private val steps = listOf("Loading", "Unlocking encryption keys", "Looking for your laptop", "Connecting", "Ready")

/** Start-up screen: three hopping circles and a progress bar, shown for five seconds. */
@Composable
fun LoadingScreen(animate: Boolean = true) {
    val tone = LocalTone.current
    val progress = remember { Animatable(if (animate) 0f else 0.45f) }
    var step by remember { mutableIntStateOf(if (animate) 0 else 2) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        progress.animateTo(1f, tween(LOADING_MS, easing = CubicBezierEasing(.3f, .1f, .3f, 1f)))
    }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        for (i in steps.indices) { step = i; delay(LOADING_MS.toLong() / steps.size) }
    }
    val t = rememberInfiniteTransition(label = "hop")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "phase")

    Box(Modifier.fillMaxSize().background(tone.desk), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(260.dp)) {
            Row(Modifier.height(48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                listOf(tone.danger, tone.sun, tone.accent).forEachIndexed { i, c ->
                    val p = ((if (animate) phase else 0.2f) - i * 0.125f + 1f) % 1f
                    val lift = if (p < 0.6f) abs(sin(p / 0.6f * Math.PI)).toFloat() else 0f
                    Box(Modifier.offset(y = (-22 * lift).dp).size(22.dp).clip(CircleShape).background(c))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("tonefer", fontWeight = FontWeight.SemiBold, fontSize = ts(20f))
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).background(tone.soft)) {
                Box(Modifier.fillMaxWidth(progress.value).height(4.dp).clip(RoundedCornerShape(4.dp)).background(tone.accent))
            }
            Spacer(Modifier.height(14.dp))
            Text(steps[step].uppercase(), color = tone.muted, fontSize = ts(11.5f), letterSpacing = 1.2.sp)
        }
    }
}
