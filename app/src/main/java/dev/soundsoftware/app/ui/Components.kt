package dev.soundsoftware.app.ui

import dev.soundsoftware.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun T(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null) =
    BasicText(text, modifier, style.let { if (align != null) it.copy(textAlign = align) else it }, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
fun Ico(icon: ImageVector, size: Dp = 22.dp, color: Color = C.Ink, modifier: Modifier = Modifier) =
    Icon(icon, null, modifier.size(size), tint = color)

fun Modifier.tap(label: String? = null, enabled: Boolean = true, ripple: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    this.clickable(source, if (ripple) LocalIndication.current else null, enabled = enabled, role = Role.Button, onClick = onClick)
        .let { if (label != null) it.semantics { contentDescription = label } else it }
}

@Composable
fun IconButtonPlain(icon: ImageVector, label: String, size: Dp = 22.dp, color: Color = C.Ink, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).tap(label, enabled, onClick = onClick), Alignment.Center) { Ico(icon, size, if (enabled) color else C.Faint) }
}

@Composable
fun RoundIconButton(icon: ImageVector, label: String, bg: Color, color: Color, size: Dp = 20.dp, shadow: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).let { if (shadow) it.shadow(1.dp, CircleShape, ambientColor = Color(0x0F000000), spotColor = Color(0x0F000000)) else it }
        .clip(CircleShape).background(bg).tap(label, onClick = onClick), Alignment.Center) { Ico(icon, size, color) }
}

/** Browser-style checkbox with accent color, as drawn in the design. */
@Composable
fun WebCheckbox(checked: Boolean, onChange: ((Boolean) -> Unit)?, size: Dp = 22.dp, accent: Color = C.Blue, dark: Boolean = false, label: String? = null) {
    val shape = RoundedCornerShape(size * 0.2f)
    Box(Modifier.size(size).clip(shape)
        .background(if (checked) accent else if (dark) Color.White else Color.White)
        .border(if (checked) 0.dp else 1.5.dp, if (checked) Color.Transparent else Color(0xFF767676), shape)
        .let { m -> if (onChange != null) m.toggleable(checked, role = Role.Checkbox, onValueChange = onChange).let { if (label != null) it.semantics { contentDescription = label } else it } else m },
        Alignment.Center) {
        if (checked) Ico(Icons.Check, size * 0.72f, Color.White)
    }
}

@Composable
fun BatteryGlyph(percent: Int, width: Dp = 24.dp, height: Dp = 12.dp, radius: Dp = 3.dp) {
    Box(Modifier.size(width, height).border(1.5.dp, C.Faint, RoundedCornerShape(radius)).padding(1.5.dp + 1.5.dp / 2)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth((percent / 100f).coerceIn(0.04f, 1f)).clip(RoundedCornerShape(radius / 2))
            .background(if (percent <= 20) C.RedDot else C.Green))
    }
}

@Composable
fun Avatar(label: String, color: Color, size: Dp = 32.dp, fontSize: Int = 13, border: Dp = 0.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(Color.White).padding(border).clip(CircleShape).background(color), Alignment.Center) {
        T(label, ts(fontSize, 600, Color.White))
    }
}

fun avatarLabel(name: String?, index: Int): String = name?.trim()?.firstOrNull()?.toString() ?: index.toString()

@Composable
fun StatusChip(text: String, fg: Color, bg: Color) {
    T(text, ts(12, 600, fg), Modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 9.dp, vertical = 4.dp), maxLines = 1)
}

@Composable
fun Waveform(values: List<Float>, colorAt: (Float) -> Color, height: Dp = 30.dp, modifier: Modifier = Modifier) {
    val bars = values.ifEmpty { List(44) { 0.15f } }
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        bars.forEachIndexed { i, v ->
            Box(Modifier.width(3.dp).height((4 + v * 25).coerceAtLeast(4f).dp).clip(RoundedCornerShape(2.dp)).background(colorAt(i.toFloat() / bars.size)))
        }
    }
}

@Composable
fun TopBar(title: String?, onBack: (() -> Unit)?, backIcon: ImageVector = Icons.Back, titleSize: Int = 17, actions: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        Row(Modifier.align(Alignment.CenterStart)) {
            if (onBack != null) IconButtonPlain(backIcon, if (backIcon == Icons.Close) stringResource(R.string.close) else stringResource(R.string.back), onClick = onBack) else Spacer(Modifier.width(44.dp))
        }
        if (title != null) T(title, ts(titleSize, 600), Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.CenterEnd), content = actions)
    }
}

@Composable
fun SectionLabel(text: String, top: Dp = 20.dp) = T(text, ts(13, 400, C.Muted), Modifier.padding(start = 20.dp, end = 20.dp, top = top, bottom = 8.dp))

@Composable
fun Group(radius: Dp = 16.dp, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(radius)).background(C.Card), content = content)

@Composable
fun Divider(color: Color = C.Line) = Box(Modifier.fillMaxWidth().height(1.dp).background(color))

@Composable
fun SettingRow(title: String, value: String? = null, subtitle: String? = null, chevron: Boolean = false, minHeight: Dp = 52.dp,
    titleColor: Color = C.Ink, divider: Boolean = true, inset: Dp = 16.dp, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = minHeight).let { if (onClick != null) it.tap(onClick = onClick) else it }.padding(horizontal = inset),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(title, ts(15, 400, titleColor))
                if (subtitle != null) T(subtitle, ts(12, 400, C.Muted))
            }
            if (value != null) T(value, ts(14, 400, C.Muted, tabular = true), Modifier.widthIn(max = 220.dp), maxLines = 2, align = TextAlign.End)
            trailing?.invoke()
            if (chevron) Ico(Icons.Chevron, 16.dp, C.Muted)
        }
        if (divider) Divider()
    }
}

@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, bg: Color = C.Ink, fg: Color = Color.White, height: Dp = 52.dp, fontSize: Int = 16,
    weight: Int = 600, enabled: Boolean = true, border: Color? = null, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(if (enabled) bg else C.Chip)
        .let { if (border != null) it.border(1.dp, border, RoundedCornerShape(height / 2)) else it }
        .tap(enabled = enabled, onClick = onClick), Alignment.Center) {
        T(text, ts(fontSize, weight, if (enabled) fg else C.Muted))
    }
}

@Composable
fun OutlineChip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    T(text, ts(13, 400, if (selected) Color.White else C.Ink), Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
        .background(if (selected) C.Ink else Color.White).border(1.dp, if (selected) C.Ink else C.Border, RoundedCornerShape(16.dp))
        .tap(onClick = onClick).padding(horizontal = 12.dp).wrapContentHeight(Alignment.CenterVertically), maxLines = 1)
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, secret: Boolean = false,
    autoFocus: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, onDone: (() -> Unit)? = null) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    CompositionLocalProvider(LocalTextSelectionColors provides SelectionColors) {
        BasicTextField(value, onChange, modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { focused = it.isFocused }.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
            .border(if (focused) 2.dp else 1.dp, if (focused) C.Blue else C.Border, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            textStyle = ts(16), singleLine = singleLine, cursorBrush = CursorBrush,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (value.isEmpty()) T(placeholder, ts(16, 400, C.Faint)); inner() } })
    }
}

/** Bottom sheet with scrim; content draws its own handle. */
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x7316181D)).tap(ripple = false, onClick = onDismiss)) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Color.White)
            .tap(ripple = false) {}.navigationBarsPadding().imePadding().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(40.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(C.Border).align(Alignment.CenterHorizontally))
            content()
        }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, danger: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Sheet(onDismiss) {
        T(title, ts(18, 700))
        T(message, ts(14, 400, C.Muted, lineHeight = 1.55f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(stringResource(R.string.cancel), Modifier.weight(1f), bg = Color.White, fg = C.Ink, height = 50.dp, weight = 400, border = C.Border, onClick = onDismiss)
            PillButton(confirm, Modifier.weight(1f), bg = if (danger) C.Red else C.Ink, height = 50.dp, onClick = onConfirm)
        }
    }
}

@Composable
fun Pulse(periodMs: Int = 2200, minAlpha: Float = 0.35f, content: @Composable (Float) -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(minAlpha, 1f, infiniteRepeatable(tween(periodMs / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    content(a)
}

@Composable
fun Dot(color: Color, size: Dp = 7.dp, breathe: Boolean = false) {
    if (breathe) Pulse { a -> Box(Modifier.size(size).alpha(a).clip(CircleShape).background(color)) }
    else Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun RingProgress(fraction: Float, size: Dp, stroke: Dp, color: Color, track: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = stroke.toPx()
        drawArc(track, 0f, 360f, false, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w), style = Stroke(w))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w), style = Stroke(w))
    }
}

// ---- Formatting -------------------------------------------------------------------------

fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
@Composable
fun minutes(ms: Long): String = if (ms < 60_000) stringResource(R.string.duration_seconds, (ms / 1000).coerceAtLeast(1)) else stringResource(R.string.duration_minutes, (ms + 30_000) / 60_000)
fun megabytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024 * 1024 -> "${(bytes + 512 * 1024) / (1024 * 1024)} MB"
    else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
}
@Composable
fun dayTime(ms: Long): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return android.text.format.DateFormat.getMediumDateFormat(context).format(ms) + " " + timeOnly(ms)
}
@Composable
fun timeOnly(ms: Long): String =
    android.text.format.DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current).format(ms)
@Composable
fun dayLabel(ms: Long, now: Long = System.currentTimeMillis()): String {
    fun day(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
    return when (day(now) - day(ms)) { 0 -> stringResource(R.string.today); 1 -> stringResource(R.string.yesterday); else -> headerDate(ms) }
}
@Composable
fun headerDate(now: Long = System.currentTimeMillis()): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMMdEEEE")
    return SimpleDateFormat(pattern, locale).format(now)
}
