package com.pankqx.tonefer.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankqx.tonefer.R
import com.pankqx.tonefer.ui.theme.LocalTone
import com.pankqx.tonefer.ui.theme.ts

val Rule = 1.5.dp

/** Hard offset shadow, the classic-desktop look. Disabled in the "Flat" window style. */
@Composable
fun Modifier.hardShadow(offset: Dp = 3.dp): Modifier {
    val tone = LocalTone.current
    if (!tone.classic) return this
    return this.drawBehind {
        val o = offset.toPx()
        drawRect(tone.shadow, topLeft = Offset(o, o), size = size)
    }
}

/** Dotted "desktop" background. */
@Composable
fun Modifier.backdrop(): Modifier {
    val tone = LocalTone.current
    return this
        .background(tone.desk)
        .then(if (!tone.dots) Modifier else Modifier.drawBehind {
            val step = 14.dp.toPx()
            val r = 0.9.dp.toPx()
            val c = tone.ink.copy(alpha = 0.14f)
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) { drawCircle(c, r, Offset(x, y)); x += step }
                y += step
            }
        })
}

@Composable
fun Modifier.ruled(): Modifier = this.border(Rule, LocalTone.current.ink)

/** Pinstriped title bar with a close box, like early desktop windows. */
@Composable
fun TitleBar(title: String, onClose: (() -> Unit)? = null) {
    val tone = LocalTone.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(tone.paper)
            .drawBehind {
                val inset = 8.dp.toPx()
                val gap = 4.dp.toPx()
                var y = 6.dp.toPx()
                repeat(4) {
                    drawLine(tone.ink, Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx())
                    y += gap
                }
                drawLine(tone.ink, Offset(0f, size.height - Rule.toPx() / 2), Offset(size.width, size.height - Rule.toPx() / 2), Rule.toPx())
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
                .size(14.dp)
                .background(tone.paper)
                .border(Rule, tone.ink)
                .then(if (onClose != null) Modifier.clickable(role = Role.Button, onClick = onClose) else Modifier)
        )
        Text(
            title,
            modifier = Modifier.background(tone.paper).padding(horizontal = 10.dp),
            fontSize = ts(12.5f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun RetroWindow(
    title: String,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tone = LocalTone.current
    Column(modifier.hardShadow().background(tone.paper).ruled()) {
        TitleBar(title, onClose)
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun RetroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    val tone = LocalTone.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg = if (primary) tone.ink else tone.paper
    val fg = when { primary -> tone.paper; danger -> tone.danger; else -> tone.ink }
    Row(
        modifier
            .offset(if (pressed && tone.classic) 2.dp else 0.dp, if (pressed && tone.classic) 2.dp else 0.dp)
            .then(if (pressed || !enabled) Modifier else Modifier.hardShadow(2.dp))
            .background(bg)
            .ruled()
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = fg.copy(alpha = if (enabled) 1f else .4f), modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg.copy(alpha = if (enabled) 1f else .4f), fontWeight = FontWeight.Medium, fontSize = ts(13.5f), maxLines = 1)
    }
}

@Composable
fun IconSquare(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 44.dp,
) {
    val tone = LocalTone.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .size(size)
            .offset(if (pressed && tone.classic) 2.dp else 0.dp, if (pressed && tone.classic) 2.dp else 0.dp)
            .then(if (pressed || !enabled) Modifier else Modifier.hardShadow(2.dp))
            .background(if (primary && enabled) tone.ink else tone.paper)
            .ruled()
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), description,
            tint = (if (primary && enabled) tone.paper else tone.ink).copy(alpha = if (enabled) 1f else .35f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Small borderless icon used inside slips. */
@Composable
fun MiniIcon(@DrawableRes icon: Int, description: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), description, tint = color, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun StatusLight(on: Boolean, label: String, modifier: Modifier = Modifier) {
    val tone = LocalTone.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(if (on) tone.signal else Color.Transparent).border(Rule, if (on) tone.ink else tone.muted))
        Spacer(Modifier.width(8.dp))
        Text(label.uppercase(), fontSize = ts(11f), letterSpacing = 0.9.sp, fontWeight = FontWeight.Medium,
            color = if (on) tone.ink else tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = LocalTone.current.muted) {
    Text(text.uppercase(), modifier, fontSize = ts(10.5f), letterSpacing = 1.sp, fontWeight = FontWeight.Medium, color = color)
}

@Composable
fun DashedDivider(modifier: Modifier = Modifier, color: Color = LocalTone.current.ink.copy(alpha = .35f)) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    }
}

/** Retro checkbox row used throughout Settings. */
@Composable
fun CheckRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tone = LocalTone.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox) { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(17.dp).background(if (checked) tone.ink else tone.paper).border(Rule, tone.ink),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Canvas(Modifier.size(11.dp)) {
                val w = 2.dp.toPx()
                drawLine(tone.paper, Offset(size.width * .08f, size.height * .55f), Offset(size.width * .4f, size.height * .85f), w, StrokeCap.Square)
                drawLine(tone.paper, Offset(size.width * .4f, size.height * .85f), Offset(size.width * .95f, size.height * .15f), w, StrokeCap.Square)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = tone.muted, fontSize = ts(12f), lineHeight = ts(17f))
        }
    }
}

/** Segmented choice: one bordered strip, the selected option inverted. */
@Composable
fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, subtitle: String? = null) {
    val tone = LocalTone.current
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        if (subtitle != null) Text(subtitle, color = tone.muted, fontSize = ts(12f), lineHeight = ts(17f))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().ruled()) {
            options.forEachIndexed { i, (value, label) ->
                val on = value == selected
                if (i > 0) Box(Modifier.width(Rule).height(38.dp).background(tone.ink))
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .background(if (on) tone.ink else tone.paper)
                        .clickable(role = Role.RadioButton) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (on) tone.paper else tone.ink, fontSize = ts(12.5f),
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

/** Phone ⇄ laptop pictogram. [linked] = solid arrows; otherwise dashed. */
@Composable
fun DevicesMark(modifier: Modifier = Modifier, linked: Boolean = true, color: Color = LocalTone.current.ink) {
    val paper = LocalTone.current.paper
    Canvas(modifier.size(64.dp, 32.dp)) {
        val u = size.width / 64f
        val stroke = Stroke(2f * u)
        // phone
        drawRect(paper, Offset(2 * u, 5 * u), Size(13 * u, 23 * u))
        drawRect(color, Offset(2 * u, 5 * u), Size(13 * u, 23 * u), style = stroke)
        drawLine(color, Offset(6.5f * u, 24 * u), Offset(10.5f * u, 24 * u), 2 * u)
        // laptop
        drawRect(paper, Offset(37 * u, 4 * u), Size(22 * u, 16 * u))
        drawRect(color, Offset(37 * u, 4 * u), Size(22 * u, 16 * u), style = stroke)
        drawLine(color, Offset(33 * u, 25 * u), Offset(63 * u, 25 * u), 2 * u)
        // arrows
        val fx = if (linked) null else PathEffect.dashPathEffect(floatArrayOf(2 * u, 3 * u))
        val a = if (linked) color else color.copy(alpha = .45f)
        drawLine(a, Offset(19 * u, 11 * u), Offset(32 * u, 11 * u), 2 * u, pathEffect = fx)
        drawLine(a, Offset(29 * u, 8 * u), Offset(32 * u, 11 * u), 2 * u)
        drawLine(a, Offset(29 * u, 14 * u), Offset(32 * u, 11 * u), 2 * u)
        drawLine(a, Offset(32 * u, 19 * u), Offset(19 * u, 19 * u), 2 * u, pathEffect = fx)
        drawLine(a, Offset(22 * u, 16 * u), Offset(19 * u, 19 * u), 2 * u)
        drawLine(a, Offset(22 * u, 22 * u), Offset(19 * u, 19 * u), 2 * u)
    }
}

@Composable
fun Brand(modifier: Modifier = Modifier) {
    val tone = LocalTone.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(tone.ink), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_arrows), null, tint = tone.paper, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(9.dp))
        Text("tonefer", fontWeight = FontWeight.SemiBold, fontSize = ts(16f))
    }
}
