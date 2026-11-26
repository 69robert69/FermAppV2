package ro.fermapp.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import ro.fermapp.app.Navigator
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.Role
import ro.fermapp.app.data.Toast
import ro.fermapp.app.data.errorMessage
import ro.fermapp.app.data.rememberApi
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.features.BarItem
import ro.fermapp.app.features.BarList
import ro.fermapp.app.platform.shareFile
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.Empty
import ro.fermapp.app.ui.IconAction
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.QueryContent
import ro.fermapp.app.ui.Screen
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.number
import ro.fermapp.domain.ReportTable
import ro.fermapp.domain.formatEuro
import ro.fermapp.domain.reportChartPoints
import kotlin.math.abs

private data class ReportKind(val value: String, val label: String, val financial: Boolean = false)

private val REPORTS = listOf(
    ReportKind("costs", "Economic", true),
    ReportKind("profitability", "Profitability", true),
    ReportKind("farm", "Farm", true),
    ReportKind("parcels", "Parcels"),
    ReportKind("work", "Field work"),
    ReportKind("machines", "Machines"),
    ReportKind("crops", "Crops"),
    ReportKind("yields", "Yields"),
    ReportKind("fuel", "Diesel"),
    ReportKind("rotation", "Rotation"),
    ReportKind("irrigation", "Irrigation"),
)

private const val COLUMN_WIDTH = 116

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen(navigator: Navigator) {
    val auth = rememberAuth()
    // Reports with money in them are hidden from operators, as on the server.
    val options = REPORTS.filter { auth.role != Role.OPERATOR || !it.financial }
    var type by remember { mutableStateOf(options.first().value) }
    var year by remember { mutableIntStateOf(currentYear()) }
    var orientation by remember { mutableStateOf("auto") }
    var exporting by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val report = rememberApi<ReportTable>("report/$type/$year", "/reports/$type", mapOf("year" to year))

    /** The server renders the same report tables to PDF and Excel; the phone shares the file. */
    fun export(format: String, mime: String) {
        exporting = format
        scope.launch {
            try {
                val orientationParam = if (format == "pdf" && orientation != "auto") "&orientation=$orientation" else ""
                val file = Api.download("/reports/$type?year=$year&format=$format$orientationParam", "report-$type-$year.$format")
                shareFile(file, mime)
            } catch (error: Throwable) {
                Toast.show("The ${if (format == "pdf") "PDF" else "Excel file"} was not created: ${errorMessage(error)}")
            } finally {
                exporting = null
            }
        }
    }

    Screen(title = "Reports", onRefresh = { report.refetch() }, refreshing = report.loading) {
        Choice(options.map { ChoiceOption(it.value, it.label) }, type, { type = it })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.lg, Alignment.CenterHorizontally)) {
            IconAction(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous year", { year-- })
            Txt(year.toString(), TextVariant.Figure)
            IconAction(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next year", { year++ })
        }
        Choice(
            listOf(ChoiceOption("auto", "Automatic"), ChoiceOption("portrait", "A4 portrait"), ChoiceOption("landscape", "A4 landscape")),
            orientation,
            { orientation = it },
            "PDF page",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            OutlinedButton(onClick = { export("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }, enabled = exporting == null, modifier = Modifier.weight(1f)) {
                Text(if (exporting == "xlsx") "Exporting…" else "Export Excel")
            }
            Button(onClick = { export("pdf", "application/pdf") }, enabled = exporting == null && report.data != null, modifier = Modifier.weight(1f)) {
                Text(if (exporting == "pdf") "Exporting…" else "Export PDF")
            }
        }
        QueryContent(report) { data ->
            if (data.rows.isEmpty()) {
                Empty(Icons.Outlined.FindInPage, "There is no data for this period", "Choose another year or report type.")
                return@QueryContent
            }
            val points = reportChartPoints(data)
            Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                Panel {
                    Txt(data.title, TextVariant.Title)
                    Txt(data.period, TextVariant.Small)
                    val summary = data.summary.orEmpty()
                    if (summary.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm), maxItemsInEachRow = 2) {
                            summary.forEach { item ->
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(FarmColors.ridge).padding(Space.sm),
                                ) {
                                    Txt(item.label, TextVariant.Small)
                                    Txt(item.value, color = FarmColors.chaff, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                val chart = data.chart
                if (chart != null && points.isNotEmpty()) {
                    Panel {
                        Txt(chart.title, TextVariant.Subtitle)
                        BarList(points.map { BarItem(it.label, it.label, if (it.value < 0) FarmColors.rust else FarmColors.wheat, abs(it.value)) }) { value ->
                            if (chart.money) formatEuro(value, 0) else "${number(value)}${chart.unit?.let { " $it" } ?: ""}"
                        }
                    }
                }
                Panel {
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        Row(Modifier.padding(bottom = Space.xs)) {
                            data.columns.forEach { column ->
                                Txt(column.label, TextVariant.Small, modifier = Modifier.width(COLUMN_WIDTH.dp).padding(end = Space.sm), align = if (column.numeric) TextAlign.End else TextAlign.Start)
                            }
                        }
                        HorizontalDivider(color = FarmColors.line, modifier = Modifier.width((COLUMN_WIDTH * data.columns.size).dp))
                        data.rows.take(100).forEach { row ->
                            Row(Modifier.padding(vertical = Space.xs)) {
                                data.columns.forEach { column ->
                                    val cell = row[column.key]
                                    val primitive = cell as? JsonPrimitive
                                    val text = when {
                                        cell == null || cell is JsonNull -> ""
                                        column.money && primitive != null && !primitive.isString -> primitive.doubleOrNull?.let { formatEuro(it, 2) } ?: primitive.content
                                        else -> primitive?.content ?: cell.toString()
                                    }
                                    Txt(text, maxLines = 2, modifier = Modifier.width(COLUMN_WIDTH.dp).padding(end = Space.sm), align = if (column.numeric) TextAlign.End else TextAlign.Start)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
