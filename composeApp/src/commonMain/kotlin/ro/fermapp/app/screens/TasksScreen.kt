package ro.fermapp.app.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ro.fermapp.app.Navigator
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.Role
import ro.fermapp.app.data.Task
import ro.fermapp.app.data.TaskPriority
import ro.fermapp.app.data.TaskStatus
import ro.fermapp.app.data.body
import ro.fermapp.app.data.canPlan
import ro.fermapp.app.data.rememberActions
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.data.rememberParcels
import ro.fermapp.app.data.rememberTasks
import ro.fermapp.app.data.rememberTeam
import ro.fermapp.app.features.CalendarEntry
import ro.fermapp.app.features.CalendarList
import ro.fermapp.app.features.CompatibilityNotice
import ro.fermapp.app.features.ParcelPicker
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.Empty
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.PRIORITIES
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.QueryContent
import ro.fermapp.app.ui.Screen
import ro.fermapp.app.ui.Section
import ro.fermapp.app.ui.TASK_TYPES
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.parseRoDate
import ro.fermapp.app.ui.shortDate
import ro.fermapp.app.ui.toRoDate
import ro.fermapp.domain.checkTaskCompatibility
import ro.fermapp.domain.parseJsDate
import ro.fermapp.domain.toJsIsoString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

private enum class TaskView(val label: String) { TODO("To Do"), IN_PROGRESS("In Progress"), DONE("Done"), CALENDAR("Calendar") }

private val NEXT = mapOf(
    TaskStatus.TODO to (TaskStatus.IN_PROGRESS to "Start"),
    TaskStatus.IN_PROGRESS to (TaskStatus.DONE to "Complete"),
    TaskStatus.DONE to (TaskStatus.TODO to "Reopen"),
)

private val PRIORITY_COLOR = mapOf(
    TaskPriority.LOW to FarmColors.straw,
    TaskPriority.MEDIUM to FarmColors.water,
    TaskPriority.HIGH to FarmColors.amber,
    TaskPriority.URGENT to FarmColors.rust,
)

/** A panel with a coloured left edge, as the priority marker of the original. */
fun Modifier.leftEdge(color: Color, width: Float = 12f) = drawBehind {
    drawRect(color, topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(width, size.height))
}

@Composable
fun TaskCard(task: Task, canMove: Boolean, onMove: (TaskStatus) -> Unit) {
    val overdue = task.status != TaskStatus.DONE && parseJsDate(task.dueDate) < Clock.System.now()
    Panel(Modifier.leftEdge(PRIORITY_COLOR.getValue(task.priority))) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (task.source == "AI") {
                    Box(Modifier.border(1.dp, FarmColors.wheat, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 1.dp)) {
                        Txt("🤖 AI Generated", TextVariant.Small, color = FarmColors.wheat)
                    }
                }
                Txt(task.title, TextVariant.Subtitle)
                Txt(
                    listOfNotNull(task.parcel?.name, task.assignee?.fullName, task.machine?.name, "${PRIORITIES[task.priority.name]} priority").joinToString(", "),
                    TextVariant.Small,
                )
                val window = if (task.windowStart != null && task.windowEnd != null) "  ·  window ${shortDate(task.windowStart)}–${shortDate(task.windowEnd)}" else ""
                Txt("${if (overdue) "Overdue" else "Due"}: ${shortDate(task.dueDate)}$window", TextVariant.Small, color = if (overdue) FarmColors.rust else null)
                task.aiReason?.let { Txt(it, TextVariant.Small, color = FarmColors.chaff) }
            }
            if (canMove) {
                val (next, label) = NEXT.getValue(task.status)
                Box(Modifier.heightIn(min = 44.dp).clickable { onMove(next) }.padding(horizontal = Space.sm), contentAlignment = Alignment.Center) {
                    Txt(label, color = FarmColors.wheat)
                }
            }
        }
    }
}

@Composable
fun TasksScreen(navigator: Navigator, preselectedParcelId: String? = null) {
    val auth = rememberAuth()
    val tasks = rememberTasks()
    val team = rememberTeam()
    val parcels = rememberParcels().data
    val actions = rememberActions()
    var filter by remember { mutableStateOf(TaskView.TODO) }
    var type by remember { mutableStateOf<String?>(null) }
    var priority by remember { mutableStateOf(TaskPriority.MEDIUM) }
    var due by remember { mutableStateOf(toRoDate(Clock.System.now() + 1.days)) }
    var parcelId by remember { mutableStateOf(preselectedParcelId) }
    var assigneeId by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val taskParcel = parcels?.firstOrNull { it.id == parcelId }

    fun mayMove(task: Task) = auth.role != Role.OPERATOR || task.assignee?.id == auth.session?.user?.id

    Screen(title = "To Do", onRefresh = { tasks.refetch() }, refreshing = tasks.loading) {
        Choice(TaskView.entries.map { ChoiceOption(it, it.label) }, filter, { filter = it })
        QueryContent(tasks) { items ->
            if (filter == TaskView.CALENDAR) {
                if (items.isEmpty()) {
                    Empty(Icons.Outlined.EventBusy, "No scheduled activities")
                } else {
                    Panel { CalendarList(items.map { CalendarEntry(it.id, it.dueDate, it.title, it.parcel?.name, it.status == TaskStatus.DONE) }) }
                }
            } else {
                val visible = items.filter { it.status.name == filter.name }
                if (visible.isEmpty()) {
                    Empty(Icons.Outlined.TaskAlt, "Nothing in this column")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                        visible.forEach { task ->
                            TaskCard(task, mayMove(task)) { next ->
                                actions.run("tasks", "dashboard") { Api.post("/tasks/${task.id}/status", body("status" to next)) }
                            }
                        }
                    }
                }
            }
        }

        if (canPlan(auth.role)) {
            Section("Add a task") {
                Panel {
                    Choice(TASK_TYPES.map { (value, label) -> ChoiceOption(value, label) }, type, { type = it }, "Task")
                    Choice(TaskPriority.entries.map { ChoiceOption(it, PRIORITIES.getValue(it.name)) }, priority, { priority = it }, "Priority")
                    Field(due, { due = it }, "Deadline (DD.MM.YYYY)")
                    ParcelPicker(parcelId, { parcelId = it })
                    CompatibilityNotice(checkTaskCompatibility(taskParcel?.tillageSystem, type))
                    Choice(team.data.orEmpty().map { ChoiceOption(it.userId, it.fullName) }, assigneeId, { assigneeId = it }, "Assignee")
                    Button(
                        onClick = {
                            saving = true
                            actions.run("tasks", "dashboard", onSuccess = {
                                type = null
                                parcelId = null
                                assigneeId = null
                                saving = false
                            }) {
                                try {
                                    Api.post(
                                        "/tasks",
                                        body(
                                            "type" to type,
                                            "priority" to priority,
                                            "dueDate" to parseRoDate(due)?.toJsIsoString(),
                                            "parcelId" to parcelId,
                                            "assigneeId" to assigneeId,
                                        ),
                                    )
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = type != null && parseRoDate(due) != null && !saving,
                    ) { Text("Save task") }
                }
            }
        }
    }
}
