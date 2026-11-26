package ro.fermapp.app.features

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Agriculture
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dangerous
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ro.fermapp.app.data.Parcel
import ro.fermapp.app.data.rememberFieldStatuses
import ro.fermapp.app.data.rememberParcels
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.theme.hexColor
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.Section
import ro.fermapp.app.ui.Stat
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.shortDate
import ro.fermapp.domain.FIELD_STATES
import ro.fermapp.domain.FieldState
import ro.fermapp.domain.FieldStatus
import ro.fermapp.domain.ParcelFieldStatus
import ro.fermapp.domain.summarizeFieldStatus

/** The field status pieces of features/fieldStatus/index.tsx. */

val FieldState.icon: ImageVector
    get() = when (this) {
        FieldState.OVERDUE -> Icons.Outlined.Dangerous
        FieldState.DUE_TODAY -> Icons.Outlined.PendingActions
        FieldState.ACTIVE -> Icons.Outlined.Agriculture
        FieldState.SCHEDULED -> Icons.Outlined.EventNote
        FieldState.COMPLETE -> Icons.Outlined.CheckCircle
    }

val FieldState.colour get() = hexColor(color)

/** State badge: icon and text in the state colour, so it reads without colour too. */
@Composable
fun FieldStatusBadge(status: FieldStatus, compact: Boolean = false) {
    val color = status.state.colour
    Row(
        Modifier.clip(RoundedCornerShape(if (compact) 10.dp else 13.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color, RoundedCornerShape(if (compact) 10.dp else 13.dp))
            .heightIn(min = if (compact) 20.dp else 26.dp)
            .padding(horizontal = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp),
    ) {
        Icon(status.state.icon, null, tint = color, modifier = Modifier.size(if (compact) 11.dp else 14.dp))
        Txt(status.badge, TextVariant.Small, color = FarmColors.chaff, maxLines = 1)
    }
}

/** Field Completion Score: share of the season's planned tasks that are done. */
@Composable
fun CompletionScore(status: FieldStatus, compact: Boolean = false) {
    val score = status.completionScore
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt("Field completion", TextVariant.Small)
            Txt(if (score == null) "No tasks" else "$score%", TextVariant.Small, color = FarmColors.chaff)
        }
        Box(Modifier.fillMaxWidth().height(if (compact) 4.dp else 8.dp).clip(RoundedCornerShape(4.dp)).background(FarmColors.ridge)) {
            Box(Modifier.fillMaxWidth((score ?: 0) / 100f).height(if (compact) 4.dp else 8.dp).background(FieldState.COMPLETE.colour))
        }
        if (!compact && score != null) Txt("${status.done} of ${status.total} planned tasks done this season", TextVariant.Small)
    }
}

/** Plan status in the parcel panel and sheet. */
@Composable
fun FieldStatusSection(status: FieldStatus?) {
    if (status == null) return
    Section("Field status") {
        Panel {
            FieldStatusBadge(status)
            val parts = listOfNotNull(
                if (status.overdue > 0) "${status.overdue} overdue" else null,
                if (status.dueToday > 0) "${status.dueToday} due today" else null,
                if (status.upcoming > 0) "${status.upcoming} in the next 7 days" else null,
            ).joinToString(", ")
            val next = status.nextDue
            Txt(
                if (status.state == FieldState.COMPLETE) {
                    if (status.later > 0 && next != null) "All planned work is complete. Next: ${next.title}, ${shortDate(next.dueDate)}." else "All planned work has been completed."
                } else {
                    parts.ifEmpty { status.state.label }
                },
                TextVariant.Small,
            )
            if (next != null && status.state != FieldState.COMPLETE) Txt("Next: ${next.title}, ${shortDate(next.dueDate)}", TextVariant.Small, color = FarmColors.chaff)
            CompletionScore(status)
        }
    }
}

/** Operations at a glance on the dashboard: finished, in progress, waiting, late and urgent parcels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OperationalSection(onOpenParcel: (Parcel) -> Unit) {
    val parcels = rememberParcels().data.orEmpty().filter { it.archivedAt == null }
    val statuses = rememberFieldStatuses()
    val items = parcels.mapNotNull { parcel -> statuses[parcel.id]?.let { ParcelFieldStatus(parcel.id, parcel.name, it) } }
    if (items.isEmpty()) return
    val summary = summarizeFieldStatus(items)
    val byId = parcels.associateBy { it.id }
    val counts = FIELD_STATES.associateWith { state -> items.count { it.status.state == state } }

    Section("Field operations") {
        Panel {
            // Parcels by state, in the validated order, with a 2px gap and a legend below.
            Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(FarmColors.ridge), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                FIELD_STATES.filter { counts.getValue(it) > 0 }.forEach { state ->
                    Box(Modifier.weight(counts.getValue(state).toFloat()).height(12.dp).background(state.colour))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FIELD_STATES.forEach { state ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(state.icon, null, tint = state.colour, modifier = Modifier.size(13.dp))
                        Txt("${state.label} ${counts.getValue(state)}", TextVariant.Small)
                    }
                }
            }
            FlowRow(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Stat(summary.complete.toString(), "Fully completed", color = FieldState.COMPLETE.colour)
                Stat(summary.active.toString(), "In progress", color = FieldState.ACTIVE.colour)
                Stat(summary.pending.toString(), "With work pending")
                Stat(summary.overdue.toString(), "With overdue work", color = if (summary.overdue > 0) FieldState.OVERDUE.colour else null)
                Stat(summary.next7Days.toString(), "Tasks in the next 7 days")
                Stat(summary.averageScore?.let { "$it%" } ?: "-", "Average field completion")
            }
        }
        if (summary.urgent.isNotEmpty()) {
            Panel(Modifier.border(1.dp, FieldState.OVERDUE.colour.copy(alpha = 0.53f), RoundedCornerShape(6.dp))) {
                Txt("Needs attention now", TextVariant.Subtitle)
                summary.urgent.take(5).forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { byId[item.parcelId]?.let(onOpenParcel) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        Txt(item.name, color = FarmColors.chaff, modifier = Modifier.weight(1f), maxLines = 1)
                        FieldStatusBadge(item.status, compact = true)
                    }
                }
            }
        }
        if (summary.mostAdvanced.isNotEmpty()) {
            Panel {
                Txt("Most advanced parcels", TextVariant.Subtitle)
                summary.mostAdvanced.forEach { item ->
                    val status = statuses.getValue(item.parcelId)
                    Column(Modifier.fillMaxWidth().clickable { byId[item.parcelId]?.let(onOpenParcel) }.padding(vertical = Space.xs), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Txt(item.name, color = FarmColors.chaff, maxLines = 1)
                        CompletionScore(status, compact = true)
                    }
                }
            }
        }
    }
}
