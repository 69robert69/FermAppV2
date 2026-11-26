package ro.fermapp.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Agriculture
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import ro.fermapp.app.data.AppJson
import ro.fermapp.app.data.Machine
import ro.fermapp.app.data.TillagePrefs
import ro.fermapp.app.data.canManage
import ro.fermapp.app.data.rememberActions
import ro.fermapp.app.data.rememberApi
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.data.rememberMachines
import ro.fermapp.app.data.rememberParcels
import ro.fermapp.app.data.rememberTeam
import ro.fermapp.app.data.rememberTillageSettings
import ro.fermapp.app.features.BarItem
import ro.fermapp.app.features.BarList
import ro.fermapp.app.features.ChartTableToggle
import ro.fermapp.app.features.CostBreakdown
import ro.fermapp.app.features.DataTable
import ro.fermapp.app.features.Donut
import ro.fermapp.app.features.DonutItem
import ro.fermapp.app.features.InsightList
import ro.fermapp.app.features.Kpi
import ro.fermapp.app.features.Series
import ro.fermapp.app.features.SeriesLegend
import ro.fermapp.app.features.SignedBars
import ro.fermapp.app.features.SignedItem
import ro.fermapp.app.features.SoilHealthMeter
import ro.fermapp.app.features.StackedColumns
import ro.fermapp.app.features.TillageBadge
import ro.fermapp.app.features.TrendLine
import ro.fermapp.app.features.TrendPoint
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.theme.hexColor
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.Empty
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.MACHINE_TYPES
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.ROLE_LABELS
import ro.fermapp.app.ui.Screen
import ro.fermapp.app.ui.Section
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.number
import ro.fermapp.app.ui.parseNumber
import ro.fermapp.domain.AdviceTone
import ro.fermapp.domain.EconomicCategory
import ro.fermapp.domain.EconomicGroup
import ro.fermapp.domain.EconomicInsight
import ro.fermapp.domain.EconomicSettings
import ro.fermapp.domain.HOURS_PER_MONTH
import ro.fermapp.domain.LaborProfile
import ro.fermapp.domain.MachineCostProfile
import ro.fermapp.domain.ParcelTillageInput
import ro.fermapp.domain.TILLAGE_LIST
import ro.fermapp.domain.TillageSystem
import ro.fermapp.domain.analyzeTillage
import ro.fermapp.domain.defaultSettings
import ro.fermapp.domain.economicInsights
import ro.fermapp.domain.formatEuro
import ro.fermapp.domain.jsNum
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.laborHourlyRate
import ro.fermapp.domain.machineHourlyCost
import ro.fermapp.domain.profile
import ro.fermapp.domain.tillageTimeline

/** Tillage analysis, the financial dashboard and the economic settings (features/tillage and features/economics). */

private val TILLAGE_SERIES = TILLAGE_LIST.map { Series(it.code.name, it.name, hexColor(it.color)) }
private const val MAX_YEARS = 6

@Composable
private fun TillageKpi(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, tone: androidx.compose.ui.graphics.Color = FarmColors.chaff, modifier: Modifier) =
    Kpi(icon, label, value, tone = tone, modifier = modifier)

/** A number field that keeps the typed text and reports the parsed value, empty meaning none. */
@Composable
private fun NumberField(label: String, value: Double?, onChange: (Double?) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var text by remember { mutableStateOf(value?.let { jsNum(it).replace('.', ',') } ?: "") }
    Field(
        text,
        { next ->
            if (!enabled) return@Field
            text = next
            val parsed = parseNumber(next)
            onChange(if (next.isBlank() || parsed == null || !parsed.isFinite()) null else parsed)
        },
        label, modifier, KeyboardType.Decimal,
    )
}

@Composable
private fun Assumptions() {
    val settings = rememberTillageSettings()
    Panel {
        Txt("Your own figures per hectare. Empty fields use the defaults shown.", TextVariant.Small)
        key(settings.isEmpty()) {
            TILLAGE_LIST.forEach { profile ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    TillageBadge(profile.code, small = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        NumberField("Cost €/ha (${jsNum(profile.costPerHa)})", settings[profile.code]?.costPerHa, { value ->
                            TillagePrefs.setValue(profile.code, costPerHa = value?.takeIf { it >= 0 })
                        }, Modifier.weight(1f))
                        NumberField("Diesel l/ha (${jsNum(profile.fuelLPerHa)})", settings[profile.code]?.fuelLPerHa, { value ->
                            TillagePrefs.setValue(profile.code, fuelLPerHa = value?.takeIf { it >= 0 })
                        }, Modifier.weight(1f))
                    }
                }
            }
        }
        TextButton(onClick = { TillagePrefs.reset() }) { Text("Restore defaults") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TillageAnalysisScreen(navigator: Navigator) {
    val auth = rememberAuth()
    val parcelsQuery = rememberParcels()
    val settings = rememberTillageSettings()
    var table by remember { mutableStateOf(false) }

    val list = remember(parcelsQuery.data) { parcelsQuery.data.orEmpty().filter { it.archivedAt == null } }
    val inputs = remember(list) { list.map { ParcelTillageInput(it.areaHa, it.tillageSystem, it.tillageHistory.orEmpty().sortedBy { entry -> entry.year }) } }
    val analysis = remember(inputs, settings) { analyzeTillage(inputs, settings) }
    val years = remember(inputs) {
        val thisYear = currentYear()
        val recorded = inputs.flatMap { input -> input.history.orEmpty().map { it.year } }
        val first = maxOf(minOf(thisYear - 2, recorded.minOrNull() ?: thisYear), thisYear - MAX_YEARS + 1)
        val last = minOf(maxOf(thisYear, recorded.maxOrNull() ?: thisYear), first + MAX_YEARS - 1)
        (first..last).toList()
    }
    val timeline = remember(inputs, years, settings) { tillageTimeline(inputs, years, settings) }
    val fuelBaseline = analysis.totalFuelL + analysis.fuelSavingsL
    val fuelReductionPct = if (fuelBaseline > 0) jsRound(analysis.fuelSavingsL / fuelBaseline * 100).toLong() else 0L
    val rows = analysis.bySystem.map { it to it.system.profile }

    Screen(title = "Tillage analysis", onRefresh = { parcelsQuery.refetch() }, refreshing = parcelsQuery.loading) {
        if (parcelsQuery.data != null && analysis.assignedAreaHa == 0.0) {
            Empty(Icons.Outlined.Agriculture, "No parcel has a tillage technology yet", "Open a parcel and choose Conventional, Minimum Till, Strip Till or No Till.")
        } else if (parcelsQuery.data != null) {
            Section("Executive summary") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm), maxItemsInEachRow = 2) {
                    val cell = Modifier.weight(1f)
                    TillageKpi(Icons.Outlined.Payments, "${formatEuro(analysis.costSavings, 0)}/yr", "Estimated savings vs conventional", if (analysis.costSavings > 0) FarmColors.leaf else FarmColors.chaff, cell)
                    TillageKpi(Icons.Outlined.LocalGasStation, "${number(analysis.fuelSavingsL, 0)} l", "Less diesel, $fuelReductionPct% lower", if (analysis.fuelSavingsL > 0) FarmColors.leaf else FarmColors.chaff, cell)
                    TillageKpi(Icons.Outlined.Agriculture, "${jsNum(analysis.passReductionPct)}%", "Fewer passes over the fields", modifier = cell)
                    TillageKpi(Icons.Outlined.WaterDrop, "${analysis.avgWaterRetention?.let { number(it, 1) } ?: "-"} / 5", "Average water retention", modifier = cell)
                }
                analysis.avgSoilHealth?.let { score ->
                    Panel {
                        Txt("Farm average, weighted by area", TextVariant.Small)
                        SoilHealthMeter(jsRound(score).toInt())
                    }
                }
            }

            Section("Distribution") {
                Panel {
                    Donut(rows.map { (row, profile) -> DonutItem(row.system.name, profile.name, hexColor(profile.color), row.areaHa) }, analysis.assignedAreaHa, "ha", "with a technology")
                    SeriesLegend(TILLAGE_SERIES, rows.associate { (row, _) -> row.system.name to "${number(row.areaHa)} ha" })
                    if (analysis.unassignedAreaHa > 0) Txt("${number(analysis.unassignedAreaHa)} ha have no technology set and are left out of the figures.", TextVariant.Small)
                }
            }

            Section("By technology", action = { ChartTableToggle(table) { table = it } }) {
                Panel {
                    if (table) {
                        DataTable(
                            listOf("Technology", "ha", "Cost €", "Diesel l"),
                            rows.map { (row, profile) -> listOf(profile.badge, number(row.areaHa), formatEuro(row.totalCost, 0), number(row.fuelL, 0)) } +
                                listOf(listOf("Total", number(analysis.assignedAreaHa), formatEuro(analysis.totalCost, 0), number(analysis.totalFuelL, 0))),
                        )
                    } else {
                        BarList(
                            rows.map { (row, profile) ->
                                BarItem(
                                    row.system.name, profile.name, hexColor(profile.color), row.areaHa,
                                    if (row.parcels > 0) "${row.parcels} parcel${if (row.parcels > 1) "s" else ""}, ${formatEuro(row.totalCost, 0)}, ${number(row.fuelL, 0)} l diesel" else "Not used",
                                )
                            },
                        ) { "${number(it)} ha" }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Txt("Total cost ${formatEuro(analysis.totalCost, 0)}", TextVariant.Small)
                        Txt("Total diesel ${number(analysis.totalFuelL, 0)} l", TextVariant.Small)
                    }
                }
            }

            Section("Evolution") {
                Panel {
                    Txt("Hectares per technology", TextVariant.Subtitle)
                    StackedColumns(years, TILLAGE_SERIES, timeline.map { point -> point.areaBySystem.mapKeys { it.key.name } }, "ha")
                }
                Panel {
                    Txt("Soil health", TextVariant.Subtitle)
                    TrendLine(timeline.map { TrendPoint(it.year, it.avgSoilHealth) }, FarmColors.leaf, 40.0 to 100.0, { jsRound(it).toLong().toString() }, "Average score, out of 100")
                }
                Panel {
                    Txt("Water retention", TextVariant.Subtitle)
                    TrendLine(timeline.map { TrendPoint(it.year, it.avgWaterRetention) }, FarmColors.water, 1.0 to 5.0, { number(it, 1) }, "Average stars, out of 5")
                }
                Txt("Years before a parcel’s first recorded technology count as without one.", TextVariant.Small)
            }

            Section("Parcels") {
                Panel {
                    list.forEach { parcel ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { navigator.open(Routes.ParcelDetail(parcel.id, parcel.name)) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Txt(parcel.name, maxLines = 1)
                                Txt("${number(parcel.areaHa)} ha", TextVariant.Small)
                            }
                            if (parcel.tillageSystem != null) TillageBadge(parcel.tillageSystem, small = true) else Txt("Not set", TextVariant.Small)
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = FarmColors.straw)
                        }
                    }
                }
            }
        }

        if (canManage(auth.role)) {
            Section("Cost and diesel assumptions") { Assumptions() }
        }
    }
}

private fun bestProfit(group: EconomicGroup) = group.actualProfitPerHa ?: group.expectedProfitPerHa

private fun profitDetail(group: EconomicGroup?) = group?.let(::bestProfit)?.let { "${formatEuro(it, 0)}/ha" }

private val CATEGORY_SHARE_LABELS = mapOf(
    EconomicCategory.SEEDS to "Seed", EconomicCategory.FERTILIZERS to "Fertiliser", EconomicCategory.CHEMICALS to "Crop protection",
    EconomicCategory.FUEL to "Diesel", EconomicCategory.MACHINERY to "Machinery", EconomicCategory.LABOR to "Labour", EconomicCategory.OTHER to "Other costs",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FinancialDashboardScreen(navigator: Navigator) {
    val auth = rememberAuth()
    val farmName = auth.session?.farm?.name ?: "This farm"
    val thisYear = currentYear()
    var year by remember { mutableStateOf(thisYear) }
    var table by remember { mutableStateOf(false) }
    val economics = rememberFarmEconomics(year)
    val tillageSettings = rememberTillageSettings()
    val magnitude = FarmColors.wheat

    val farmInsights = remember(economics.data, tillageSettings) {
        val data = economics.data ?: return@remember emptyList()
        val savings = data.parcels.flatMap { item -> economicInsights(item.result, item.tillage, tillageSettings) }.filter { it.savings != null && it.savings != 0.0 }
        val total = savings.sumOf { it.savings ?: 0.0 }
        buildList {
            if (total > 0) {
                add(EconomicInsight(AdviceTone.SUCCESS, "Moving ${savings.size} parcel${if (savings.size > 1) "s" else ""} to a lighter tillage technology could save about ${formatEuro(total, 0)} this season.", total))
            }
            val summary = data.summary
            val top = summary.byCategory.entries.sortedByDescending { it.value }.firstOrNull()
            if (top != null && summary.cost > 0) {
                add(EconomicInsight(AdviceTone.INFO, "${CATEGORY_SHARE_LABELS.getValue(top.key)} is ${jsRound(top.value / summary.cost * 100).toLong()}% of the farm’s production cost."))
            }
            val priciest = summary.byParcel.filter { it.areaHa > 0 }.maxByOrNull { it.costPerHa }
            if (priciest != null && summary.costPerHa > 0 && priciest.costPerHa > summary.costPerHa * 1.25) {
                add(
                    EconomicInsight(
                        AdviceTone.WARNING,
                        "${priciest.label} costs ${formatEuro(priciest.costPerHa, 0)}/ha, ${jsRound((priciest.costPerHa / summary.costPerHa - 1) * 100).toLong()}% above the farm average. Check its inputs and passes.",
                    ),
                )
            }
            val losses = summary.byParcel.filter { (bestProfit(it) ?: 0.0) < 0 }
            if (losses.isNotEmpty()) add(EconomicInsight(AdviceTone.WARNING, "${losses.joinToString(", ") { it.label }} ${if (losses.size > 1) "run" else "runs"} at a loss this season."))
        }
    }

    Screen(title = "Financial dashboard", onRefresh = { economics.refetch() }, refreshing = economics.loading) {
        Choice(listOf(thisYear - 1, thisYear, thisYear + 1).map { ChoiceOption(it, it.toString()) }, year, { year = it })
        val data = economics.data
        val summary = data?.summary
        if (summary != null && summary.cost <= 0) {
            Empty(Icons.Outlined.Payments, "No costs for $year yet", "Open a parcel, Economic analysis, and add seed, fertiliser and crop protection.")
        } else if (summary != null) {
            Section("Indicators") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm), maxItemsInEachRow = 2) {
                    val cell = Modifier.weight(1f)
                    Kpi(Icons.Outlined.Calculate, "Average cost per hectare", formatEuro(summary.costPerHa, 2), "${formatEuro(summary.cost, 0)} on ${number(summary.areaHa)} ha", FarmColors.wheat, cell)
                    Kpi(
                        Icons.AutoMirrored.Outlined.TrendingUp, "Average profit per hectare", summary.profitPerHa?.let { formatEuro(it, 0) } ?: "-",
                        tone = if ((summary.profitPerHa ?: 0.0) >= 0) FarmColors.leaf else FarmColors.rust, modifier = cell,
                    )
                    Kpi(Icons.Outlined.EmojiEvents, "Most profitable field", summary.mostProfitableParcel?.label ?: "-", profitDetail(summary.mostProfitableParcel), modifier = cell)
                    Kpi(Icons.Outlined.Spa, "Most profitable crop", summary.mostProfitableCrop?.label ?: "-", profitDetail(summary.mostProfitableCrop), modifier = cell)
                    Kpi(
                        Icons.Outlined.Agriculture, "Most efficient technology", summary.mostEfficientTillage?.label ?: "-",
                        summary.mostEfficientTillage?.let { "${formatEuro(it.costPerHa, 0)}/ha" } ?: "Set a technology on parcels", modifier = cell,
                    )
                    val fuel = summary.byCategory[EconomicCategory.FUEL] ?: 0.0
                    Kpi(Icons.Outlined.LocalGasStation, "Diesel cost", formatEuro(fuel, 0), if (summary.areaHa > 0) "${formatEuro(fuel / summary.areaHa, 0)}/ha" else null, modifier = cell)
                }
            }

            Section("Recommendations") { InsightList(farmInsights) }
            Section("Cost structure") { Panel { CostBreakdown(summary.byCategory, summary.cost, summary.areaHa) } }

            fun costBars(groups: List<EconomicGroup>, color: (EconomicGroup) -> androidx.compose.ui.graphics.Color = { magnitude }) =
                groups.map { BarItem(it.key, it.label, color(it), it.costPerHa, "${formatEuro(it.cost, 0)} on ${number(it.areaHa)} ha") }

            Section("Costs by crop") { Panel { BarList(costBars(summary.byCrop)) { "${formatEuro(it, 0)}/ha" } } }
            Section("Costs by parcel") { Panel { BarList(costBars(summary.byParcel)) { "${formatEuro(it, 0)}/ha" } } }
            Section("Costs by technology") {
                Panel {
                    if (summary.byTillage.isEmpty()) Txt("No parcel has a tillage technology yet.", TextVariant.Small)
                    else BarList(costBars(summary.byTillage) { group -> runCatching { hexColor(TillageSystem.valueOf(group.key).profile.color) }.getOrDefault(magnitude) }) { "${formatEuro(it, 0)}/ha" }
                }
            }
            Section("Profit by crop") { Panel { SignedBars(summary.byCrop.map { SignedItem(it.key, it.label, bestProfit(it)) }) { "${formatEuro(it, 0)}/ha" } } }
            Section("Profit by parcel") { Panel { SignedBars(summary.byParcel.map { SignedItem(it.key, it.label, bestProfit(it)) }) { "${formatEuro(it, 0)}/ha" } } }
            Section("Profit by farm") {
                Panel {
                    SignedBars(listOf(SignedItem("farm", farmName, summary.actualProfit ?: summary.expectedProfit))) { formatEuro(it, 0) }
                    Txt("Each farm keeps its own data. Switch farms from Settings to compare their results.", TextVariant.Small)
                }
            }

            Section("Technology comparison", action = { ChartTableToggle(table) { table = it } }) {
                Panel {
                    val rows = TILLAGE_LIST.map { profile -> profile to summary.byTillage.firstOrNull { it.key == profile.code.name } }
                    fun modelCost(system: TillageSystem) = tillageSettings[system]?.costPerHa ?: system.profile.costPerHa
                    fun modelFuel(system: TillageSystem) = tillageSettings[system]?.fuelLPerHa ?: system.profile.fuelLPerHa
                    if (table) {
                        DataTable(
                            listOf("Technology", "€/ha", "l/ha", "Passes", "Exp. €/ha", "Act. €/ha"),
                            rows.map { (profile, used) ->
                                listOf(
                                    profile.badge,
                                    used?.let { number(it.costPerHa, 0) } ?: "${number(modelCost(profile.code), 0)}*",
                                    used?.let { number(it.fuelLPerHa, 0) } ?: "${number(modelFuel(profile.code), 0)}*",
                                    used?.let { number(it.passes, 1) } ?: "${jsNum(profile.passes.toDouble())}*",
                                    used?.expectedProfitPerHa?.let { number(it, 0) } ?: "-",
                                    used?.actualProfitPerHa?.let { number(it, 0) } ?: "-",
                                )
                            },
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                            rows.forEach { (profile, used) ->
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                                    Box(Modifier.width(4.dp).heightIn(min = 36.dp).clip(RoundedCornerShape(2.dp)).background(hexColor(profile.color)))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Txt("${profile.name}${used?.let { ", ${number(it.areaHa)} ha" } ?: " (model, not used)"}", color = FarmColors.chaff)
                                        Txt(
                                            if (used != null) "${formatEuro(used.costPerHa, 0)}/ha, ${number(used.fuelLPerHa, 0)} l/ha diesel, ${number(used.passes, 1)} passes"
                                            else "${formatEuro(modelCost(profile.code), 0)}/ha, ${number(modelFuel(profile.code), 0)} l/ha, ${jsNum(profile.passes.toDouble())} passes",
                                            TextVariant.Small,
                                        )
                                        if (used != null) {
                                            Txt(
                                                "Expected profit ${used.expectedProfitPerHa?.let { "${formatEuro(it, 0)}/ha" } ?: "-"}, actual ${used.actualProfitPerHa?.let { "${formatEuro(it, 0)}/ha" } ?: "-"}",
                                                TextVariant.Small,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Txt("* Model values from the tillage assumptions, for technologies not used on the farm.", TextVariant.Small)
                }
            }
        }
        OutlinedButton(onClick = { navigator.open(Routes.EconomicSettings) }, modifier = Modifier.fillMaxWidth()) { Text("Diesel, labour and machine costs") }
    }
}

private val EMPTY_MACHINE = MachineCostProfile(purchasePrice = 0.0, residualPct = 20.0, depreciationYears = 10.0, annualHours = 500.0, serviceCostPerYear = 0.0, maintenanceCostPerYear = 0.0)

@Composable
fun EconomicSettingsScreen(navigator: Navigator) {
    val auth = rememberAuth()
    val editable = canManage(auth.role)
    val settings = rememberApi<EconomicSettings>("economics/settings", "/economics/settings")
    val team = rememberTeam().data.orEmpty()
    val machinesQuery = rememberMachines()
    val actions = rememberActions()
    var draft by remember { mutableStateOf(defaultSettings()) }
    var dirty by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }

    LaunchedEffect(settings.data) {
        settings.data?.let { draft = it; dirty = false; version++ }
    }
    fun change(next: (EconomicSettings) -> EconomicSettings) {
        draft = next(draft)
        dirty = true
    }
    fun setLabor(userId: String, patch: (LaborProfile) -> LaborProfile) = change { it.copy(labor = it.labor + (userId to patch(it.labor[userId] ?: LaborProfile()))) }
    fun setMachine(id: String, patch: (MachineCostProfile) -> MachineCostProfile) = change { it.copy(machines = it.machines + (id to patch(it.machines[id] ?: EMPTY_MACHINE))) }

    Screen(title = "Economic settings") {
        key(version) {
            Section("Diesel and labour") {
                Panel {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        NumberField("Diesel price (€/l)", draft.fuelPricePerL, { value -> change { it.copy(fuelPricePerL = value ?: 0.0) } }, Modifier.weight(1f), editable)
                        NumberField("Default labour (€/h)", draft.defaultLaborRate, { value -> change { it.copy(defaultLaborRate = value ?: 0.0) } }, Modifier.weight(1f), editable)
                    }
                    Txt("The default rate is used for operators without their own salary or rate.", TextVariant.Small)
                }
            }

            Section("Operators") {
                Panel {
                    team.forEach { member ->
                        val profile = draft.labor[member.userId] ?: LaborProfile()
                        val rate = laborHourlyRate(profile, draft.defaultLaborRate)
                        Column(Modifier.padding4(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Txt(member.fullName, color = FarmColors.chaff, modifier = Modifier.weight(1f))
                                Txt(ROLE_LABELS[member.role.name] ?: member.role.name, TextVariant.Small)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                NumberField("Monthly salary (€)", profile.monthlySalary, { value -> setLabor(member.userId) { it.copy(monthlySalary = value) } }, Modifier.weight(1f), editable)
                                NumberField("Hourly rate (€/h)", profile.hourlyRate, { value -> setLabor(member.userId) { it.copy(hourlyRate = value) } }, Modifier.weight(1f), editable)
                            }
                            val source = when {
                                (profile.hourlyRate ?: 0.0) > 0 -> ""
                                (profile.monthlySalary ?: 0.0) > 0 -> " (salary over ${jsNum(HOURS_PER_MONTH)} h)"
                                else -> " (default)"
                            }
                            Txt("Charged to operations at ${formatEuro(rate, 2)}/h$source", TextVariant.Small)
                        }
                    }
                }
            }

            Section("Machines") {
                Panel {
                    val machines: List<Machine> = machinesQuery.data.orEmpty()
                    if (machinesQuery.data != null && machines.isEmpty()) Txt("No machines in the fleet yet.", TextVariant.Small)
                    machines.forEach { machine ->
                        val profile = draft.machines[machine.id]
                        val cost = machineHourlyCost(profile, machine.type)
                        Column(Modifier.padding4(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Txt(machine.name, color = FarmColors.chaff, modifier = Modifier.weight(1f))
                                Txt(MACHINE_TYPES[machine.type] ?: machine.type, TextVariant.Small)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                NumberField("Purchase cost (€)", profile?.purchasePrice, { value -> setMachine(machine.id) { it.copy(purchasePrice = value ?: 0.0) } }, Modifier.weight(1f), editable)
                                NumberField("Residual value (%)", profile?.residualPct, { value -> setMachine(machine.id) { it.copy(residualPct = value ?: 20.0) } }, Modifier.weight(1f), editable)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                NumberField("Depreciation (years)", profile?.depreciationYears, { value -> setMachine(machine.id) { it.copy(depreciationYears = value ?: 10.0) } }, Modifier.weight(1f), editable)
                                NumberField("Hours per year", profile?.annualHours, { value -> setMachine(machine.id) { it.copy(annualHours = value ?: 500.0) } }, Modifier.weight(1f), editable)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                NumberField("Service (€/year)", profile?.serviceCostPerYear, { value -> setMachine(machine.id) { it.copy(serviceCostPerYear = value ?: 0.0) } }, Modifier.weight(1f), editable)
                                NumberField("Maintenance (€/year)", profile?.maintenanceCostPerYear, { value -> setMachine(machine.id) { it.copy(maintenanceCostPerYear = value ?: 0.0) } }, Modifier.weight(1f), editable)
                            }
                            Txt(
                                if (cost.estimated) "Typical ${formatEuro(cost.perHour, 0)}/h for this type until its costs are entered."
                                else "Real cost ${formatEuro(cost.perHour, 2)}/h: depreciation ${formatEuro(cost.depreciation, 2)}, service ${formatEuro(cost.service, 2)}, maintenance ${formatEuro(cost.maintenance, 2)}.",
                                TextVariant.Small,
                                color = if (cost.estimated) null else FarmColors.chaff,
                            )
                        }
                    }
                }
            }
        }

        if (editable) {
            Button(
                onClick = {
                    val values = draft
                    actions.run("economics", "report", onSuccess = { dirty = false }) {
                        Api.post("/economics/settings", AppJson.encodeToJsonElement(EconomicSettings.serializer(), values))
                    }
                },
                enabled = dirty,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (dirty) "Save and recalculate" else "Saved") }
        } else {
            Txt("Only administrators and farm managers can change these figures.", TextVariant.Small)
        }
    }
}

private fun Modifier.padding4() = this.then(Modifier.fillMaxWidth())
