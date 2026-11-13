package dev.soundsoftware.app.ui

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.soundsoftware.app.R

/** Design tokens copied from the confirmed mobile UI (docs/references/mobile-ui.html). */
object C {
    val Ink = Color(0xFF16181D)
    val Ink2 = Color(0xFF3A3F4A)
    val Muted = Color(0xFF5B616E)
    val Faint = Color(0xFF9AA0AB)
    val Line = Color(0xFFEEF0F3)
    val Line2 = Color(0xFFE6E8EC)
    val Border = Color(0xFFDADDE3)
    val Track = Color(0xFFD5D8DE)
    val HomeBg = Color(0xFFF2F2F5)
    val PageBg = Color(0xFFF6F7F9)
    val Card = Color(0xFFFFFFFF)
    val Chip = Color(0xFFEEEFF2)
    val Blue = Color(0xFF2453D6)
    val BlueSoft = Color(0xFFE8EEFC)
    val Highlight = Color(0xFFF1F4FD)
    val Green = Color(0xFF10B981)
    val Teal = Color(0xFF0F766E)
    val TealSoft = Color(0xFFDDF1EE)
    val Red = Color(0xFFB42318)
    val RedSoft = Color(0xFFFDECEC)
    val RedDot = Color(0xFFE5484D)
    val Orange = Color(0xFFFF9F43)
    val Amber = Color(0xFFE8890C)
    val Photo = Color(0xFFF2F2F4)   // light stage behind the transparent product renders
    // Dark sync screen
    val NightTop = Color(0xFF232633)
    val NightMid = Color(0xFF0E0F13)
    val NightEnd = Color(0xFF08090B)
    val NightAccent = Color(0xFF5B8CFF)
    val NightMuted = Color(0xFFA1A6B0)
    val NightValue = Color(0xFF9DB8FF)
    val NightPanel = Color(0x0FFFFFFF)
    val NightInner = Color(0xFF15171D)
}

data class SpeakerColor(val fg: Color, val bg: Color)

val SPEAKER_COLORS = listOf(
    SpeakerColor(C.Blue, C.BlueSoft), SpeakerColor(Color(0xFFB4530A), Color(0xFFFBEBDD)),
    SpeakerColor(C.Teal, C.TealSoft), SpeakerColor(Color(0xFF6D28D9), Color(0xFFEEE6FB)),
    SpeakerColor(Color(0xFFBE185D), Color(0xFFFCE7F3)), SpeakerColor(Color(0xFF4D7C0F), Color(0xFFECFCCB)),
)

fun speakerColor(index: Int) = SPEAKER_COLORS[(index - 1).mod(SPEAKER_COLORS.size)]

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(R.font.inter, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))

fun ts(size: Int, weight: Int = 400, color: Color = C.Ink, spacing: TextUnit = 0.em, lineHeight: Float? = null, tabular: Boolean = false) = TextStyle(
    fontFamily = Inter, fontSize = size.sp, fontWeight = FontWeight(weight), color = color, letterSpacing = spacing,
    lineHeight = lineHeight?.let { (size * it).sp } ?: TextUnit.Unspecified, fontFeatureSettings = if (tabular) "tnum" else null,
)

@Composable
fun SoundTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = C.Blue, background = C.HomeBg, surface = C.Card, onSurface = C.Ink),
        content = content)
}

val SelectionColors = TextSelectionColors(handleColor = C.Blue, backgroundColor = C.Blue.copy(alpha = 0.25f))
val CursorBrush = SolidColor(C.Blue)

/** Icons from the design's 24×24 SVG strokes, kept as path data so they render identically. */
object Icons {
    private fun icon(name: String, vararg paths: String, stroke: Float = 2f, filled: Boolean = false, vw: Float = 24f, vh: Float = 24f) =
        ImageVector.Builder(name, vw.dp, vh.dp, vw, vh).apply {
            paths.forEach { d ->
                addPath(PathParser().parsePathString(d).toNodes(),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = if (filled) null else SolidColor(Color.Black), strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    val Back = icon("back", "M15 5l-7 7 7 7")
    val BackBold = icon("back", "M15 5l-7 7 7 7", stroke = 2.2f)
    val Close = icon("close", "M6 6l12 12M18 6L6 18")
    val CloseBold = icon("close", "M6 6l12 12M18 6L6 18", stroke = 3f)
    val Search = icon("search", circle(11f, 11f, 7f), "M20 20l-3.5-3.5", stroke = 2.2f)
    val SearchThin = icon("search", circle(11f, 11f, 7f), "M20 20l-3.5-3.5")
    val Download = icon("download", "M12 4v12M6 11l6 6 6-6M5 20h14", stroke = 2.4f)
    val Share = icon("share", "M12 15V3M7 8l5-5 5 5M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6")
    val More = icon("more", circle(5f, 12f, 1.8f), circle(12f, 12f, 1.8f), circle(19f, 12f, 1.8f), filled = true)
    val Chevron = icon("chevron", "M9 6l6 6-6 6")
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7.5", stroke = 3f)
    val Trash = icon("trash", "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13")
    val Wifi = icon("wifi", "M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0", circle(12f, 19.5f, 1f))
    val Bluetooth = icon("bt", "M7 7l10 10-5 5V2l5 5L7 17")
    val Play = icon("play", "M8 5v14l11-7z", filled = true)
    val Pause = icon("pause", "M7 5h2.5a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM14.5 5H17a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-2.5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", filled = true)
    val Rewind = icon("rewind", "M3 12a9 9 0 1 0 9-9H8M8 3L5 6l3 3")
    val Forward = icon("forward", "M21 12a9 9 0 1 1-9-9h4M16 3l3 3-3 3")
    val Wave = icon("wave", "M4 10v4M8 6v12M12 3v18M16 7v10M20 10v4", stroke = 2.2f)
    val Todo = icon("todo", "M9 4h6a5 5 0 0 1 5 5v6a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5V9a5 5 0 0 1 5-5z", "M8.5 12l2.5 2.5 4.5-5", stroke = 2.2f)
    val Bean = icon("bean", "M12 4a6.5 8.5 0 1 1 0 17a6.5 8.5 0 1 1 0-17z", circle(12f, 8f, 1f), stroke = 2.2f)
    val Person = icon("person", circle(12f, 8f, 4f), "M4 21a8 8 0 0 1 16 0", stroke = 2.2f)
    val Doc = icon("doc", "M6 3h9l4 4v14H6z", "M14 3v5h5M9 13h7M9 17h5")
    val Link = icon("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val Folder = icon("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Copy = icon("copy", "M9 9h10v12H9z", "M5 15V3h10")
    val Plus = icon("plus", "M12 5v14M5 12h14", stroke = 2.2f)
    val Send = icon("send", "M5 12h14M13 6l6 6-6 6", stroke = 2.2f)
    val Import = icon("import", "M12 3v12M7 10l5 5 5-5M5 21h14")
    val Sparkle = icon("sparkle", "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z")
    val Bolt = icon("bolt", "M6 0L0 8h4l-1 6 7-9H6z", filled = true, vw = 10f, vh = 14f)
}
