package ro.fermapp.app.features

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Agriculture
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Grass
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Yard
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.decodeToImageBitmap
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.AppJson
import ro.fermapp.app.data.Auth
import ro.fermapp.app.data.IrrigationRun
import ro.fermapp.app.data.ParcelIntelligence
import ro.fermapp.app.data.Queries
import ro.fermapp.app.data.RecommendationsResponse
import ro.fermapp.app.data.RunStatus
import ro.fermapp.app.data.SatellitePoint
import ro.fermapp.app.data.SendResult
import ro.fermapp.app.data.TaskStatus
import ro.fermapp.app.data.Toast
import ro.fermapp.app.data.WeatherDay
import ro.fermapp.app.data.WorkSession
import ro.fermapp.app.data.body
import ro.fermapp.app.data.errorMessage
import ro.fermapp.app.data.intelligenceKey
import ro.fermapp.app.data.rememberActions
import ro.fermapp.app.data.rememberApi
import ro.fermapp.app.data.rememberWork
import ro.fermapp.app.platform.rememberFilePicker
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.theme.hexColor
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.InfoRow
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.ProgressTrack
import ro.fermapp.app.ui.Section
import ro.fermapp.app.ui.Stat
import ro.fermapp.app.ui.TASK_TYPES
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.WORK_TYPES
import ro.fermapp.app.ui.clock
import ro.fermapp.app.ui.fullDate
import ro.fermapp.app.ui.hoursMinutes
import ro.fermapp.app.ui.liveProgress
import ro.fermapp.app.ui.number
import ro.fermapp.app.ui.shortDate
import ro.fermapp.app.ui.toRoDate
import ro.fermapp.domain.ActionPriority
import ro.fermapp.domain.CropCode
import ro.fermapp.domain.HealthColor
import ro.fermapp.domain.RotationVerdict
import ro.fermapp.domain.SprayAlertCode
import ro.fermapp.domain.economicInsights
import ro.fermapp.domain.formatEuro
import ro.fermapp.domain.jsNum
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.parseJsDate
import ro.fermapp.domain.toFixed
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds
import ro.fermapp.app.screens.leftEdge
import ro.fermapp.domain.toJsIsoString

/** The sections of the parcel intelligence panel, as features/intelligence/sections.tsx. */

private fun cropIcon(code: CropCode): ImageVector = when (code) {
    CropCode.WHEAT, CropCode.BARLEY, CropCode.OAT -> Icons.Outlined.Grass
    CropCode.CORN -> Icons.Outlined.Yard
    CropCode.SUNFLOWER, CropCode.RAPESEED -> Icons.Outlined.LocalFlorist
    else -> Icons.Outlined.Spa
}

private val CROP_STATUS = mapOf(
    "NOT_SOWN" to ("Not sown" to FarmColors.straw),
    "GROWING" to ("Growing" to FarmColors.leaf),
    "READY" to ("Near harvest" to FarmColors.wheat),
    "HARVESTED" to ("Harvested" to FarmColors.straw),
)

private val VERDICT_COLOR = mapOf(RotationVerdict.RECOMMENDED to FarmColors.leaf, RotationVerdict.ACCEPTABLE to FarmColors.amber, RotationVerdict.NOT_RECOMMENDED to FarmColors.rust)
private val LEVEL_COLOR = mapOf(ActionPriority.LOW to FarmColors.leaf, ActionPriority.MEDIUM to FarmColors.amber, ActionPriority.HIGH to FarmColors.rust)
private val LEVEL_LABEL = mapOf(ActionPriority.LOW to "Low", ActionPriority.MEDIUM to "Medium", ActionPriority.HIGH to "High")
private val HEALTH_COLOR = mapOf(HealthColor.GREEN to FarmColors.leaf, HealthColor.YELLOW to FarmColors.amber, HealthColor.RED to FarmColors.rust)
private val HEALTH_LABEL = mapOf(HealthColor.GREEN to "Healthy", HealthColor.YELLOW to "Warning", HealthColor.RED to "Critical")

val IMPLEMENT_COLOR: Map<String, Color> = mapOf(
    "PLOWING" to hexColor("#8A5A34"),
    "DISCING" to hexColor("#A0703F"),
    "SUBSOILING" to hexColor("#7A5234"),
    "SOWING" to hexColor("#58A23A"),
    "FERTILIZING" to hexColor("#9BC46A"),
    "SPRAYING" to hexColor("#4FB39A"),
    "HARVESTING" to hexColor("#E2B23D"),
)

private fun dateText(value: String?) = value?.let { toRoDate(parseJsDate(it)) } ?: "Not filled in"

@Composable
fun CircularProgress(percent: Double, size: Dp = 96.dp, color: Color = FarmColors.water, icon: ImageVector? = null) {
    val value = percent.coerceIn(0.0, 100.0)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2
            val arc = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(FarmColors.ridge, 0f, 360f, false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arc, style = Stroke(stroke))
            drawArc(color, -90f, (360 * value / 100).toFloat(), false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(18.dp)) }
            Txt("${jsNum(jsRound(value))}%", TextVariant.Subtitle)
        }
    }
}

@Serializable
private data class ChatAnswer(val message: Content) {
    @Serializable
    data class Content(val content: String)
}

/** Asks the assistant for an explanation based on the parcel's real data. */
@Composable
fun AiExplain(parcelId: String, question: String) {
    var answer by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    answer?.let {
        Txt(it)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        TextButton(onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    val result = Api.post("/ai/chat", body("message" to question, "parcelId" to parcelId), offline = false)
                    answer = if (result is SendResult.Done) AppJson.decodeFromJsonElement(ChatAnswer.serializer(), result.value).message.content else ""
                } catch (problem: Throwable) {
                    error = errorMessage(problem)
                } finally {
                    busy = false
                }
            }
        }, enabled = !busy) { Text(if (busy) "Asking…" else "Explain in more detail") }
        error?.let { Txt(it, TextVariant.Small, color = FarmColors.rust) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeaderSection(data: ParcelIntelligence) {
    val header = data.header
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Txt("Parcel Intelligence", TextVariant.Small)
        Txt(header.name, TextVariant.Display)
        FlowRow(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Stat(number(header.areaHa, 2), "Area", unit = "ha", color = FarmColors.wheat)
            Stat(header.code, "Parcel code")
        }
        InfoRow("County", header.county ?: "Not filled in")
        InfoRow("Locality", header.locality ?: "Not filled in")
        InfoRow("GPS coordinates", "${header.lat.toFixed(5)}, ${header.lng.toFixed(5)}")
    }
}

@Composable
fun CropSection(data: ParcelIntelligence) {
    val crop = data.crop
    Section("Current crop") {
        Panel {
            if (crop == null) {
                Txt("There is no crop recorded for this year.", TextVariant.Small)
                return@Panel
            }
            val (label, color) = CROP_STATUS[crop.status] ?: ("" to FarmColors.straw)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                Icon(cropIcon(crop.code), null, tint = color, modifier = Modifier.size(30.dp))
                Column(Modifier.weight(1f)) {
                    Txt(crop.name, TextVariant.Title)
                    Txt(label, TextVariant.Small, color = color)
                }
                Txt("${crop.percent}%", TextVariant.Figure, color = color)
            }
            ProgressTrack(crop.percent.toDouble(), color)
            InfoRow("Sown", dateText(crop.sowingDate))
            InfoRow("Estimated harvest", dateText(crop.estimatedHarvestDate))
            InfoRow("Growing day", "${crop.vegetationDay} / ${crop.totalDays} days")
        }
    }
}

@Composable
fun RotationSection(data: ParcelIntelligence) {
    val rotation = data.rotation
    val color = VERDICT_COLOR.getValue(rotation.verdict)
    Section("Crop rotation") {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                CircularProgress(rotation.score.toDouble(), 76.dp, color)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(rotation.label, TextVariant.Subtitle, color = color)
                    if (rotation.mode == "SUGGESTED") Txt("The best option for this year", TextVariant.Small)
                    Txt(rotation.explanation, TextVariant.Small)
                }
            }
            if (rotation.history.isEmpty()) Txt("Add the crop history for a more accurate score.", TextVariant.Small)
            rotation.history.forEach { InfoRow(it.year.toString(), it.name) }
            AiExplain(data.header.id, "Explain this parcel's rotation score and what risk exists.")
        }
    }
}

@Composable
fun IrrigationCoverageSection(data: ParcelIntelligence) {
    val irrigation = data.irrigation
    Section("Irrigation") {
        Panel {
            if (irrigation.configured) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    CircularProgress(irrigation.coveragePct ?: 0.0, icon = Icons.Outlined.WaterDrop)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Stat(number(irrigation.irrigatedHa ?: 0.0, 2), "Irrigated", unit = "ha", color = FarmColors.water)
                        Stat(number(irrigation.notIrrigatedHa ?: 0.0, 2), "Not irrigated", unit = "ha")
                    }
                }
            } else {
                Txt("Fill in the irrigated area in the parcel editor.", TextVariant.Small)
            }
        }
    }
}

@Composable
private fun IrrigationCard(run: IrrigationRun) {
    val progress = liveProgress(run)
    val paused = run.status == RunStatus.PAUSED
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Icon(Icons.Outlined.WaterDrop, null, tint = FarmColors.water, modifier = Modifier.size(24.dp))
            Txt(run.label ?: run.parcel.name, TextVariant.Subtitle, modifier = Modifier.weight(1f))
            Txt(if (paused) "Paused" else "Running", color = if (paused) FarmColors.straw else FarmColors.water)
        }
        ProgressTrack(progress.percent, if (paused) FarmColors.straw else FarmColors.water)
        InfoRow("Speed", "${number(run.speedMPerH, 0)} m/h")
        InfoRow("Time left", hoursMinutes(progress.remainingSeconds))
        InfoRow("ETA", progress.eta?.let(::clock) ?: "Stopped")
    }
}

@Composable
fun ActiveIrrigationSection(data: ParcelIntelligence) {
    if (data.activeIrrigation.isEmpty()) return
    Section("Active irrigation systems") { data.activeIrrigation.forEach { IrrigationCard(it) } }
}

@Composable
private fun WorkCard(work: WorkSession, onTrack: (WorkSession) -> Unit) {
    val progress = liveProgress(work)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Icon(Icons.Outlined.Agriculture, null, tint = FarmColors.wheat, modifier = Modifier.size(24.dp))
            Txt(work.machine?.name ?: "No machine", TextVariant.Subtitle, modifier = Modifier.weight(1f))
            Txt("${floor(progress.percent).toInt()}%", TextVariant.Figure, color = FarmColors.wheat)
        }
        ProgressTrack(progress.percent)
        InfoRow("Operator", work.operator.fullName)
        InfoRow("Operation", WORK_TYPES[work.type] ?: work.type)
        work.workingWidthM?.let { InfoRow("Width", "${number(it)} m") }
        work.speedKmh?.let { InfoRow("Speed", "${number(it)} km/h") }
        InfoRow("ETA", if (work.status == RunStatus.PAUSED) "Paused" else hoursMinutes(progress.remainingSeconds))
        OutlinedButton(onClick = { onTrack(work) }, modifier = Modifier.fillMaxWidth()) { Text("See live position") }
    }
}

@Composable
fun ActiveWorkSection(data: ParcelIntelligence, onTrack: (WorkSession) -> Unit) {
    if (data.activeWork.isEmpty()) return
    Section("Active operations") { data.activeWork.forEach { WorkCard(it, onTrack) } }
}

@Composable
fun NextActionSection(data: ParcelIntelligence) {
    val action = data.nextAction
    Section("Next recommended action") {
        Panel(Modifier.leftEdge(LEVEL_COLOR.getValue(action.priority))) {
            Txt(action.action, TextVariant.Title)
            Txt(action.reason, color = FarmColors.straw)
            InfoRow("Priority", LEVEL_LABEL.getValue(action.priority), LEVEL_COLOR.getValue(action.priority))
            InfoRow("Risk level", LEVEL_LABEL.getValue(action.riskLevel), LEVEL_COLOR.getValue(action.riskLevel))
            InfoRow("Confidence level", "${action.confidencePct}%")
            AiExplain(data.header.id, "Explain why this is recommended: ${action.action}")
        }
    }
}

private val TASK_FLOW = mapOf(
    TaskStatus.TODO to Triple(TaskStatus.IN_PROGRESS, "Start", "To do"),
    TaskStatus.IN_PROGRESS to Triple(TaskStatus.DONE, "Complete", "In progress"),
    TaskStatus.DONE to Triple(TaskStatus.TODO, "Reopen", "Done"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksSection(data: ParcelIntelligence) {
    val summary = data.tasks.summary
    val actions = rememberActions()
    Section("Tasks") {
        Panel {
            FlowRow(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Stat(summary.todo.toString(), "To do")
                Stat(summary.inProgress.toString(), "In progress", color = FarmColors.wheat)
                Stat(summary.done.toString(), "Done", color = FarmColors.leaf)
            }
            data.tasks.items.take(8).forEach { task ->
                val (next, label, status) = TASK_FLOW.getValue(task.status)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    Column(Modifier.weight(1f)) {
                        Txt(task.title.ifEmpty { TASK_TYPES[task.type] ?: task.type })
                        Txt("$status, due ${shortDate(task.dueDate)}", TextVariant.Small)
                    }
                    Box(Modifier.heightIn(min = 44.dp).clickable {
                        actions.run(intelligenceKey(data.header.id), "tasks", "dashboard") { Api.post("/tasks/${task.id}/status", body("status" to next)) }
                    }.padding(horizontal = Space.sm), contentAlignment = Alignment.Center) { Txt(label, color = FarmColors.wheat) }
                }
            }
        }
    }
}

@Composable
private fun ForecastRow(day: WeatherDay) {
    InfoRow(
        shortDate(day.date),
        "${jsNum(jsRound(day.tMinC))}° / ${jsNum(jsRound(day.tMaxC))}°, ${number(day.precipitationMm)} mm, ${jsNum(day.rainProbabilityPct ?: 0.0)}%",
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeatherSection(data: ParcelIntelligence) {
    var range by remember { mutableStateOf("today") }
    val weather = data.weather
    Section("Weather") {
        if (weather == null) {
            Panel { Txt("The forecast is not available right now.", TextVariant.Small) }
            return@Section
        }
        weather.alerts.forEach { alert ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(FarmColors.amber).padding(Space.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Icon(if (alert.code == SprayAlertCode.WIND) Icons.Outlined.Air else Icons.Outlined.Thunderstorm, null, tint = FarmColors.wheatInk, modifier = Modifier.size(20.dp))
                Txt(alert.message, color = FarmColors.wheatInk, modifier = Modifier.weight(1f))
            }
        }
        Panel {
            Choice(listOf(ChoiceOption("today", "Today"), ChoiceOption("7", "7 days"), ChoiceOption("14", "14 days")), range, { range = it })
            val current = weather.current
            if (range == "today" && current != null) {
                FlowRow(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    Stat("${jsNum(jsRound(current.temperatureC))}°", "Temperature")
                    Stat("${jsNum(jsRound(current.humidityPct))}%", "Humidity")
                    Stat(number(weather.today?.precipitationMm ?: current.precipitationMm), "Rain today", unit = "mm", color = FarmColors.water)
                    Stat(jsNum(jsRound(current.windKmh)), "Wind", unit = "km/h")
                    Stat(number(weather.today?.et0Mm ?: 0.0), "Evapotranspiration", unit = "mm")
                }
            }
            if (range == "7") weather.next7.forEach { ForecastRow(it) }
            if (range == "14") weather.next14.forEach { ForecastRow(it) }
        }
    }
}

@Composable
private fun IndexBar(label: String, value: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        InfoRow(label, "$value/100")
        ProgressTrack(value.toDouble(), if (value >= 65) FarmColors.leaf else if (value >= 40) FarmColors.amber else FarmColors.rust)
    }
}

@Composable
fun SoilSection(data: ParcelIntelligence) {
    val soil = data.soil
    Section("Soil analysis") {
        Panel {
            if (soil.status != "READY") {
                Txt(soil.errorMessage ?: "The soil profile is being analysed.", TextVariant.Small)
                return@Panel
            }
            Txt(soil.label ?: "", TextVariant.Title)
            InfoRow("pH", soil.ph?.let(::jsNum) ?: "-")
            InfoRow("Clay", "${soil.clayPct?.let(::jsNum) ?: "-"}%")
            InfoRow("Sand", "${soil.sandPct?.let(::jsNum) ?: "-"}%")
            InfoRow("Silt", "${soil.siltPct?.let(::jsNum) ?: "-"}%")
            InfoRow("Organic matter", "${soil.organicMatterPct?.let(::jsNum) ?: "-"}%")
            InfoRow("Organic carbon", "${soil.organicCarbonGKg?.let(::jsNum) ?: "-"} g/kg")
            soil.indices?.let {
                IndexBar("Soil score", it.soilScore)
                IndexBar("Fertility index", it.fertilityIndex)
                IndexBar("Water retention index", it.waterRetentionIndex)
            }
            Txt("Indicative values, estimated from SoilGrids maps.", TextVariant.Small)
        }
    }
}

@Composable
fun RecommendationsSection(parcelId: String) {
    val query = rememberApi<RecommendationsResponse>("recommendations/$parcelId", "/parcels/$parcelId/recommendations")
    val data = query.data
    Section(if (data != null) "Recommended crops for ${data.year}" else "Recommended crops") {
        Panel {
            if (query.loading && data == null) CircularProgressIndicator(color = FarmColors.wheat)
            query.state.error?.takeIf { data == null }?.let { Txt(errorMessage(it), TextVariant.Small) }
            data?.recommendations?.take(4)?.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    InfoRow(item.name, "${item.score}%")
                    ProgressTrack(item.score.toDouble(), if (item.score >= 75) FarmColors.leaf else if (item.score >= 55) FarmColors.amber else FarmColors.rust)
                    Txt(item.reasons.drop(1).take(2).joinToString(" ").ifEmpty { item.reasons.firstOrNull() ?: "" }, TextVariant.Small)
                }
            }
        }
    }
}

@Composable
private fun SatelliteColumn(title: String, point: SatellitePoint?, modifier: Modifier) {
    val color = point?.let { HEALTH_COLOR[it.health.color] } ?: FarmColors.straw
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(title, TextVariant.Small)
        if (point == null) {
            Txt("No image", TextVariant.Small)
            return@Column
        }
        Txt(point.ndvi.toFixed(2), TextVariant.Figure, color = color)
        Txt(HEALTH_LABEL[point.health.color] ?: "", TextVariant.Small, color = color)
        Txt("NDRE ${point.ndre?.toFixed(2) ?: "-"}", TextVariant.Small)
        Txt("Index ${jsNum(point.healthIndex)}", TextVariant.Small)
        Txt(shortDate(point.date), TextVariant.Small)
    }
}

@Composable
fun SatelliteSection(data: ParcelIntelligence, canRefresh: Boolean) {
    val satellite = data.satellite
    val actions = rememberActions()
    var busy by remember { mutableStateOf(false) }
    Section("Satellite monitoring") {
        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                SatelliteColumn("Latest image", satellite.latest, Modifier.weight(1f))
                SatelliteColumn("7 days ago", satellite.sevenDaysAgo, Modifier.weight(1f))
                SatelliteColumn("30 days ago", satellite.thirtyDaysAgo, Modifier.weight(1f))
            }
            if (canRefresh) {
                OutlinedButton(onClick = {
                    busy = true
                    actions.run(intelligenceKey(data.header.id), "parcels", onSuccess = { busy = false }) {
                        try {
                            Api.post("/parcels/${data.header.id}/satellite/refresh", body("days" to 45), offline = false)
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Downloading…" else "Download Sentinel-2 images") }
            }
        }
    }
}

/** The parcel's active plan, in the information panel. */
@Composable
fun PlanSection(data: ParcelIntelligence, canEdit: Boolean, onConfigure: () -> Unit) {
    var view by remember { mutableStateOf("steps") }
    val doneKeys = remember(data.tasks.items) { data.tasks.items.filter { it.status == TaskStatus.DONE && it.planStepKey != null }.map { it.planStepKey!! }.toSet() }
    val saved = data.plan
    if (saved == null) {
        Section("Technology plan") {
            Panel {
                Txt("The parcel does not have a plan for next season yet.")
                if (canEdit) Button(onClick = onConfigure, modifier = Modifier.fillMaxWidth()) { Text("Create plan") }
            }
        }
        return
    }
    val plan = saved.data
    Section("Plan ${plan.cropName} ${plan.year}") {
        Choice(
            listOf(ChoiceOption("steps", "Technology plan"), ChoiceOption("calendar", "Calendar"), ChoiceOption("resources", "Water and nutrients"), ChoiceOption("report", "Report")),
            view,
            { view = it },
        )
        when (view) {
            "steps" -> Panel { PlanChecklist(plan.steps, doneKeys) }
            "calendar" -> Panel { CalendarList(stepsToCalendar(plan.steps, doneKeys)) }
            "resources" -> PlanResources(plan)
            else -> Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                PlanReport(plan.report)
                if (data.planHistory.size > 1) {
                    Panel {
                        Txt("Saved reports", TextVariant.Subtitle)
                        data.planHistory.forEach { InfoRow(shortDate(it.createdAt), "${it.cropName} ${it.year}${if (it.active) ", active" else ""}") }
                    }
                }
            }
        }
        if (canEdit) OutlinedButton(onClick = onConfigure, modifier = Modifier.fillMaxWidth()) { Text("Change the planned crop") }
    }
}

/** The parcel's real cost per hectare and profit, in the parcel panel on the map. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EconomicSummarySection(parcelId: String, onOpen: () -> Unit) {
    val year = ro.fermapp.app.screens.currentYear()
    val economics = ro.fermapp.app.screens.rememberFarmEconomics(year).data
    val tillageSettings = ro.fermapp.app.data.rememberTillageSettings()
    val item = economics?.parcels?.firstOrNull { it.parcelId == parcelId } ?: return
    val season = item.result.season
    val outlook = item.result.actual ?: item.result.expected
    val top = economicInsights(item.result, item.tillage, tillageSettings).take(2)
    Section("Economics $year") {
        Panel {
            FlowRow(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Stat(formatEuro(season.perHa, 2), "Real cost", unit = "/ha", color = FarmColors.wheat)
                Stat(formatEuro(season.total, 0), "Season total")
                Stat(
                    outlook?.let { formatEuro(it.profitPerHa, 0) } ?: "-",
                    if (item.result.actual != null) "Actual profit" else "Expected profit",
                    unit = if (outlook != null) "/ha" else null,
                    color = outlook?.let { if (it.profit >= 0) FarmColors.leaf else FarmColors.rust },
                )
            }
            if (season.total > 0) CostBreakdown(season.byCategory, season.total, item.result.areaHa) else Txt("No costs yet for this season.", TextVariant.Small)
            InsightList(top)
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Open the economic analysis") }
        }
    }
}

/** Operations finished on the parcel, newest first, with the date they were carried out. */
@Composable
fun OperationHistorySection(parcelId: String) {
    val work = rememberWork().data
    val done = work.orEmpty()
        .filter { it.parcel.id == parcelId && (it.status == RunStatus.COMPLETED || it.status == RunStatus.CANCELLED) }
        .sortedByDescending { it.startedAt }
        .take(8)
    Section("Operation history") {
        Panel {
            if (done.isEmpty()) Txt("No finished operations on this parcel yet.", TextVariant.Small)
            done.forEach { item ->
                val completed = item.status == RunStatus.COMPLETED
                val finishedAt = parseJsDate(item.startedAt) + (item.progress.elapsedSeconds + item.pausedMs / 1000).seconds
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    Box(Modifier.width(4.dp).heightIn(min = 36.dp).clip(RoundedCornerShape(2.dp)).background(IMPLEMENT_COLOR[item.type] ?: FarmColors.leaf))
                    Column(Modifier.weight(1f)) {
                        Txt("${WORK_TYPES[item.type] ?: item.type}${if (completed) "" else ", cancelled"}")
                        Txt(
                            listOfNotNull(
                                fullDate(if (completed) finishedAt.toJsIsoString() else item.startedAt),
                                item.machine?.name,
                                "${number((item.areaHa * item.progressPct) / 100)} ha",
                                hoursMinutes(item.progress.elapsedSeconds),
                            ).joinToString(", "),
                            TextVariant.Small,
                        )
                    }
                    Icon(
                        if (completed) Icons.Outlined.CheckCircle else Icons.Outlined.HighlightOff,
                        if (completed) "Completed" else "Cancelled",
                        tint = if (completed) FarmColors.leaf else FarmColors.straw,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** A photo served by the API, downloaded with the session token. */
@Composable
private fun ApiImage(parcelId: String, photoId: String, description: String) {
    var bitmap by remember(photoId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photoId) {
        bitmap = runCatching {
            val bytes = Api.http.get("${Api.apiUrl}/parcels/$parcelId/photos/$photoId/file") {
                Auth.current.session?.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }.readRawBytes()
            bytes.decodeToImageBitmap()
        }.getOrNull()
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(FarmColors.ridge)) {
        bitmap?.let { Image(it, description, Modifier.fillMaxWidth().fillMaxHeight(), contentScale = ContentScale.Crop) }
    }
}

/** Photos and field observations; the photo is uploaded together with the note. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotosSection(data: ParcelIntelligence) {
    val parcelId = data.header.id
    val actions = rememberActions()
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    val pick = rememberFilePicker(listOf("image/*")) { file ->
        uploading = true
        scope.launch {
            try {
                Api.upload("/parcels/$parcelId/photos", file.name, file.bytes, file.mimeType, if (notes.isNotBlank()) mapOf("notes" to notes.trim()) else emptyMap())
                notes = ""
                Queries.invalidate(intelligenceKey(parcelId))
            } catch (error: Throwable) {
                Toast.show("The photo was not uploaded: ${errorMessage(error)}")
            } finally {
                uploading = false
            }
        }
    }

    Section("Photos") {
        Panel {
            if (data.photos.isEmpty()) Txt("No photos for this parcel.", TextVariant.Small)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm), maxItemsInEachRow = 3) {
                data.photos.forEach { photo ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        ApiImage(parcelId, photo.id, photo.notes ?: "Photo from ${shortDate(photo.createdAt)}")
                        Txt(photo.notes ?: shortDate(photo.createdAt), TextVariant.Small, maxLines = 2)
                    }
                }
            }
            Field(notes, { notes = it }, "Photo note", singleLine = false)
            Button(onClick = pick, enabled = !uploading, modifier = Modifier.fillMaxWidth()) { Text(if (uploading) "Uploading…" else "Add photo") }
        }
    }

    Section("Field observations") {
        Panel {
            data.observations.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(item.text)
                    Txt("${item.author.fullName}, ${shortDate(item.createdAt)}", TextVariant.Small)
                }
            }
            Field(text, { text = it }, "New observation", singleLine = false)
            Button(onClick = {
                actions.run(intelligenceKey(parcelId), onSuccess = { text = "" }) { Api.post("/parcels/$parcelId/observations", body("text" to text.trim())) }
            }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save observation") }
        }
    }
}

