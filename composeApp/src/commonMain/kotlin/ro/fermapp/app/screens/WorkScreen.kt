package ro.fermapp.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Agriculture
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MenuBook
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
import ro.fermapp.app.Navigator
import ro.fermapp.app.Routes
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.Queries
import ro.fermapp.app.data.Role
import ro.fermapp.app.data.RunStatus
import ro.fermapp.app.data.SendResult
import ro.fermapp.app.data.WorkSession
import ro.fermapp.app.data.body
import ro.fermapp.app.data.canOperate
import ro.fermapp.app.data.errorMessage
import ro.fermapp.app.data.rememberActions
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.data.rememberMachines
import ro.fermapp.app.data.rememberParcels
import ro.fermapp.app.data.rememberWork
import ro.fermapp.app.data.Toast
import ro.fermapp.app.features.CatalogPrefs
import ro.fermapp.app.features.CompatibilityNotice
import ro.fermapp.app.features.ParcelPicker
import ro.fermapp.app.features.TillageBadge
import ro.fermapp.app.features.machineFuelLPerH
import ro.fermapp.app.features.toTillageMachine
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.Empty
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.IconAction
import ro.fermapp.app.ui.InfoRow
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.QueryContent
import ro.fermapp.app.ui.RunAction
import ro.fermapp.app.ui.RunCard
import ro.fermapp.app.ui.Screen
import ro.fermapp.app.ui.Section
import ro.fermapp.app.ui.Stat
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.WORK_TYPES
import ro.fermapp.app.ui.clock
import ro.fermapp.app.ui.isPositive
import ro.fermapp.app.ui.isRunActive
import ro.fermapp.app.ui.number
import ro.fermapp.app.ui.parseNumber
import ro.fermapp.app.ui.shortDate
import ro.fermapp.domain.WorkParams
import ro.fermapp.domain.checkMachineCompatibility
import ro.fermapp.domain.checkWorkCompatibility
import ro.fermapp.domain.estimateWork
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.jsNum
import ro.fermapp.domain.profile
import ro.fermapp.domain.toJsIsoString
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** Runs a pause/resume/complete action on a field operation or irrigation. */
fun RunAction.path() = when (this) {
    RunAction.PAUSE -> "pause"
    RunAction.RESUME -> "resume"
    RunAction.COMPLETE -> "complete"
}

@Composable
fun WorkScreen(navigator: Navigator, preselectedParcelId: String? = null, preselectedMachineId: String? = null, preselectedType: String? = null) {
    val auth = rememberAuth()
    val work = rememberWork()
    val machines = rememberMachines().data
    val parcels = rememberParcels().data
    val actions = rememberActions()
    val scope = rememberCoroutineScope()

    var parcelId by remember { mutableStateOf(preselectedParcelId) }
    var machineId by remember { mutableStateOf(preselectedMachineId) }
    var type by remember { mutableStateOf(preselectedType) }
    var width by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf("") }
    var efficiency by remember { mutableStateOf("80") }
    var queuedNote by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val parcel = parcels?.firstOrNull { it.id == parcelId }
    val selectedMachine = machines?.firstOrNull { it.id == machineId }
    val validEfficiency = isPositive(efficiency) && (parseNumber(efficiency) ?: 0.0) <= 100
    val inputsValid = isPositive(width) && isPositive(speed) && validEfficiency
    val estimate = if (parcel != null && inputsValid) {
        estimateWork(parcel.areaHa, WorkParams(parseNumber(width)!!, parseNumber(speed)!!, parseNumber(efficiency)!!))
    } else {
        null
    }

    val items = work.data.orEmpty()
    // A run that reached 100% is finished even before the server closes it.
    val active = items.filter { isRunActive(it) }
    val history = items.filter { it.status == RunStatus.COMPLETED || it.status == RunStatus.CANCELLED }.take(15)
    fun mayControl(item: WorkSession) = canOperate(auth.role) && (auth.role != Role.OPERATOR || item.operator.id == auth.session?.user?.id)

    Screen(
        title = "Work",
        action = {
            Row {
                IconAction(Icons.Outlined.MenuBook, "Farm catalog", { navigator.open(Routes.Catalog) })
                IconAction(Icons.Outlined.Agriculture, "Machines", { navigator.open(Routes.Machines) })
            }
        },
        onRefresh = { work.refetch() },
        refreshing = work.loading,
    ) {
        QueryContent(work) {
            if (active.isNotEmpty()) {
                Section("Active operations") {
                    active.forEach { item ->
                        RunCard(
                            item,
                            "${WORK_TYPES[item.type]}, ${item.parcel.name}",
                            "${item.machine?.name ?: "No machine"}, ${jsNum(item.capacityHaH)} ha/hour",
                            canControl = mayControl(item),
                            busy = busy,
                        ) { action ->
                            busy = true
                            actions.run("work", "dashboard", "map-overview", "machines", onSuccess = { busy = false }) {
                                try {
                                    Api.post("/work/${item.id}/${action.path()}")
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
            Section("Start an operation") {
                Panel {
                    ParcelPicker(parcelId, { parcelId = it })
                    parcel?.tillageSystem?.let { system ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            TillageBadge(system, small = true)
                            Txt("Recommended: ${system.profile.operations.joinToString(", ") { it.label }}", TextVariant.Small, modifier = Modifier.weight(1f))
                        }
                    }
                    Choice(WORK_TYPES.map { (value, label) -> ChoiceOption(value, label) }, type, { type = it }, "Operation")
                    CompatibilityNotice(checkWorkCompatibility(parcel?.tillageSystem, type))
                    Choice(machines.orEmpty().map { ChoiceOption(it.id, it.name) }, machineId, { machineId = it }, "Machine (optional)")
                    CompatibilityNotice(checkMachineCompatibility(selectedMachine?.toTillageMachine(), parcel?.tillageSystem))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Field(width, { width = it }, "Width (m)", Modifier.weight(1f), KeyboardType.Decimal)
                        Field(speed, { speed = it }, "Speed (km/h)", Modifier.weight(1f), KeyboardType.Decimal)
                        Field(efficiency, { efficiency = it }, "Efficiency (%)", Modifier.weight(1f), KeyboardType.Decimal)
                    }
                    if (estimate != null) {
                        val eta = estimate.eta.toJsIsoString()
                        Row {
                            Stat(number(estimate.capacityHaH, 2), "Capacity", unit = "ha/hour", color = FarmColors.wheat, modifier = Modifier.weight(1f))
                            Stat(number(estimate.durationHours), "Duration", unit = "hours", modifier = Modifier.weight(1f))
                            Stat(clock(eta), "ETA, ${shortDate(eta)}", modifier = Modifier.weight(1f))
                        }
                    }
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val result = Api.post(
                                        "/work",
                                        body(
                                            "parcelId" to parcelId,
                                            "machineId" to machineId,
                                            "type" to type,
                                            "workingWidthM" to parseNumber(width),
                                            "speedKmh" to parseNumber(speed),
                                            "efficiencyPct" to parseNumber(efficiency),
                                            "fuelLPerH" to machineFuelLPerH(selectedMachine, type),
                                            "fuelPricePerL" to CatalogPrefs.fuelPrice.value,
                                        ),
                                    )
                                    queuedNote = result is SendResult.Queued
                                    parcelId = null
                                    type = null
                                    Queries.invalidate("work", "dashboard", "map-overview", "machines")
                                } catch (error: Throwable) {
                                    Toast.show("The action failed: ${errorMessage(error)}")
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = parcelId != null && type != null && inputsValid && !busy,
                    ) { Text("Start operation") }
                    if (queuedNote) Txt("The operation starts on the server when the connection returns.", TextVariant.Small, color = FarmColors.wheat)
                }
            }
        }

        Section("History") {
            if (history.isEmpty()) {
                Empty(Icons.Outlined.History, "No completed operations")
            } else {
                Panel {
                    history.forEach { item ->
                        InfoRow(
                            "${shortDate(item.startedAt)}, ${WORK_TYPES[item.type]}, ${item.parcel.name}",
                            if (item.status == RunStatus.CANCELLED) "Cancelled" else "${jsNum(jsRound(item.progressPct))}%",
                        )
                    }
                }
            }
        }
    }
}
