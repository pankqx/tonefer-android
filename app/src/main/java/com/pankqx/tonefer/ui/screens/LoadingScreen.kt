package com.pankqx.tonefer.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankqx.tonefer.ui.theme.LocalTone
import com.pankqx.tonefer.ui.theme.Tone
import com.pankqx.tonefer.ui.theme.ts
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** How long the start-up screen stays up, in milliseconds. */
const val LOADING_MS = 5_000

/** The hyperjump at the end: content lunges forward and the streaks go to full speed. */
private const val JUMP_MS = 450f

private val steps = listOf("Starting", "Unlocking encryption keys", "Looking for your laptop", "Tuning the link", "Ready")

private val zipEase = CubicBezierEasing(.55f, 0f, .25f, 1f)
private val barEase = CubicBezierEasing(.3f, .1f, .3f, 1f)
private val jumpEase = CubicBezierEasing(.7f, 0f, .2f, 1f)

/** One line of the warp field. Positions are fractions of the screen. */
private class Streak(val x: Float, val y: Float, val len: Float, val thick: Float, val v: Float, val alpha: Float, val kind: Int)

/**
 * Start-up screen. Packets race from the phone to the laptop over a warp field that keeps
 * accelerating, while the meter counts to 100 %. Everything is a pure function of the time
 * since the first frame, so [animate] = false renders a fixed frame for screenshots.
 */
@Composable
fun LoadingScreen(modifier: Modifier = Modifier, animate: Boolean = true, frozenAtMs: Float = 2_600f) {
    val tone = LocalTone.current
    var t by remember { mutableFloatStateOf(if (animate) 0f else frozenAtMs) }
    if (animate) LaunchedEffect(Unit) {
        var start = -1L
        while (true) withFrameNanos { now ->
            if (start < 0) start = now
            t = (now - start) / 1_000_000f
        }
    }
    val streaks = remember {
        val r = Random(7)
        List(72) {
            val roll = r.nextFloat()
            Streak(
                x = r.nextFloat(), y = r.nextFloat(),
                len = 0.07f + r.nextFloat() * 0.2f,
                thick = if (r.nextFloat() < 0.18f) 2f else 1f,
                v = 0.55f + r.nextFloat() * 0.9f,
                alpha = 0.3f + r.nextFloat() * 0.5f,
                kind = when { roll < 0.07f -> 1; roll < 0.13f -> 2; roll < 0.17f -> 3; else -> 0 },
            )
        }
    }
    val pct by remember { derivedStateOf { (barEase.transform((t / (LOADING_MS - 300f)).coerceIn(0f, 1f)) * 100).roundToInt() } }
    val step by remember { derivedStateOf { min(steps.lastIndex, (t / (LOADING_MS / steps.size)).toInt()) } }

    Box(
        modifier.fillMaxSize().background(tone.desk).clipToBounds()
            .semantics { contentDescription = "Tonefer is starting" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { warp(tone, streaks, t) }

        BoxWithConstraints(Modifier.widthIn(max = 420.dp).fillMaxWidth(0.86f)) {
            val track = min(230f, maxWidth.value - 26 - 62 - 28).coerceAtLeast(110f).dp
            Column(
                Modifier.fillMaxWidth().graphicsLayer {
                    val j = jump(t)
                    translationX = 28.dp.toPx() * j
                    scaleX = 1f + 0.04f * j
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Lane(tone, track) { t }
                Spacer(Modifier.height(20.dp))
                Text("tonefer", fontWeight = FontWeight.SemiBold, fontSize = ts(26f), color = tone.ink)
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Canvas(Modifier.weight(1f).height(6.dp)) { meter(tone, pct / 100f, t) }
                    Text(
                        "$pct%", color = tone.accent, fontWeight = FontWeight.SemiBold, fontSize = ts(13f),
                        textAlign = TextAlign.End, modifier = Modifier.width(52.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(steps[step].uppercase(), color = tone.muted, fontSize = ts(11.5f), letterSpacing = 1.6.sp)
            }
        }

        Text(
            buildAnnotatedString {
                append("DEVELOPED & OWNED BY ")
                withStyle(SpanStyle(color = tone.ink, fontWeight = FontWeight.SemiBold)) { append("K S PANKAJ") }
            },
            color = tone.muted, fontSize = ts(11f), letterSpacing = 1.6.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp, start = 16.dp, end = 16.dp),
        )
    }
}

/** 0 → 1 over the last [JUMP_MS] of the loader. */
private fun jump(t: Float): Float = jumpEase.transform(((t - (LOADING_MS - JUMP_MS)) / JUMP_MS).coerceIn(0f, 1f))

/** Screen-widths travelled by the warp field after [t] ms: slow start, cubic build-up, then a boost. */
private fun distance(t: Float): Float {
    val s = t / 1000f
    val T = LOADING_MS / 1000f
    val base = 0.12f * s + 2.3f * s * s * s / (3f * T * T)
    val j0 = T - JUMP_MS / 1000f
    val boost = if (s <= j0) 0f else {
        val d = min(s - j0, JUMP_MS / 1000f)
        val ramp = 7f * d * d / (2f * JUMP_MS / 1000f)
        ramp + 7f * (s - j0 - d)
    }
    return base + boost
}

/** Current speed, 0 (crawl) → 1 (full warp). */
private fun speed(t: Float): Float {
    val s = (t / LOADING_MS).coerceIn(0f, 1f)
    return (0.08f + 0.6f * s * s + 0.4f * jump(t)).coerceAtMost(1f)
}

private fun streakColor(tone: Tone, kind: Int): Color = when (kind) {
    1 -> tone.accent
    2 -> tone.sun
    3 -> tone.danger
    else -> tone.muted
}

private fun DrawScope.warp(tone: Tone, streaks: List<Streak>, t: Float) {
    val w = size.width
    val h = size.height
    val d = distance(t)
    val sp = speed(t)
    for (s in streaks) {
        val stretch = s.len * w * (0.35f + 1.3f * sp)
        val pos = s.x - d * s.v
        val frac = pos - floor(pos)
        val head = frac * (w + stretch) - stretch
        val y = s.y * h
        val c = streakColor(tone, s.kind)
        val a = (s.alpha * (0.45f + 0.75f * sp)).coerceAtMost(0.9f)
        drawRect(
            brush = Brush.horizontalGradient(listOf(c.copy(alpha = a), c.copy(alpha = 0f)), startX = head, endX = head + stretch),
            topLeft = Offset(head, y), size = Size(stretch, s.thick * density),
        )
    }
    // Keep the centre and the credit line clear.
    val rx = 190.dp.toPx()
    val ry = 110.dp.toPx()
    scale(scaleX = rx / ry, scaleY = 1f, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(0f to tone.desk, 0.6f to tone.desk, 1f to tone.desk.copy(alpha = 0f), center = center, radius = ry * 1.6f),
            radius = ry * 1.6f, center = center,
        )
    }
    val bottom = 120.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(0f to tone.desk.copy(alpha = 0f), 0.55f to tone.desk, 1f to tone.desk, startY = h - bottom, endY = h),
        topLeft = Offset(0f, h - bottom), size = Size(w, bottom),
    )
}

/** Phone → packets → laptop. */
@Composable
private fun Lane(tone: Tone, track: Dp, time: () -> Float) {
    val total = 26.dp + 14.dp + track + 14.dp + 62.dp
    Canvas(Modifier.size(total, 50.dp)) {
        val t = time()
        val stroke = 2.dp.toPx()
        val midY = size.height / 2f

        // Phone
        val pw = 26.dp.toPx(); val ph = 44.dp.toPx()
        drawRect(tone.paper, Offset(0f, midY - ph / 2), Size(pw, ph))
        drawRect(tone.ink, Offset(stroke / 2, midY - ph / 2 + stroke / 2), Size(pw - stroke, ph - stroke), style = Stroke(stroke))
        drawRect(tone.ink, Offset(pw / 2 - 4.dp.toPx(), midY + ph / 2 - 8.dp.toPx()), Size(8.dp.toPx(), 2.dp.toPx()))

        // Track
        val tx = pw + 14.dp.toPx()
        val tw = track.toPx()
        drawLine(
            tone.muted.copy(alpha = 0.7f), Offset(tx, midY), Offset(tx + tw, midY), strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()), -(t / 8f)),
        )
        var catching = false
        clipRect(tx, 0f, tx + tw, size.height) {
            val colors = listOf(tone.accent, tone.sun, tone.ink, tone.danger, tone.accent, tone.sun)
            val lift = listOf(0f, -6f, 5f, -3f, 7f, -7f)
            for (i in 0 until 6) {
                val period = 620f + i * 37f
                val p = ((t / period + i * 0.17f) % 1f + 1f) % 1f
                if (p > 0.86f && p < 0.99f) catching = true
                val x = tx - 20.dp.toPx() + zipEase.transform(p) * (tw + 30.dp.toPx())
                val a = when { p < 0.12f -> p / 0.12f; p > 0.88f -> (1f - p) / 0.12f; else -> 1f }
                val sz = (if (i % 3 == 2) 8 else 10).dp.toPx()
                val y = midY + lift[i].dp.toPx() - sz / 2
                val trail = 56.dp.toPx()
                drawRect(
                    Brush.horizontalGradient(listOf(colors[i].copy(alpha = 0f), colors[i].copy(alpha = 0.55f * a)), startX = x - trail, endX = x),
                    Offset(x - trail, y + sz / 2 - 1.5.dp.toPx()), Size(trail, 3.dp.toPx()),
                )
                drawRect(colors[i].copy(alpha = a), Offset(x, y), Size(sz, sz))
            }
        }

        // Laptop, lights up as packets land
        val lx = tx + tw + 14.dp.toPx()
        val lw = 54.dp.toPx(); val lh = 36.dp.toPx()
        val top = midY - lh / 2 - 3.dp.toPx()
        drawRect(if (catching) tone.accent else tone.paper, Offset(lx, top), Size(lw, lh))
        drawRect(tone.ink, Offset(lx + stroke / 2, top + stroke / 2), Size(lw - stroke, lh - stroke), style = Stroke(stroke))
        if (catching) drawRect(tone.paper, Offset(lx + 6.dp.toPx(), top + 6.dp.toPx()), Size(lw - 12.dp.toPx(), lh - 12.dp.toPx()), style = Stroke(1.dp.toPx()))
        drawRect(tone.ink, Offset(lx - 4.dp.toPx(), top + lh + 2.dp.toPx()), Size(lw + 8.dp.toPx(), 4.dp.toPx()))
    }
}

/** Progress bar with stripes that keep moving. */
private fun DrawScope.meter(tone: Tone, f: Float, t: Float) {
    drawRect(tone.soft)
    val fw = size.width * f
    if (fw <= 0f) return
    drawRect(tone.accent, size = Size(fw, size.height))
    clipRect(0f, 0f, fw, size.height) {
        val gap = 12.dp.toPx()
        val shift = (t / 18f) % gap
        var x = -gap + shift
        while (x < fw + gap) {
            drawLine(tone.paper.copy(alpha = 0.35f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 3.dp.toPx())
            x += gap
        }
    }
}
