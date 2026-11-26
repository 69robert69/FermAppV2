package ro.fermapp.app.features

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.toFixed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** The executive charts of features/tillage/charts.tsx, drawn with Compose. */

data class Series(val key: String, val label: String, val color: Color)

private val GRID = FarmColors.line
private val INK_MUTED = FarmColors.straw

fun niceMax(value: Double): Double {
    if (value <= 0) return 1.0
    val magnitude = 10.0.pow(floor(log10(value)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= value }
    return step * magnitude
}

fun compact(value: Double): String = when {
    abs(value) >= 10_000 -> "${jsRound(value / 1000).toLong()}k"
    abs(value) >= 1000 -> "${(value / 1000).toFixed(1)}k"
    else -> jsRound(value).toLong().toString()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesLegend(series: List<Series>, values: Map<String, String> = emptyMap()) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        series.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(item.color))
                Txt(item.label, TextVariant.Small, color = FarmColors.chaff)
                values[item.key]?.let { Txt(it, TextVariant.Small) }
            }
        }
    }
}

data class DonutItem(val key: String, val label: String, val color: Color, val value: Double)

/** Share of a whole. Tap the ring to step through the segments and read each one in the centre. */
@Composable
fun Donut(data: List<DonutItem>, total: Double, unit: String, centerLabel: String) {
    var active by remember { mutableStateOf<String?>(null) }
    val visible = data.filter { it.value > 0 }
    val focus = data.firstOrNull { it.key == active }
    fun pct(value: Double) = if (total > 0) jsRound(value / total * 100).toLong() else 0L

    Box(
        Modifier.fillMaxWidth().height(180.dp)
            .semantics { contentDescription = "$centerLabel: ${data.joinToString(", ") { "${it.label} ${pct(it.value)}%" }}" }
            .clickable {
                val keys = visible.map { it.key }
                active = if (keys.isEmpty()) null else when (val index = keys.indexOf(active)) {
                    -1 -> keys.first()
                    keys.lastIndex -> null
                    else -> keys[index + 1]
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(180.dp)) {
            val stroke = 22.dp.toPx()
            val radius = (size.minDimension - stroke) / 2
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            drawCircle(FarmColors.ridge, radius, style = Stroke(stroke))
            val gapDeg = if (visible.size > 1) (2.dp.toPx() / radius) * 180f / PI.toFloat() else 0f
            var cursor = -90f
            visible.forEach { item ->
                val sweep = (item.value / max(total, 1e-9) * 360).toFloat()
                val width = if (active == item.key) stroke + 6.dp.toPx() else stroke
                val alpha = if (active != null && active != item.key) 0.45f else 1f
                drawArc(
                    item.color.copy(alpha = alpha), cursor + gapDeg / 2, if (visible.size == 1) 359.99f else sweep - gapDeg, false,
                    topLeft, arcSize, style = Stroke(width),
                )
                cursor += sweep
            }
        }
        Column(Modifier.padding(horizontal = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(if (focus != null) "${pct(focus.value)}%" else jsRound(total).toLong().toString(), TextVariant.Figure, color = FarmColors.chaff)
            Txt(focus?.label ?: "$unit $centerLabel", TextVariant.Small, align = TextAlign.Center, maxLines = 2)
        }
    }
}

/** Stacked yearly columns; tap a year to read its parts. */
@Composable
fun StackedColumns(years: List<Int>, series: List<Series>, values: List<Map<String, Double>>, unit: String) {
    var active by remember { mutableStateOf<Int?>(null) }
    val totals = values.map { row -> series.sumOf { row[it.key] ?: 0.0 } }
    val maxValue = niceMax(max(totals.maxOrNull() ?: 0.0, 1.0))
    val ticks = listOf(0.0, maxValue / 2, maxValue)

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            Row(Modifier.fillMaxWidth().height(170.dp)) {
                Column(Modifier.size(34.dp, 148.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                    ticks.reversed().forEach { Txt(compact(it), TextVariant.Small) }
                }
                Column(Modifier.weight(1f)) {
                    Canvas(
                        Modifier.fillMaxWidth().height(148.dp).pointerInput(years) {
                            detectTapGestures { offset ->
                                val slot = size.width / max(years.size, 1).toFloat()
                                val index = (offset.x / slot).toInt().coerceIn(0, years.lastIndex)
                                active = if (active == index) null else index
                            }
                        },
                    ) {
                        val plotH = size.height - 8.dp.toPx()
                        ticks.forEach { tick ->
                            val y = 8.dp.toPx() + plotH - (tick / maxValue).toFloat() * plotH
                            drawLine(GRID, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = if (tick == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
                        }
                        val slot = size.width / max(years.size, 1)
                        val barW = min(36.dp.toPx(), slot * 0.6f)
                        years.forEachIndexed { index, _ ->
                            val x = slot * index + (slot - barW) / 2
                            var y = 8.dp.toPx() + plotH
                            val parts = series.map { it to (values.getOrNull(index)?.get(it.key) ?: 0.0) }.filter { it.second > 0 }
                            val alpha = if (active == null || active == index) 1f else 0.45f
                            parts.forEachIndexed { partIndex, (item, value) ->
                                val h = (value / maxValue).toFloat() * plotH
                                y -= h
                                val isTop = partIndex == parts.lastIndex
                                drawRoundRect(
                                    item.color.copy(alpha = alpha), Offset(x, y), Size(barW, max(0f, h - if (isTop) 0f else 2.dp.toPx())),
                                    if (isTop) CornerRadius(4.dp.toPx()) else CornerRadius.Zero,
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        years.forEachIndexed { index, year ->
                            Txt(year.toString(), TextVariant.Small, color = if (active == index) FarmColors.chaff else INK_MUTED, align = TextAlign.Center, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        active?.let { index ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FarmColors.ridge).border(1.dp, FarmColors.line, RoundedCornerShape(10.dp)).padding(Space.md),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Txt("${years[index]}, ${compact(totals.getOrElse(index) { 0.0 })} $unit", TextVariant.Subtitle)
                series.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(item.color))
                        Txt(item.label, TextVariant.Small, modifier = Modifier.weight(1f))
                        Txt("${compact(values.getOrNull(index)?.get(item.key) ?: 0.0)} $unit", TextVariant.Small, color = FarmColors.chaff)
                    }
                }
            }
        }
        SeriesLegend(series)
    }
}

data class TrendPoint(val x: Int, val y: Double?)

/** One measure over time, with a crosshair that follows the finger. */
@Composable
fun TrendLine(points: List<TrendPoint>, color: Color, domain: Pair<Double, Double>, format: (Double) -> String, label: String) {
    var active by remember { mutableStateOf<Int?>(null) }
    val (lo, hi) = domain
    val ticks = listOf(lo, (lo + hi) / 2, hi)
    fun pick(x: Float, width: Float) {
        val index = if (points.size > 1) (x / width * (points.size - 1)).roundToInt() else 0
        active = index.coerceIn(0, max(points.lastIndex, 0))
    }
    val focus = active?.let { points.getOrNull(it) }

    Column {
        Row(Modifier.fillMaxWidth().height(150.dp)) {
            Column(Modifier.size(34.dp, 128.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                ticks.reversed().forEach { Txt(format(it), TextVariant.Small) }
            }
            Column(Modifier.weight(1f)) {
                Canvas(
                    Modifier.fillMaxWidth().height(128.dp)
                        .semantics { contentDescription = "$label: ${points.joinToString(", ") { "${it.x} ${it.y?.let(format) ?: "no data"}" }}" }
                        .pointerInput(points) { detectTapGestures { pick(it.x, size.width.toFloat()) } }
                        .pointerInput(points) { detectDragGestures { change, _ -> pick(change.position.x, size.width.toFloat()) } },
                ) {
                    val top = 6.dp.toPx()
                    val plotH = size.height - top * 2
                    fun xAt(index: Int) = if (points.size > 1) index / (points.size - 1f) * size.width else size.width / 2
                    fun yAt(value: Double) = top + plotH - ((value - lo) / max(hi - lo, 1e-9)).toFloat() * plotH
                    ticks.forEach { tick ->
                        drawLine(GRID, Offset(0f, yAt(tick)), Offset(size.width, yAt(tick)), 1f, pathEffect = if (tick == lo) null else PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
                    }
                    active?.let { drawLine(INK_MUTED, Offset(xAt(it), top), Offset(xAt(it), top + plotH), 1f) }
                    val path = Path()
                    var pen = false
                    points.forEachIndexed { index, point ->
                        val y = point.y
                        if (y == null) { pen = false; return@forEachIndexed }
                        if (pen) path.lineTo(xAt(index), yAt(y)) else path.moveTo(xAt(index), yAt(y))
                        pen = true
                    }
                    drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    points.forEachIndexed { index, point ->
                        val y = point.y ?: return@forEachIndexed
                        val r = (if (active == index) 6 else 4).dp.toPx()
                        drawCircle(FarmColors.furrow, r + 2.dp.toPx(), Offset(xAt(index), yAt(y)))
                        drawCircle(color, r, Offset(xAt(index), yAt(y)))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    points.forEachIndexed { index, point ->
                        Txt(point.x.toString(), TextVariant.Small, color = if (active == index) FarmColors.chaff else INK_MUTED, align = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Txt(label, TextVariant.Small, modifier = Modifier.weight(1f))
            Txt(focus?.let { "${it.x}: ${it.y?.let(format) ?: "no data"}" } ?: "Touch the chart to read a year", TextVariant.Small, color = FarmColors.chaff)
        }
    }
}

/** Switch between the chart and a plain table of the same numbers. */
@Composable
fun ChartTableToggle(table: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, FarmColors.line, RoundedCornerShape(8.dp))) {
        listOf(false to "Chart", true to "Table").forEach { (value, label) ->
            Box(
                Modifier.heightIn(min = 32.dp).background(if (table == value) FarmColors.wheat else Color.Transparent).clickable { onChange(value) }.padding(horizontal = Space.md),
                contentAlignment = Alignment.Center,
            ) { Txt(label, TextVariant.Small, color = if (table == value) FarmColors.wheatInk else FarmColors.straw) }
        }
    }
}

@Composable
fun DataTable(columns: List<String>, rows: List<List<String>>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, FarmColors.line, RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().background(FarmColors.ridge).padding(horizontal = Space.sm, vertical = 6.dp)) {
            columns.forEachIndexed { index, column ->
                Txt(column, TextVariant.Small, color = FarmColors.chaff, align = if (index > 0) TextAlign.End else TextAlign.Start, modifier = Modifier.weight(if (index == 0) 1.4f else 1f))
            }
        }
        rows.forEach { row ->
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(FarmColors.line))
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = 6.dp)) {
                row.forEachIndexed { index, cell ->
                    Txt(cell, TextVariant.Small, color = if (index == 0) FarmColors.chaff else null, align = if (index > 0) TextAlign.End else TextAlign.Start, modifier = Modifier.weight(if (index == 0) 1.4f else 1f))
                }
            }
        }
    }
}
