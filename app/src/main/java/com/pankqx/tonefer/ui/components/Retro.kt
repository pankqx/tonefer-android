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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
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

val Rule = 1.dp

/** Corner radius: soft rounded boxes by default, square in the "Classic" window style. */
/** Sharp corners everywhere (only the top bar keeps its rounded status pill and logo). */
val com.pankqx.tonefer.ui.theme.Tone.corner: Dp get() = 0.dp

@Composable
fun boxShape(radius: Dp = LocalTone.current.corner): Shape = RoundedCornerShape(radius)

/** Hard offset shadow, only in the "Classic" window style. */
@Composable
fun Modifier.hardShadow(offset: Dp = 3.dp): Modifier {
    val tone = LocalTone.current
    if (!tone.classic) return this
    return this.drawBehind {
        val o = offset.toPx()
        drawRect(tone.ink, topLeft = Offset(o, o), size = size)
    }
}

/** Page background, optionally with a faint dot grid. */
@Composable
fun Modifier.backdrop(): Modifier {
    val tone = LocalTone.current
    return this
        .background(tone.desk)
        .then(if (!tone.dots) Modifier else Modifier.drawBehind {
            val step = 16.dp.toPx()
            val r = 0.8.dp.toPx()
            val c = tone.ink.copy(alpha = 0.07f)
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) { drawCircle(c, r, Offset(x, y)); x += step }
                y += step
            }
        })
}

@Composable
fun Modifier.ruled(shape: Shape = boxShape()): Modifier {
    val tone = LocalTone.current
    return this.border(Rule, if (tone.classic) tone.ink else tone.line, shape)
}

/** Window header: a coloured dot and a small uppercase label. */
@Composable
fun TitleBar(title: String, onClose: (() -> Unit)? = null, dot: Color = LocalTone.current.danger) {
    val tone = LocalTone.current
    Column {
        Row(
            Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).background(dot))
            Spacer(Modifier.width(10.dp))
            Text(title.uppercase(), fontSize = ts(11.5f), letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium,
                color = tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onClose != null) MiniIcon(R.drawable.ic_close, "Close", tone.muted, onClose)
        }
        Box(Modifier.fillMaxWidth().height(Rule).background(if (tone.classic) tone.ink else tone.line))
    }
}

@Composable
fun RetroWindow(
    title: String,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    dot: Color = LocalTone.current.danger,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tone = LocalTone.current
    val shape = boxShape()
    Column(modifier.hardShadow().clip(shape).background(tone.paper).ruled(shape)) {
        TitleBar(title, onClose, dot)
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** Pill button. Primary = filled teal. */
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
    val shape = RectangleShape
    val fg = when { primary -> tone.accentInk; danger -> tone.danger; else -> tone.ink }.copy(alpha = if (enabled) 1f else .45f)
    Row(
        modifier
            .scale(if (pressed) .97f else 1f)
            .then(if (pressed || !enabled) Modifier else Modifier.hardShadow(2.dp))
            .clip(shape)
            .background(if (primary) tone.accent.copy(alpha = if (enabled) 1f else .45f) else tone.paper)
            .then(if (primary) Modifier else Modifier.border(Rule, if (tone.classic) tone.ink else tone.ink.copy(alpha = .3f), shape))
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontWeight = FontWeight.Medium, fontSize = ts(13.5f), maxLines = 1)
    }
}

/** Round icon button. Primary = filled teal. */
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
    val shape = RectangleShape
    Box(
        modifier
            .size(size)
            .scale(if (pressed) .92f else 1f)
            .clip(shape)
            .background(if (primary) tone.accent.copy(alpha = if (enabled) 1f else .4f) else tone.soft)
            .then(if (tone.classic) Modifier.border(Rule, tone.ink, shape) else Modifier)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), description,
            tint = if (primary) tone.accentInk else tone.ink.copy(alpha = if (enabled) 1f else .35f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Small borderless icon used inside bubbles. */
@Composable
fun MiniIcon(@DrawableRes icon: Int, description: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), description, tint = color, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun StatusLight(on: Boolean, label: String, modifier: Modifier = Modifier) {
    val tone = LocalTone.current
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .border(Rule, if (on) tone.accent else tone.line, RoundedCornerShape(50))
            .padding(start = 10.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) tone.accent else tone.line))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = ts(12f), fontWeight = FontWeight.Medium,
            color = if (on) tone.ink else tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = LocalTone.current.muted) {
    Text(text.uppercase(), modifier, fontSize = ts(10.5f), letterSpacing = 1.sp, fontWeight = FontWeight.Medium, color = color)
}

@Composable
fun DashedDivider(modifier: Modifier = Modifier, color: Color = LocalTone.current.line) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Settings checkbox row: a rounded teal check. */
@Composable
fun CheckRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tone = LocalTone.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox) { onChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val shape = RectangleShape
        Box(
            Modifier.padding(top = 2.dp).size(19.dp).clip(shape)
                .background(if (checked) tone.accent else Color.Transparent)
                .border(1.5.dp, if (checked) tone.accent else tone.ink.copy(alpha = .35f), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Canvas(Modifier.size(11.dp)) {
                val w = 2.dp.toPx()
                drawLine(tone.accentInk, Offset(size.width * .08f, size.height * .55f), Offset(size.width * .4f, size.height * .85f), w, StrokeCap.Round)
                drawLine(tone.accentInk, Offset(size.width * .4f, size.height * .85f), Offset(size.width * .95f, size.height * .15f), w, StrokeCap.Round)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = tone.muted, fontSize = ts(12f), lineHeight = ts(17f))
        }
    }
}

/** Segmented choice: pills, the selected one tinted teal. */
@Composable
fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, subtitle: String? = null) {
    val tone = LocalTone.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        if (subtitle != null) Text(subtitle, color = tone.muted, fontSize = ts(12f), lineHeight = ts(17f))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val on = value == selected
                val shape = RectangleShape
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(shape)
                        .background(if (on) tone.accentSoft else Color.Transparent)
                        .border(Rule, if (on) tone.accent else tone.ink.copy(alpha = .25f), shape)
                        .clickable(role = Role.RadioButton) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = tone.ink, fontSize = ts(12.5f),
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
    val LocalToneAccent = LocalTone.current.accent
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
        val a = if (linked) LocalToneAccent else color.copy(alpha = .45f)
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
        Box(Modifier.size(26.dp).clip(CircleShape).background(tone.accent), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_arrows), null, tint = tone.accentInk, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(9.dp))
        Text("tonefer", fontWeight = FontWeight.SemiBold, fontSize = ts(16f))
    }
}

@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
