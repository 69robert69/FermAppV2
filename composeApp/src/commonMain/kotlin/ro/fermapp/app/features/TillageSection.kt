package ro.fermapp.app.features

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.Parcel
import ro.fermapp.app.data.Queries
import ro.fermapp.app.data.RunStatus
import ro.fermapp.app.data.Task
import ro.fermapp.app.data.TaskStatus
import ro.fermapp.app.data.Toast
import ro.fermapp.app.data.body
import ro.fermapp.app.data.errorMessage
import ro.fermapp.app.data.intelligenceKey
import ro.fermapp.app.data.rememberTasks
import ro.fermapp.app.data.rememberTillageSettings
import ro.fermapp.app.data.rememberWork
import ro.fermapp.app.screens.currentYear
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.theme.hexColor
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.Section
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.WORK_TYPES
import ro.fermapp.app.ui.number
import ro.fermapp.app.ui.shortDate
import ro.fermapp.domain.AdviceTone
import ro.fermapp.domain.CropCode
import ro.fermapp.domain.SoilHealthLevel
import ro.fermapp.domain.TILLAGE_BASELINE
import ro.fermapp.domain.TILLAGE_LIST
import ro.fermapp.domain.TillageHistoryEntry
import ro.fermapp.domain.TillageSystem
import ro.fermapp.domain.WorkOperation
import ro.fermapp.domain.checkTaskCompatibility
import ro.fermapp.domain.checkWorkCompatibility
import ro.fermapp.domain.estimateParcelTillage
import ro.fermapp.domain.formatEuro
import ro.fermapp.domain.jsRoundInt
import ro.fermapp.domain.planTillageOperations
import ro.fermapp.domain.profile
import ro.fermapp.domain.soilHealthLabel
import ro.fermapp.domain.tillageRecommendations
import ro.fermapp.domain.toJsIsoString
import ro.fermapp.domain.waterRetentionMessage
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The tillage pieces of features/tillage: meter, stars, picker, timeline, conflicts and the parcel section. */

/** Soil health score as a number, a banded bar and a sentence. */
@Composable
fun SoilHealthMeter(score: Int, compact: Boolean = false) {
    val fill by animateFloatAsState(score / 100f, tween(700, delayMillis = 80))
    val reading = soilHealthLabel(score.toDouble())
    val tint = when (reading.level) {
        SoilHealthLevel.EXCELLENT, SoilHealthLevel.GOOD -> FarmColors.leaf
        SoilHealthLevel.FAIR -> FarmColors.amber
        SoilHealthLevel.POOR -> FarmColors.rust
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Txt("Soil Health Score", TextVariant.Small)
            Txt("$score/100", if (compact) TextVariant.Subtitle else TextVariant.Figure)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(FarmColors.ridge)) {
            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(tint))
            // The track is split in score bands so the position reads without colour.
            listOf(55, 70, 85).forEach { mark -> Box(Modifier.offset(x = maxWidth * (mark / 100f)).width(2.dp).fillMaxHeight().background(FarmColors.loam)) }
        }
        if (!compact) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Icon(if (reading.level == SoilHealthLevel.POOR) Icons.Outlined.WarningAmber else Icons.Outlined.Spa, null, tint = tint, modifier = Modifier.size(14.dp))
                Txt(reading.label, TextVariant.Small, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun WaterStars(value: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (1..5).forEach { index ->
            Icon(if (index <= value) Icons.Outlined.Star else Icons.Outlined.StarOutline, null, tint = if (index <= value) FarmColors.water else FarmColors.line, modifier = Modifier.size(16.dp))
        }
    }
}

/** Technology picker, one card per technology. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TillagePicker(value: TillageSystem?, onChange: (TillageSystem) -> Unit, disabled: Boolean = false) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm), maxItemsInEachRow = 2) {
        TILLAGE_LIST.forEach { profile ->
            val selected = profile.code == value
            val color = hexColor(profile.color)
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (selected) color.copy(alpha = 0.14f) else FarmColors.furrow)
                    .border(1.dp, if (selected) color else FarmColors.line, RoundedCornerShape(10.dp))
                    .clickable(enabled = !disabled) { onChange(profile.code) }
                    .padding(horizontal = Space.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Txt(profile.name, TextVariant.Small, color = FarmColors.chaff, modifier = Modifier.weight(1f), maxLines = 1)
                if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = color, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Year by year history, as a horizontal timeline. */
@Composable
fun TillageTimeline(history: List<TillageHistoryEntry>) {
    if (history.isEmpty()) {
        Txt("No history yet. The technology you set is recorded for its year.", TextVariant.Small)
        return
    }
    val thisYear = currentYear()
    Row(Modifier.fillMaxWidth()) {
        history.forEachIndexed { index, entry ->
            val profile = entry.system.profile
            val color = hexColor(profile.color)
            val future = entry.year > thisYear
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(2.dp).background(if (index == 0) Color.Transparent else FarmColors.line))
                    Box(Modifier.size(14.dp).clip(CircleShape).background(if (future) FarmColors.loam else color).border(2.dp, color, CircleShape))
                    Box(Modifier.weight(1f).height(2.dp).background(if (index == history.lastIndex) Color.Transparent else FarmColors.line))
                }
                Txt(entry.year.toString(), TextVariant.Small, color = FarmColors.chaff)
                Txt(if (future) "${profile.badge} (plan)" else profile.badge, TextVariant.Small, maxLines = 1)
            }
        }
    }
}

data class TillageConflict(val id: String, val work: Boolean, val title: String, val message: String)

/** Open tasks and running operations of the parcel that contradict its technology. */
@Composable
fun rememberTillageConflicts(parcelId: String, system: TillageSystem?): List<TillageConflict> {
    val tasks = rememberTasks().data
    val work = rememberWork().data
    if (system == null) return emptyList()
    val discouraged = system.profile.discouragedWork.keys
    val conflicts = mutableListOf<TillageConflict>()
    tasks.orEmpty().filter { it.parcel?.id == parcelId && it.status != TaskStatus.DONE }.forEach { task ->
        val check = checkTaskCompatibility(system, task.type)
        // A task titled after a discouraged operation, for example "Ploughing", is flagged too.
        val named = discouraged.firstOrNull { type -> task.title.lowercase().contains(if (type == WorkOperation.PLOWING) "plough" else type.name.lowercase().take(5)) }
        val byName = named?.let { checkWorkCompatibility(system, it.name) }
        val message = if (!check.compatible) check.message else if (byName != null && !byName.compatible) byName.message else null
        if (message != null) conflicts.add(TillageConflict(task.id, false, task.title, message))
    }
    work.orEmpty().filter { it.parcel.id == parcelId && (it.status == RunStatus.RUNNING || it.status == RunStatus.PAUSED) }.forEach { run ->
        val check = checkWorkCompatibility(system, run.type)
        if (!check.compatible && check.message != null) conflicts.add(TillageConflict(run.id, true, run.machine?.name ?: "Running operation", check.message!!))
    }
    return conflicts
}

/**
 * Creates To Do tasks for the operations the technology recommends, skipping
 * the ones already open on the parcel with the same title. Returns how many were created.
 */
suspend fun generateTillageTasks(parcelId: String, system: TillageSystem, start: Instant, existing: List<Task>): Int {
    val open = existing.filter { it.parcel?.id == parcelId && it.status != TaskStatus.DONE }.map { it.title.lowercase() }.toSet()
    val missing = planTillageOperations(system, start).filter { it.operation.label.lowercase() !in open }
    for (planned in missing) {
        Api.post(
            "/tasks",
            body("type" to planned.operation.taskType, "title" to planned.operation.label, "priority" to "MEDIUM", "dueDate" to planned.dueDate.toJsIsoString(), "parcelId" to parcelId),
        )
    }
    return missing.size
}


private fun adviceIcon(tone: AdviceTone) = when (tone) {
    AdviceTone.INFO -> Icons.Outlined.Info to FarmColors.water
    AdviceTone.WARNING -> Icons.Outlined.WarningAmber to FarmColors.amber
    AdviceTone.SUCCESS -> Icons.Outlined.CheckCircleOutline to FarmColors.leaf
}

@Composable
private fun Metric(label: String, value: String, detail: String? = null, color: Color? = null, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(FarmColors.ridge).padding(Space.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(label, TextVariant.Small)
        Txt(value, TextVariant.Subtitle, color = color)
        detail?.let { Txt(it, TextVariant.Small) }
    }
}

/** A saving reads as a plain amount; an extra cost carries a plus sign. */
private fun signed(value: Double) = "${if (value >= 0) "" else "+"}${formatEuro(abs(value), 0)}"

/** The tillage technology of a parcel and everything it drives: works, costs, diesel, soil, water and advice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TillageSection(parcel: Parcel, canEdit: Boolean, crop: CropCode? = null) {
    val settings = rememberTillageSettings()
    val thisYear = currentYear()
    var year by remember { mutableStateOf(thisYear) }
    val history = parcel.tillageHistory.orEmpty().sortedBy { it.year }
    val system = parcel.tillageSystem
    val tasks = rememberTasks().data.orEmpty()
    val conflicts = rememberTillageConflicts(parcel.id, system)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<TillageSystem?>(null) }
    val tomorrow = Clock.System.now() + 1.days

    fun refresh() = scope.launch { Queries.invalidate("parcels", "parcel/${parcel.id}", "map-overview", intelligenceKey(parcel.id), "dashboard", "tasks") }

    fun setTillage(next: TillageSystem, plan: Boolean) {
        busy = true
        scope.launch {
            try {
                Api.post("/parcels/${parcel.id}/tillage", body("system" to next, "year" to year))
                if (plan) {
                    val created = generateTillageTasks(parcel.id, next, tomorrow, tasks)
                    Toast.show(if (created > 0) "$created recommended operations added to To Do." else "All recommended operations are already planned.")
                }
                refresh()
            } catch (error: Throwable) {
                Toast.show("The action failed: ${errorMessage(error)}")
            } finally {
                busy = false
            }
        }
    }

    confirm?.let { next ->
        val profile = next.profile
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${profile.name} in $year") },
            text = { Text("${profile.description}\n\nAdd its recommended operations to To Do as well?") },
            confirmButton = { TextButton(onClick = { confirm = null; setTillage(next, true) }) { Text("Set and plan") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirm = null }) { Text("Cancel") }
                    TextButton(onClick = { confirm = null; setTillage(next, false) }) { Text("Set only") }
                }
            },
        )
    }

    val yearSystem = history.firstOrNull { it.year == year }?.system ?: if (year == thisYear) system else null
    val seasons = parcel.seasons?.size?.takeIf { it > 0 } ?: 1
    val estimate = system?.let { estimateParcelTillage(it, parcel.areaHa, settings, seasons) }
    val profile = system?.profile
    val isBaseline = system == TILLAGE_BASELINE

    Section("Tillage technology", action = { TillageBadge(system) }) {
        Panel {
            Txt(profile?.description ?: "No technology set. Choose one to get recommended operations, cost and diesel estimates and soil indicators.", TextVariant.Small)
            if (canEdit) {
                Choice(listOf(thisYear - 1, thisYear, thisYear + 1).map { ChoiceOption(it, it.toString()) }, year, { year = it }, "Season")
                TillagePicker(yearSystem, { confirm = it }, disabled = busy)
            }
        }

        if (conflicts.isNotEmpty()) {
            Panel(Modifier.border(1.dp, FarmColors.amber.copy(alpha = 0.53f), RoundedCornerShape(6.dp))) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Icon(Icons.Outlined.Warning, null, tint = FarmColors.amber, modifier = Modifier.size(18.dp))
                    Txt("${conflicts.size} ${if (conflicts.size == 1) "item contradicts" else "items contradict"} the technology", TextVariant.Subtitle)
                }
                conflicts.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Txt("${if (item.work) "Running: " else "Task: "}${item.title}")
                        Txt(item.message, TextVariant.Small)
                    }
                }
            }
        }

        if (estimate != null && profile != null) {
            Panel {
                SoilHealthMeter(estimate.soilHealth)
                Box(Modifier.fillMaxWidth().height(1.dp).background(FarmColors.line))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Txt("Water retention", TextVariant.Small)
                    WaterStars(estimate.waterRetention)
                }
                Txt(waterRetentionMessage(profile.code), TextVariant.Small)
            }

            Panel {
                Txt("Estimated economics", TextVariant.Subtitle)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm), maxItemsInEachRow = 2) {
                    Metric("Parcel cost", formatEuro(estimate.totalCost, 0), "${formatEuro(estimate.costPerHa, 0)}/ha", modifier = Modifier.weight(1f))
                    Metric(
                        "vs Conventional",
                        if (isBaseline) "Reference" else signed(estimate.costSavings),
                        if (isBaseline) null else if (estimate.costSavings >= 0) "saved" else "extra cost",
                        if (!isBaseline && estimate.costSavings > 0) FarmColors.leaf else null,
                        Modifier.weight(1f),
                    )
                    Metric("Diesel, season", "${number(estimate.seasonFuelL, 0)} l", "${number(estimate.fuelPerHa, 0)} l/ha", modifier = Modifier.weight(1f))
                    Metric("Diesel, year", "${number(estimate.annualFuelL, 0)} l", "$seasons season${if (seasons > 1) "s" else ""}", modifier = Modifier.weight(1f))
                }
                if (!isBaseline && estimate.annualFuelSavingsL > 0) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FarmColors.leaf.copy(alpha = 0.12f)).padding(Space.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        Icon(Icons.Outlined.LocalGasStation, null, tint = FarmColors.leaf, modifier = Modifier.size(18.dp))
                        Txt("Estimated savings: ${number(estimate.annualFuelSavingsL, 0)} litres of diesel and ${estimate.passesSaved} fewer passes", color = FarmColors.chaff, modifier = Modifier.weight(1f))
                    }
                }
            }

            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("Recommended operations", TextVariant.Subtitle, modifier = Modifier.weight(1f))
                    Txt("${profile.passes} pass${if (profile.passes > 1) "es" else ""}", TextVariant.Small)
                }
                profile.operations.forEachIndexed { index, operation ->
                    Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, hexColor(profile.color), CircleShape), contentAlignment = Alignment.Center) {
                            Txt((index + 1).toString(), TextVariant.Small, color = FarmColors.chaff)
                        }
                        Txt(operation.label, modifier = Modifier.weight(1f))
                        Txt(if (index == 0) "Start" else "+${operation.offsetDays} d", TextVariant.Small)
                    }
                }
                if (profile.discouragedWork.isNotEmpty()) {
                    Txt("Not recommended: ${profile.discouragedWork.keys.joinToString(", ") { WORK_TYPES[it.name] ?: it.name }}.", TextVariant.Small)
                }
                if (canEdit) {
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val created = generateTillageTasks(parcel.id, profile.code, tomorrow, tasks)
                                Toast.show(if (created > 0) "$created recommended operations added to To Do." else "All recommended operations are already planned.")
                                refresh()
                            } catch (error: Throwable) {
                                Toast.show("The action failed: ${errorMessage(error)}")
                            } finally {
                                busy = false
                            }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Plan from ${shortDate(tomorrow.toJsIsoString())}") }
                }
            }

            Panel {
                Txt("Recommendations", TextVariant.Subtitle)
                tillageRecommendations(profile.code, crop).forEach { item ->
                    val (icon, color) = adviceIcon(item.tone)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                        Txt(item.message, TextVariant.Small, color = FarmColors.chaff, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Panel {
            Txt("History", TextVariant.Subtitle)
            TillageTimeline(history)
        }
    }
}

