package ro.fermapp.app.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ro.fermapp.app.Navigator
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.IrrigationRun
import ro.fermapp.app.data.RunStatus
import ro.fermapp.app.data.body
import ro.fermapp.app.data.canOperate
import ro.fermapp.app.data.rememberActions
import ro.fermapp.app.data.rememberApi
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.features.ParcelPicker
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.Empty
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.IRRIGATION_TYPES
import ro.fermapp.app.ui.InfoRow
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.ProgressTrack
import ro.fermapp.app.ui.QueryContent
import ro.fermapp.app.ui.RunCard
import ro.fermapp.app.ui.Screen
import ro.fermapp.app.ui.Section
import ro.fermapp.app.ui.Stat
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.clock
import ro.fermapp.app.ui.hoursMinutes
import ro.fermapp.app.ui.isPositive
import ro.fermapp.app.ui.liveProgress
import ro.fermapp.app.ui.number
import ro.fermapp.app.ui.parseNumber
import ro.fermapp.app.ui.shortDate
import ro.fermapp.domain.estimateIrrigation
import ro.fermapp.domain.jsNum
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.toJsIsoString
import kotlin.math.floor

/** The irrigation system chosen on the map. */
@Composable
private fun FocusedRun(run: IrrigationRun, label: String) {
    val progress = liveProgress(run)
    val paused = run.status == RunStatus.PAUSED
    Panel(Modifier.border(1.dp, FarmColors.water, RoundedCornerShape(6.dp))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Column(Modifier.weight(1f)) {
                Txt(label, TextVariant.Subtitle)
                Txt(run.parcel.name, TextVariant.Small)
            }
            Txt("${floor(progress.percent).toInt()}%", TextVariant.Figure, color = FarmColors.water)
        }
        ProgressTrack(progress.percent, if (paused) FarmColors.straw else FarmColors.water)
        InfoRow("Equipment type", IRRIGATION_TYPES[run.systemType.name] ?: "")
        InfoRow("Coverage", "${floor(progress.percent).toInt()}%")
        InfoRow("Travel speed", "${number(run.speedMPerH, 0)} m/hour")
        InfoRow("Time left", hoursMinutes(progress.remainingSeconds))
        InfoRow("Completion ETA", if (paused) "Paused" else progress.eta?.let(::clock) ?: "-")
    }
}

@Composable
fun IrrigationScreen(navigator: Navigator, preselectedParcelId: String? = null, focusedRunId: String? = null, focusedLabel: String? = null) {
    val auth = rememberAuth()
    val runs = rememberApi<List<IrrigationRun>>("irrigation", "/irrigation")
    val actions = rememberActions()
    var parcelId by remember { mutableStateOf(preselectedParcelId) }
    var system by remember { mutableStateOf<String?>(null) }
    var length by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val valid = isPositive(length) && isPositive(speed) && (dose.isEmpty() || isPositive(dose))
    val estimate = if (isPositive(length) && isPositive(speed)) estimateIrrigation(parseNumber(length)!!, parseNumber(speed)!!) else null

    val items = runs.data.orEmpty()
    val active = items.filter { it.status == RunStatus.RUNNING || it.status == RunStatus.PAUSED }
    val history = items.filter { it.status == RunStatus.COMPLETED || it.status == RunStatus.CANCELLED }.take(15)
    val focused = active.firstOrNull { it.id == focusedRunId }

    Screen(title = "Irrigation", onRefresh = { runs.refetch() }, refreshing = runs.loading) {
        if (focused != null) FocusedRun(focused, focusedLabel ?: IRRIGATION_TYPES.getValue(focused.systemType.name))
        QueryContent(runs) {
            if (active.isNotEmpty()) {
                Section("Active irrigations") {
                    active.forEach { run ->
                        RunCard(
                            run,
                            run.parcel.name,
                            "${IRRIGATION_TYPES[run.systemType.name]}, ${number(run.routeLengthM, 0)} m at ${number(run.speedMPerH, 0)} m/hour",
                            color = FarmColors.water,
                            canControl = canOperate(auth.role),
                            busy = busy,
                        ) { action ->
                            busy = true
                            actions.run("irrigation", "dashboard", "map-overview", onSuccess = { busy = false }) {
                                try {
                                    Api.post("/irrigation/${run.id}/${action.path()}")
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }
                }
            }
        }

        if (canOperate(auth.role)) {
            Section("Start an irrigation") {
                Panel {
                    ParcelPicker(parcelId, { parcelId = it })
                    Choice(IRRIGATION_TYPES.map { (value, label) -> ChoiceOption(value, label) }, system, { system = it }, "System")
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Field(length, { length = it }, "Route (m)", Modifier.weight(1f), KeyboardType.Decimal)
                        Field(speed, { speed = it }, "Speed (m/hour)", Modifier.weight(1f), KeyboardType.Decimal)
                    }
                    Field(dose, { dose = it }, "Applied dose (mm, optional)", keyboard = KeyboardType.Decimal)
                    if (estimate != null) {
                        val eta = estimate.eta.toJsIsoString()
                        Row {
                            Stat(number(estimate.durationHours), "Total duration", unit = "hours", color = FarmColors.water, modifier = Modifier.weight(1f))
                            Stat(clock(eta), "ETA, ${shortDate(eta)}", modifier = Modifier.weight(1f))
                        }
                    }
                    Button(
                        onClick = {
                            busy = true
                            actions.run("irrigation", "dashboard", "map-overview", onSuccess = {
                                parcelId = null
                                length = ""
                                speed = ""
                                dose = ""
                            }) {
                                try {
                                    Api.post(
                                        "/irrigation",
                                        body(
                                            "parcelId" to parcelId,
                                            "systemType" to system,
                                            "routeLengthM" to parseNumber(length),
                                            "speedMPerH" to parseNumber(speed),
                                            "doseMm" to (if (dose.isNotEmpty()) parseNumber(dose) else null),
                                        ),
                                    )
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = parcelId != null && system != null && valid && !busy,
                    ) { Text("Start irrigation") }
                }
            }
        }

        Section("History") {
            if (history.isEmpty()) {
                Empty(Icons.Outlined.FormatColorReset, "No completed irrigations")
            } else {
                Panel {
                    history.forEach { run ->
                        InfoRow("${shortDate(run.startedAt)}, ${run.parcel.name}", if (run.status == RunStatus.CANCELLED) "Cancelled" else "${jsNum(jsRound(run.progressPct))}%")
                    }
                }
            }
        }
    }
}
