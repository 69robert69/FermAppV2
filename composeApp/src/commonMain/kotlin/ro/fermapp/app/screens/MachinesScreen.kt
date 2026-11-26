package ro.fermapp.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Agriculture
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ro.fermapp.app.Navigator
import ro.fermapp.app.Routes
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.Machine
import ro.fermapp.app.data.body
import ro.fermapp.app.data.canManage
import ro.fermapp.app.data.rememberActions
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.data.rememberMachines
import ro.fermapp.app.data.rememberTeam
import ro.fermapp.app.data.rememberWork
import ro.fermapp.app.features.TillageChips
import ro.fermapp.app.features.colour
import ro.fermapp.app.features.toTillageMachine
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.Empty
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.IconAction
import ro.fermapp.app.ui.MACHINE_TYPES
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.QueryContent
import ro.fermapp.app.ui.Screen
import ro.fermapp.app.ui.Section
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.isPositive
import ro.fermapp.app.ui.isRunActive
import ro.fermapp.app.ui.parseNumber
import ro.fermapp.domain.FieldState
import ro.fermapp.domain.TillageMachine
import ro.fermapp.domain.TillageSystem
import ro.fermapp.domain.jsNum
import ro.fermapp.domain.machineTillageSystems

private val BRANDS = listOf("John Deere", "Fendt", "Case IH", "New Holland", "Claas")

private fun toggled(list: List<TillageSystem>, system: TillageSystem) = if (system in list) list - system else list + system

/** The technologies a machine suits, edited in place. Its type decides them until the user changes them. */
@Composable
private fun MachineTillage(machine: Machine, editable: Boolean) {
    val actions = rememberActions()
    var saving by remember { mutableStateOf(false) }
    val current = machineTillageSystems(machine.toTillageMachine())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(if (!machine.tillageSystems.isNullOrEmpty()) "Suitable for" else "Suitable for (default for its type)", TextVariant.Small)
        TillageChips(current, { system ->
            val next = toggled(current, system)
            if (next.isNotEmpty()) {
                saving = true
                actions.run("machines", onSuccess = { saving = false }) {
                    try {
                        Api.patch("/machines/${machine.id}", body("tillageSystems" to next))
                    } finally {
                        saving = false
                    }
                }
            }
        }, disabled = !editable || saving)
    }
}

@Composable
fun MachinesScreen(navigator: Navigator) {
    val auth = rememberAuth()
    val machines = rememberMachines()
    val team = rememberTeam().data
    val work = rememberWork().data
    val actions = rememberActions()
    // A machine is busy only while its run is active; at 100% it is free for the next job.
    val busy = work.orEmpty().filter { it.machine != null && isRunActive(it) }.associate { it.machine!!.id to it.parcel.name }

    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<String?>(null) }
    var brand by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var power by remember { mutableStateOf("") }
    var consumption by remember { mutableStateOf("") }
    var operatorId by remember { mutableStateOf<String?>(null) }
    var tillage by remember { mutableStateOf<List<TillageSystem>?>(null) }
    var saving by remember { mutableStateOf(false) }
    val tillageValue = tillage ?: machineTillageSystems(TillageMachine(type ?: "TRACTOR"))
    val optionalValid = (power.isEmpty() || isPositive(power)) && (consumption.isEmpty() || isPositive(consumption))
    val manager = canManage(auth.role)

    Screen(onRefresh = { machines.refetch() }, refreshing = machines.loading) {
        if (manager) {
            OutlinedButton(onClick = { navigator.open(Routes.EconomicSettings) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Calculate, null)
                Text("  Ownership, service and labour costs")
            }
        }
        QueryContent(machines) { items ->
            if (items.isEmpty()) {
                Empty(Icons.Outlined.Agriculture, "No machines in the fleet", "Add the tractors and equipment used for field work.")
                return@QueryContent
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                listOf(true, false).forEach { working ->
                    val members = items.filter { (it.id in busy) == working }
                    if (members.isNotEmpty()) {
                        Section(if (working) "In work (${members.size})" else "Available (${members.size})") {
                            members.forEach { machine ->
                                Panel {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                                Txt(machine.name, TextVariant.Subtitle, modifier = Modifier.weight(1f))
                                                Box(Modifier.size(8.dp).clip(CircleShape).background(if (machine.id in busy) FieldState.ACTIVE.colour else FarmColors.leaf))
                                                Txt(busy[machine.id]?.let { "Working, $it" } ?: "Available", TextVariant.Small, color = FarmColors.chaff)
                                            }
                                            Txt(
                                                listOfNotNull(
                                                    MACHINE_TYPES[machine.type],
                                                    listOfNotNull(machine.brand, machine.model).joinToString(" ").ifEmpty { null },
                                                    machine.powerHp?.let { "${jsNum(it)} HP" },
                                                    machine.avgConsumptionLh?.let { "${jsNum(it)} l/hour" },
                                                    machine.operator?.fullName,
                                                ).joinToString(", "),
                                                TextVariant.Small,
                                            )
                                            MachineTillage(machine, manager)
                                        }
                                        if (manager) {
                                            IconAction(Icons.Outlined.Archive, "Archive ${machine.name}", {
                                                actions.run("machines") { Api.delete("/machines/${machine.id}") }
                                            }, tint = FarmColors.straw)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (manager) {
            Section("Add a machine") {
                Panel {
                    Field(name, { name = it }, "Name")
                    Choice(MACHINE_TYPES.map { (value, label) -> ChoiceOption(value, label) }, type, { type = it }, "Type")
                    Choice(BRANDS.map { ChoiceOption(it, it) }, brand, { brand = it }, "Brand")
                    Field(brand, { brand = it }, "Other brand")
                    Field(model, { model = it }, "Model")
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Field(power, { power = it }, "Power (HP)", Modifier.weight(1f), KeyboardType.Decimal)
                        Field(consumption, { consumption = it }, "Consumption (l/hour)", Modifier.weight(1f), KeyboardType.Decimal)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Txt(if (tillage != null) "Suitable tillage technologies" else "Suitable tillage technologies (default for the type)", TextVariant.Small)
                        TillageChips(tillageValue, { system ->
                            val next = toggled(tillageValue, system)
                            if (next.isNotEmpty()) tillage = next
                        })
                    }
                    Choice(team.orEmpty().map { ChoiceOption(it.userId, it.fullName) }, operatorId, { operatorId = it }, "Operator")
                    Button(
                        onClick = {
                            saving = true
                            actions.run("machines", onSuccess = {
                                name = ""; type = null; brand = ""; model = ""; power = ""; consumption = ""; operatorId = null; tillage = null
                            }) {
                                try {
                                    Api.post(
                                        "/machines",
                                        body(
                                            "name" to name.trim(),
                                            "type" to type,
                                            "brand" to brand.trim().ifEmpty { null },
                                            "model" to model.trim().ifEmpty { null },
                                            "powerHp" to (if (power.isNotEmpty()) parseNumber(power) else null),
                                            "avgConsumptionLh" to (if (consumption.isNotEmpty()) parseNumber(consumption) else null),
                                            "operatorId" to operatorId,
                                            "tillageSystems" to tillage,
                                        ),
                                    )
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = name.isNotBlank() && type != null && optionalValid && !saving,
                    ) { Text("Save machine") }
                }
            }
        }
    }
}
