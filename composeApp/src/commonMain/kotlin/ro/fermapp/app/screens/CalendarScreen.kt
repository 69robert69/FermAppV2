package ro.fermapp.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import ro.fermapp.app.Navigator
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.IrrigationRun
import ro.fermapp.app.data.Task
import ro.fermapp.app.data.TaskPriority
import ro.fermapp.app.data.body
import ro.fermapp.app.data.canPlan
import ro.fermapp.app.data.rememberActions
import ro.fermapp.app.data.rememberApi
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.data.rememberMachines
import ro.fermapp.app.data.rememberParcels
import ro.fermapp.app.data.rememberQuery
import ro.fermapp.app.data.rememberTasks
import ro.fermapp.app.data.rememberTeam
import ro.fermapp.app.data.rememberWork
import ro.fermapp.app.features.loadForecast
import ro.fermapp.app.features.rememberFarmCentre
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.theme.hexColor
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.IconAction
import ro.fermapp.app.ui.InfoRow
import ro.fermapp.app.ui.PRIORITIES
import ro.fermapp.app.ui.Screen
import ro.fermapp.app.ui.TASK_TYPES
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.rememberNow
import ro.fermapp.app.ui.shortDate
import ro.fermapp.app.ui.toLocal
import ro.fermapp.domain.CALENDAR_CATEGORIES
import ro.fermapp.domain.CATEGORY_ORDER
import ro.fermapp.domain.CalendarDay
import ro.fermapp.domain.CalendarPriority
import ro.fermapp.domain.CalendarRun
import ro.fermapp.domain.CalendarTask
import ro.fermapp.domain.Resource
import ro.fermapp.domain.TimelineState
import ro.fermapp.domain.WarningLevel
import ro.fermapp.domain.calendarDashboard
import ro.fermapp.domain.categoryOf
import ro.fermapp.domain.dayDiff
import ro.fermapp.domain.detectConflicts
import ro.fermapp.domain.fieldTimeline
import ro.fermapp.domain.isOpen
import ro.fermapp.domain.jsNum
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.monthGrid
import ro.fermapp.domain.seasonRange
import ro.fermapp.domain.shiftDay
import ro.fermapp.domain.weatherWarnings
import ro.fermapp.domain.weekDays
import kotlinx.datetime.toLocalDateTime

/**
 * The agricultural calendar of features/calendar, built from the server's tasks,
 * field work and irrigation, with the farm forecast and the domain's calendar rules.
 */

private enum class CalendarView(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month"), SEASON("Season") }

private val WEEKDAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val WEEKDAY_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val SEASON_MONTHS = listOf("Sep", "Oct", "Nov", "Dec", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug")
private val MONTHS_LONG = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

private fun weekdayIndex(day: String) = LocalDate.parse(day).dayOfWeek.isoDayNumber - 1

private fun dayName(day: String) = "${WEEKDAY_LONG[weekdayIndex(day)]} ${shortDate("${day}T12:00:00Z")}"

/** A task's local day, YYYY-MM-DD. */
private fun localDay(iso: String) = toLocal(iso).date.toString()

private fun Task.toCalendar() = CalendarTask(
    id, title, type, parcel?.id, CalendarPriority.valueOf(priority.name), ro.fermapp.domain.TaskStatus.valueOf(status.name),
    localDay(dueDate), assignee?.id, machine?.id, source,
)

private fun categoryColor(type: String) = hexColor(CALENDAR_CATEGORIES.getValue(categoryOf(type)).color)

@Composable
private fun WeatherMini(day: CalendarDay?) {
    if (day == null) return
    val wind = day.windMaxKmh ?: 0.0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(if (day.precipitationMm >= 2) Icons.Outlined.Umbrella else if (wind > 25) Icons.Outlined.Air else Icons.Outlined.Cloud, null, tint = FarmColors.straw, modifier = Modifier.size(14.dp))
        Txt("${jsNum(jsRound(day.tMaxC))}°/${jsNum(jsRound(day.tMinC))}°", TextVariant.Small)
        if (day.precipitationMm >= 0.5) Txt("${jsNum(jsRound(day.precipitationMm))} mm", TextVariant.Small, color = FarmColors.water)
        if (wind > 15) Txt("${jsNum(jsRound(wind))} km/h", TextVariant.Small, color = if (wind > 25) FarmColors.amber else FarmColors.straw)
    }
}

@Composable
private fun StatCard(label: String, value: Int, active: Boolean, tone: androidx.compose.ui.graphics.Color? = null, sub: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(min = 96.dp, max = 160.dp).clip(RoundedCornerShape(8.dp)).background(FarmColors.furrow)
            .then(if (active) Modifier.border(1.dp, FarmColors.wheat, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick).padding(Space.md),
    ) {
        Txt(value.toString(), TextVariant.Figure, color = tone ?: FarmColors.chaff)
        Txt(label, TextVariant.Small, color = FarmColors.chaff)
        sub?.takeIf { it.isNotEmpty() }?.let { Txt(it, TextVariant.Small, maxLines = 1) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CATEGORY_ORDER.forEach { category ->
            val style = CALENDAR_CATEGORIES.getValue(category)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(hexColor(style.color)))
                Txt(style.label, TextVariant.Small)
            }
        }
    }
}

@Composable
private fun TaskChip(task: CalendarTask, parcelName: (String?) -> String?, days: List<CalendarDay>, conflicted: Boolean, onClick: () -> Unit) {
    val warnings = weatherWarnings(task.type, task.day, days).filter { it.level == WarningLevel.WARNING }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(FarmColors.ridge).clickable(onClick = onClick)
            .alpha(if (task.status == ro.fermapp.domain.TaskStatus.DONE) 0.55f else 1f).padding(horizontal = Space.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(Modifier.width(4.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(categoryColor(task.type)))
        Column(Modifier.weight(1f)) {
            Txt(task.title, TextVariant.Small, color = FarmColors.chaff, maxLines = 1)
            Txt(listOfNotNull(parcelName(task.parcelId), PRIORITIES[task.priority.name]).joinToString(" · "), TextVariant.Small, maxLines = 1)
        }
        if (conflicted) Icon(Icons.Outlined.Warning, "Resource conflict", tint = FarmColors.rust, modifier = Modifier.size(14.dp))
        else if (warnings.isNotEmpty()) Icon(Icons.Outlined.Warning, warnings.first().title, tint = FarmColors.amber, modifier = Modifier.size(14.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(navigator: Navigator) {
    val auth = rememberAuth()
    val canEdit = canPlan(auth.role)
    val now = rememberNow(60_000)
    val today = now.toLocalDateTime(ro.fermapp.app.ui.localZone).date.toString()
    val tasksQuery = rememberTasks()
    val workQuery = rememberWork()
    val irrigation = rememberApi<List<IrrigationRun>>("irrigation", "/irrigation").data.orEmpty()
    val machines = rememberMachines().data.orEmpty()
    val team = rememberTeam().data.orEmpty()
    val parcels = rememberParcels().data.orEmpty()
    val centre = rememberFarmCentre()
    val forecast = centre?.let { (lat, lng) -> rememberQuery("weather/$lat/$lng") { loadForecast(lat, lng) }.data }
    val actions = rememberActions()

    var view by remember { mutableStateOf(CalendarView.WEEK) }
    var cursor by remember { mutableStateOf(today) }
    var open by remember { mutableStateOf<CalendarTask?>(null) }
    var card by remember { mutableStateOf<String?>(null) }
    var timelineParcel by remember { mutableStateOf<String?>(null) }

    val tasks = remember(tasksQuery.data) { tasksQuery.data.orEmpty().map { it.toCalendar() } }
    val runs = remember(workQuery.data, irrigation) {
        workQuery.data.orEmpty().map { CalendarRun(it.id, "WORK", it.type, it.parcel.id, it.status.name, localDay(it.startedAt), null, it.machine?.id, it.operator.id) } +
            irrigation.map { CalendarRun(it.id, "IRRIGATION", it.systemType.name, it.parcel.id, it.status.name, localDay(it.startedAt), null, null, null) }
    }
    val days = remember(forecast) {
        forecast?.days.orEmpty().map { day ->
            val hours = forecast!!.hours.filter { it.time.startsWith(day.date) }
            CalendarDay(day.date, day.tMinC, day.tMaxC, day.precipitationMm, hours.maxOfOrNull { it.precipitationProbabilityPct ?: 0.0 }, hours.maxOfOrNull { it.windKmh })
        }
    }
    fun parcelName(id: String?) = parcels.firstOrNull { it.id == id }?.name
    val machineResources = machines.map { Resource(it.id, it.name, it.type) }
    val memberResources = team.map { Resource(it.userId, it.fullName, it.role.name) }
    val conflicts = remember(tasks, runs, machines, team) { detectConflicts(tasks, runs, machineResources, memberResources) { parcelName(it) ?: "farm-wide" } }
    val upcoming = conflicts.filter { it.day >= today && dayDiff(today, it.day) <= 14 }
    val operators = memberResources.filter { it.type == "OPERATOR" }
    val dashboard = remember(tasks, runs, machines, team, today) { calendarDashboard(tasks, runs, machineResources, operators, today) }

    fun tasksOn(day: String) = tasks.filter { it.day == day }.sortedWith(compareBy<CalendarTask> { it.status == ro.fermapp.domain.TaskStatus.DONE }.thenBy { it.title })

    fun move(direction: Int) {
        cursor = when (view) {
            CalendarView.MONTH -> {
                val date = LocalDate.parse(cursor)
                val month = date.month.ordinal + direction
                val year = date.year + Math.floorDivCompat(month, 12)
                LocalDate(year, Math.floorModCompat(month, 12) + 1, 1).toString()
            }
            CalendarView.SEASON -> shiftDay(cursor, direction * 365)
            CalendarView.DAY -> shiftDay(cursor, direction)
            CalendarView.WEEK -> shiftDay(cursor, direction * 7)
        }
    }

    val title = when (view) {
        CalendarView.DAY -> dayName(cursor)
        CalendarView.WEEK -> weekDays(cursor).let { "${shortDate("${it.first()}T12:00:00Z")} – ${shortDate("${it.last()}T12:00:00Z")}" }
        CalendarView.MONTH -> LocalDate.parse(cursor).let { "${MONTHS_LONG[it.month.ordinal]} ${it.year}" }
        CalendarView.SEASON -> "Season ${seasonRange(cursor).label}"
    }

    @Composable
    fun DayRow(day: String, expanded: Boolean) {
        val list = tasksOn(day)
        Column(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(8.dp)).background(FarmColors.furrow)
                .then(if (day == today) Modifier.border(1.dp, FarmColors.wheat, RoundedCornerShape(8.dp)) else Modifier)
                .padding(Space.sm),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(
                    if (expanded) dayName(day) else "${WEEKDAY[weekdayIndex(day)]} ${day.takeLast(2).toInt()}",
                    TextVariant.Subtitle,
                    color = if (day == today) FarmColors.wheat else FarmColors.chaff,
                    modifier = Modifier.weight(1f).clickable { cursor = day; view = CalendarView.DAY },
                )
                WeatherMini(days.firstOrNull { it.date == day })
            }
            if (list.isEmpty()) Txt("Nothing planned", TextVariant.Small)
            list.forEach { task -> TaskChip(task, ::parcelName, days, conflicts.any { task.id in it.taskIds }) { open = task } }
        }
    }

    Screen(title = "Calendar", onRefresh = { tasksQuery.refetch(); workQuery.refetch() }, refreshing = tasksQuery.loading) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            StatCard("Today", dashboard.today.size, card == "today") { card = if (card == "today") null else "today" }
            StatCard("This week", dashboard.thisWeek.size, card == "week") { card = if (card == "week") null else "week" }
            StatCard("Delayed", dashboard.delayed.size, card == "delayed", if (dashboard.delayed.isNotEmpty()) FarmColors.rust else null) { card = if (card == "delayed") null else "delayed" }
            StatCard("Critical soon", dashboard.critical.size, card == "critical", if (dashboard.critical.isNotEmpty()) FarmColors.amber else null) { card = if (card == "critical") null else "critical" }
            StatCard("Machines free", dashboard.availableMachines.size, card == "machines", sub = dashboard.availableMachines.joinToString(", ") { it.name }) { card = if (card == "machines") null else "machines" }
            StatCard("Operators free", dashboard.availableOperators.size, card == "operators", sub = dashboard.availableOperators.joinToString(", ") { it.name }) { card = if (card == "operators") null else "operators" }
        }
        card?.let { selectedCard ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FarmColors.furrow).padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                val resources = when (selectedCard) {
                    "machines" -> dashboard.availableMachines
                    "operators" -> dashboard.availableOperators
                    else -> null
                }
                if (resources != null) {
                    if (resources.isEmpty()) Txt("Everyone is busy today.", TextVariant.Small)
                    resources.forEach { Txt("• ${it.name}", TextVariant.Small, color = FarmColors.chaff) }
                } else {
                    val list = when (selectedCard) {
                        "today" -> dashboard.today
                        "week" -> dashboard.thisWeek
                        "delayed" -> dashboard.delayed
                        else -> dashboard.critical
                    }
                    if (list.isEmpty()) Txt("Nothing here.", TextVariant.Small)
                    list.forEach { task ->
                        Row(Modifier.clickable { open = task }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(task.type)))
                            Txt("${task.title} · ${shortDate("${task.day}T12:00:00Z")}", TextVariant.Small, color = FarmColors.chaff, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (upcoming.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FarmColors.furrow).border(1.dp, FarmColors.rust.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Txt("⚠ Resource Conflict Detected", TextVariant.Subtitle, color = FarmColors.rust)
                upcoming.forEach { Txt("${shortDate("${it.day}T12:00:00Z")} · ${it.title}: ${it.reason}", TextVariant.Small, color = FarmColors.chaff) }
            }
        }

        Choice(CalendarView.entries.map { ChoiceOption(it, it.label) }, view, { view = it })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            IconAction(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous", { move(-1) })
            Txt(title, TextVariant.Subtitle, modifier = Modifier.weight(1f), align = TextAlign.Center)
            IconAction(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next", { move(1) })
            if (cursor != today) Txt("Today", TextVariant.Small, color = FarmColors.wheat, modifier = Modifier.clickable { cursor = today }.padding(Space.sm))
        }
        Legend()

        when (view) {
            CalendarView.DAY -> Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                DayRow(shiftDay(cursor, -1), false)
                DayRow(cursor, true)
                DayRow(shiftDay(cursor, 1), false)
            }
            CalendarView.WEEK -> Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) { weekDays(cursor).forEach { DayRow(it, false) } }
            CalendarView.MONTH -> Column {
                Row { WEEKDAY.forEach { Txt(it, TextVariant.Small, modifier = Modifier.weight(1f), align = TextAlign.Center) } }
                monthGrid(cursor).forEach { week ->
                    Row {
                        week.forEach { day ->
                            val list = tasksOn(day).filter(::isOpen)
                            val inMonth = day.take(7) == cursor.take(7)
                            val warn = list.any { task -> weatherWarnings(task.type, task.day, days).any { it.level == WarningLevel.WARNING } }
                            val conflict = conflicts.any { it.day == day }
                            Column(
                                Modifier.weight(1f).aspectRatio(0.87f).border(0.5.dp, FarmColors.line).background(FarmColors.furrow)
                                    .then(if (day == today) Modifier.border(1.dp, FarmColors.wheat) else Modifier)
                                    .alpha(if (inMonth) 1f else 0.35f)
                                    .clickable { cursor = day; view = CalendarView.DAY }
                                    .padding(3.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Txt(day.takeLast(2).toInt().toString(), TextVariant.Small, color = if (day == today) FarmColors.wheat else FarmColors.chaff, modifier = Modifier.weight(1f))
                                    if (conflict) Icon(Icons.Outlined.Warning, null, tint = FarmColors.rust, modifier = Modifier.size(10.dp))
                                    else if (warn) Icon(Icons.Outlined.Warning, null, tint = FarmColors.amber, modifier = Modifier.size(10.dp))
                                }
                                list.take(3).forEach { task -> Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(categoryColor(task.type))) }
                                if (list.size > 3) Txt("+${list.size - 3}", TextVariant.Small)
                            }
                        }
                    }
                }
            }
            CalendarView.SEASON -> {
                val season = seasonRange(cursor)
                val span = dayDiff(season.start, season.end).toFloat()
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Row(Modifier.padding(start = 96.dp)) { SEASON_MONTHS.forEach { Txt(it, TextVariant.Small, modifier = Modifier.weight(1f)) } }
                    parcels.forEach { parcel ->
                        val entries = fieldTimeline(parcel.id, tasks, runs, emptyList(), season.start, season.end, today)
                        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { timelineParcel = parcel.id }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Txt(parcel.name, TextVariant.Small, color = FarmColors.chaff, maxLines = 1, modifier = Modifier.width(88.dp))
                            BoxWithConstraints(Modifier.weight(1f).height(22.dp).clip(RoundedCornerShape(4.dp)).background(FarmColors.furrow)) {
                                if (today >= season.start && today <= season.end) {
                                    Box(Modifier.offset(x = maxWidth * (dayDiff(season.start, today) / span)).width(2.dp).height(22.dp).background(FarmColors.wheat))
                                }
                                entries.forEach { entry ->
                                    Box(
                                        Modifier.offset(x = maxWidth * (dayDiff(season.start, entry.day) / span) - 4.dp, y = 4.dp).size(8.dp, 14.dp).clip(RoundedCornerShape(3.dp))
                                            .background(hexColor(CALENDAR_CATEGORIES.getValue(entry.category).color).copy(alpha = if (entry.state == TimelineState.PLANNED) 0.55f else 1f))
                                            .then(if (entry.state == TimelineState.OVERDUE) Modifier.border(2.dp, FarmColors.rust, RoundedCornerShape(3.dp)) else Modifier),
                                    )
                                }
                            }
                        }
                    }
                    Txt("Tap a field to see its whole season. Faded marks are planned; outlined in red are late.", TextVariant.Small)
                }
            }
        }
    }

    open?.let { task ->
        ModalBottomSheet(onDismissRequest = { open = null }, containerColor = FarmColors.furrow) {
            Column(Modifier.padding(Space.lg).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Txt(task.title, TextVariant.Title)
                InfoRow("Type", TASK_TYPES[task.type] ?: task.type)
                InfoRow("Day", dayName(task.day))
                InfoRow("Parcel", parcelName(task.parcelId) ?: "Farm-wide")
                InfoRow("Priority", PRIORITIES[task.priority.name] ?: "")
                task.assigneeId?.let { id -> InfoRow("Operator", team.firstOrNull { it.userId == id }?.fullName ?: "") }
                task.machineId?.let { id -> InfoRow("Machine", machines.firstOrNull { it.id == id }?.name ?: "") }
                weatherWarnings(task.type, task.day, days).forEach { Txt("⚠ ${it.title}: ${it.reason}", TextVariant.Small, color = if (it.level == WarningLevel.WARNING) FarmColors.amber else FarmColors.straw) }
                conflicts.filter { task.id in it.taskIds }.forEach { Txt("⚠ ${it.title}: ${it.reason}", TextVariant.Small, color = FarmColors.rust) }
                if (canEdit && task.status != ro.fermapp.domain.TaskStatus.DONE) {
                    Choice(TaskPriority.entries.map { ChoiceOption(it.name, PRIORITIES.getValue(it.name)) }, task.priority.name, { priority ->
                        open = null
                        actions.run("tasks", "dashboard") { Api.patch("/tasks/${task.id}", body("priority" to priority)) }
                    }, "Priority")
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        listOf(-1 to "1 day earlier", 1 to "1 day later", 7 to "1 week later").forEach { (shift, label) ->
                            OutlinedButton(onClick = {
                                open = null
                                actions.run("tasks", "dashboard") { Api.patch("/tasks/${task.id}", body("dueDate" to "${shiftDay(task.day, shift)}T12:00:00.000Z")) }
                            }, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }

    timelineParcel?.let { parcelId ->
        val season = seasonRange(cursor)
        ModalBottomSheet(onDismissRequest = { timelineParcel = null }, containerColor = FarmColors.furrow) {
            Column(Modifier.padding(Space.lg).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Txt("${parcelName(parcelId) ?: ""} · season ${season.label}", TextVariant.Title)
                fieldTimeline(parcelId, tasks, runs, emptyList(), season.start, season.end, today).forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(hexColor(CALENDAR_CATEGORIES.getValue(entry.category).color)))
                        Txt(shortDate("${entry.day}T12:00:00Z"), TextVariant.Small, modifier = Modifier.width(64.dp))
                        Txt(entry.label, TextVariant.Small, color = FarmColors.chaff, modifier = Modifier.weight(1f))
                        Txt(
                            when (entry.state) {
                                TimelineState.DONE -> "Done"
                                TimelineState.RUNNING -> "Running"
                                TimelineState.OVERDUE -> "Late"
                                TimelineState.PLANNED -> "Planned"
                            },
                            TextVariant.Small,
                            color = when (entry.state) {
                                TimelineState.OVERDUE -> FarmColors.rust
                                TimelineState.DONE -> FarmColors.leaf
                                TimelineState.RUNNING -> FarmColors.wheat
                                TimelineState.PLANNED -> FarmColors.straw
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Integer floor division and modulo that also work for negative months. */
private object Math {
    fun floorDivCompat(a: Int, b: Int) = a.floorDiv(b)
    fun floorModCompat(a: Int, b: Int) = a.mod(b)
}

