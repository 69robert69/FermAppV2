package ro.fermapp.app.features

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.AppJson
import ro.fermapp.app.data.canPlan
import ro.fermapp.app.data.rememberActions
import ro.fermapp.app.data.rememberApi
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.data.rememberTillageSettings
import ro.fermapp.app.screens.currentYear
import ro.fermapp.app.screens.rememberFarmEconomics
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.Empty
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.InfoRow
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.QueryContent
import ro.fermapp.app.ui.Section
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.number
import ro.fermapp.app.ui.parseNumber
import ro.fermapp.domain.CHEMICAL_PRESETS
import ro.fermapp.domain.ChemicalKind
import ro.fermapp.domain.ChemicalLine
import ro.fermapp.domain.DoseUnit
import ro.fermapp.domain.EconomicSheet
import ro.fermapp.domain.FERTILIZER_PRESETS
import ro.fermapp.domain.FertilizerLine
import ro.fermapp.domain.MiscBasis
import ro.fermapp.domain.MiscLine
import ro.fermapp.domain.OperationCost
import ro.fermapp.domain.ProfitResult
import ro.fermapp.domain.SEED_CATEGORIES
import ro.fermapp.domain.SEED_DEFAULTS
import ro.fermapp.domain.SEED_SUPPLIERS
import ro.fermapp.domain.SeedDefaults
import ro.fermapp.domain.SeedLine
import ro.fermapp.domain.SeedUnit
import ro.fermapp.domain.chemicalCost
import ro.fermapp.domain.economicInsights
import ro.fermapp.domain.fertilizerCost
import ro.fermapp.domain.formatEuro
import ro.fermapp.domain.jsRoundInt
import ro.fermapp.domain.miscCost
import ro.fermapp.domain.seedCost
import kotlin.random.Random
import kotlin.time.Clock

@Serializable
data class SheetResponse(val parcelId: String, val year: Int, val sheet: EconomicSheet)

fun newLineId(): String = Clock.System.now().toEpochMilliseconds().toString(36) + Random.nextLong(1_000_000).toString(36)

/** A number field that keeps what the user types and reports the parsed value. */
@Composable
private fun NumField(label: String, value: Double?, onChange: (Double?) -> Unit, modifier: Modifier, disabled: Boolean = false) {
    var text by remember { mutableStateOf(value?.let { ro.fermapp.domain.jsNum(it).replace('.', ',') } ?: "") }
    Field(text, { next ->
        if (disabled) return@Field
        text = next
        onChange(if (next.isBlank()) null else parseNumber(next))
    }, label, modifier, KeyboardType.Decimal)
}

@Composable
private fun LineCard(title: String, subtitle: String, main: String, detail: String, editable: Boolean, onRemove: () -> Unit, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(title.isEmpty()) }
    Column(Modifier.fillMaxWidth().border(1.dp, FarmColors.line, RoundedCornerShape(10.dp))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { open = !open }.padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Column(Modifier.weight(1f)) {
                Txt(title.ifEmpty { "New line" }, color = FarmColors.chaff, maxLines = 1)
                Txt(subtitle, TextVariant.Small, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Txt(main, color = FarmColors.chaff)
                if (detail.isNotEmpty()) Txt(detail, TextVariant.Small)
            }
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = FarmColors.straw)
        }
        if (open) {
            Column(Modifier.padding(start = Space.md, end = Space.md, bottom = Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                content()
                if (editable) TextButton(onClick = onRemove) { Text("Remove", color = FarmColors.rust) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetChips(labels: List<String>, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        labels.forEachIndexed { index, label ->
            Row(
                Modifier.heightIn(min = 34.dp).border(1.dp, FarmColors.line, RoundedCornerShape(17.dp)).clickable { onPick(index) }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Outlined.Add, null, tint = FarmColors.wheat, modifier = Modifier.size(14.dp))
                Txt(label, TextVariant.Small, color = FarmColors.chaff)
            }
        }
    }
}

@Composable
private fun ProfitPanel(title: String, result: ProfitResult?) {
    if (result == null) return
    val positive = result.profit >= 0
    val tone = if (positive) FarmColors.leaf else FarmColors.rust
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Txt(title, TextVariant.Subtitle)
        InfoRow("Yield", "${number(result.yieldTHa)} t/ha at ${formatEuro(result.pricePerT)}/t")
        InfoRow("Gross revenue", "${formatEuro(result.revenue, 0)} (${formatEuro(result.revenuePerHa, 0)}/ha)")
        InfoRow("Gross profit", formatEuro(result.profit, 0), tone)
        InfoRow("Profit per hectare", "${formatEuro(result.profitPerHa, 2)}/ha", tone)
        InfoRow("Profit margin", result.marginPct?.let { "${number(it)}%" } ?: "-")
    }
}

@Composable
private fun OperationRow(op: OperationCost) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Txt(op.label, color = FarmColors.chaff)
                if (op.projected) ro.fermapp.app.ui.Badge("Estimated", FarmColors.water.copy(alpha = 0.2f), FarmColors.water)
            }
            Txt(listOf(op.machineName ?: if (op.projected) "Typical machine" else "No machine", "${number(op.areaHa)} ha", "${number(op.hours)} h", "${number(op.fuelL, 0)} l diesel").joinToString(", "), TextVariant.Small)
            Txt("Diesel ${formatEuro(op.fuelCost, 0)}, machine ${formatEuro(op.machineCost, 0)}, labour ${formatEuro(op.laborCost, 0)}", TextVariant.Small)
        }
        Column(horizontalAlignment = Alignment.End) {
            Txt(formatEuro(op.total, 0), color = FarmColors.chaff)
            Txt("${formatEuro(op.costPerHa, 2)}/ha", TextVariant.Small)
        }
    }
}

@Composable
private fun Inputs(content: @Composable RowScope.() -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), content = content)

/** "Economic analysis" of a parcel: real cost per hectare, its structure, profit and advice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParcelEconomicsTab(parcelId: String) {
    val auth = rememberAuth()
    val editable = canPlan(auth.role)
    val thisYear = currentYear()
    var year by remember { mutableStateOf(thisYear) }
    var view by remember { mutableStateOf("season") }
    val farm = rememberFarmEconomics(year)
    val stored = rememberApi<SheetResponse>("economics/sheet/$parcelId/$year", "/economics/sheets/$parcelId/$year")
    var draft by remember(year) { mutableStateOf(EconomicSheet()) }
    var dirty by remember(year) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val actions = rememberActions()
    val tillageSettings = rememberTillageSettings()

    LaunchedEffect(stored.data) {
        stored.data?.let {
            draft = it.sheet
            dirty = false
        }
    }

    fun update(change: (EconomicSheet) -> EconomicSheet) {
        draft = change(draft)
        dirty = true
    }

    QueryContent(farm) { data ->
        val item = data.parcels.firstOrNull { it.parcelId == parcelId }
        if (item == null) {
            Empty(Icons.Outlined.Calculate, "This parcel is archived or not found")
            return@QueryContent
        }
        val areaHa = item.result.areaHa
        val crop = item.crop
        val totals = if (view == "season") item.result.season else item.result.toDate
        val outlook = item.result.actual ?: item.result.expected
        val insights = economicInsights(item.result, item.tillage, tillageSettings)
        Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
            Choice(listOf(thisYear - 1, thisYear, thisYear + 1).map { ChoiceOption(it, it.toString()) }, year, { year = it })

            Section("Real cost per hectare") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm), maxItemsInEachRow = 2) {
                    Kpi(Icons.Outlined.CropSquare, "Area", "${number(areaHa)} ha", item.cropName ?: "No crop", modifier = Modifier.weight(1f))
                    Kpi(Icons.Outlined.Payments, if (view == "season") "Season total" else "Spent so far", formatEuro(totals.total, 0), modifier = Modifier.weight(1f))
                    Kpi(Icons.Outlined.Calculate, "Cost per hectare", formatEuro(totals.perHa, 2), tone = FarmColors.wheat, modifier = Modifier.weight(1f))
                    val profit = outlook?.profit ?: 0.0
                    Kpi(
                        if (profit >= 0) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown,
                        if (item.result.actual != null) "Actual profit/ha" else "Expected profit/ha",
                        outlook?.let { formatEuro(it.profitPerHa, 0) } ?: "-",
                        tone = if (profit >= 0) FarmColors.leaf else FarmColors.rust,
                        modifier = Modifier.weight(1f),
                    )
                }
                Panel {
                    Choice(listOf(ChoiceOption("season", "Full season"), ChoiceOption("toDate", "Spent so far")), view, { view = it })
                    Txt(
                        if (view == "season") "Inputs, finished work, recorded expenses and the operations still to come." else "Inputs, finished work and recorded expenses only.",
                        TextVariant.Small,
                    )
                    CostBreakdown(totals.byCategory, totals.total, areaHa)
                    if (item.result.recordedTotal > 0) Txt("Includes ${formatEuro(item.result.recordedTotal, 0)} of expenses recorded in the Expenses tab.", TextVariant.Small)
                }
            }

            Section("Recommendations") { InsightList(insights) }

            Section("Profitability") {
                Panel {
                    Inputs {
                        NumField("Expected yield (t/ha)", draft.revenue.expectedYieldTHa, { v -> update { it.copy(revenue = it.revenue.copy(expectedYieldTHa = v)) } }, Modifier.weight(1f), !editable)
                        NumField("Actual yield (t/ha)", draft.revenue.actualYieldTHa, { v -> update { it.copy(revenue = it.revenue.copy(actualYieldTHa = v)) } }, Modifier.weight(1f), !editable)
                    }
                    Inputs {
                        NumField("Sale price (€/t)", draft.revenue.pricePerT, { v -> update { it.copy(revenue = it.revenue.copy(pricePerT = v)) } }, Modifier.weight(1f), !editable)
                        NumField("Price obtained (€/t)", draft.revenue.actualPricePerT, { v -> update { it.copy(revenue = it.revenue.copy(actualPricePerT = v)) } }, Modifier.weight(1f), !editable)
                    }
                    ProfitPanel("Expected", item.result.expected)
                    ProfitPanel("Actual", item.result.actual)
                }
            }

            Section("Seeds") {
                Panel {
                    draft.seeds.forEach { line ->
                        val cost = seedCost(line, areaHa)
                        fun set(change: (SeedLine) -> SeedLine) = update { sheet -> sheet.copy(seeds = sheet.seeds.map { if (it.id == line.id) change(it) else it }) }
                        LineCard(
                            listOf(line.supplier, line.hybrid).filter { it.isNotEmpty() }.joinToString(" "),
                            "${number(line.ratePerHa, 0)} ${if (line.unit == SeedUnit.SEEDS) "seeds" else "kg"}/ha, ${number(cost.bagsNeeded)} bags needed",
                            "${formatEuro(cost.costPerHa, 2)}/ha",
                            formatEuro(cost.total, 0),
                            editable,
                            { update { sheet -> sheet.copy(seeds = sheet.seeds.filter { it.id != line.id }) } },
                        ) {
                            val suppliers = crop?.let { SEED_SUPPLIERS[it] } ?: listOf("Pioneer", "Dekalb", "KWS", "Syngenta", "Corteva", "LG")
                            Choice(suppliers.map { ChoiceOption(it, it) }, line.supplier, { s -> set { it.copy(supplier = s) } })
                            Inputs {
                                Field(line.supplier, { s -> set { it.copy(supplier = s) } }, "Supplier", Modifier.weight(1f))
                                Field(line.hybrid, { h -> set { it.copy(hybrid = h) } }, "Hybrid / variety", Modifier.weight(1f))
                            }
                            Choice(listOf(ChoiceOption(SeedUnit.SEEDS, "Seeds per bag"), ChoiceOption(SeedUnit.KG, "Kilograms")), line.unit, { u -> set { it.copy(unit = u) } }, "Sold by")
                            if (line.unit == SeedUnit.KG) Choice(SEED_CATEGORIES.map { ChoiceOption(it, it) }, line.category, { c -> set { it.copy(category = c) } }, "Seed category")
                            Inputs {
                                NumField(if (line.unit == SeedUnit.SEEDS) "Seeds per bag" else "kg per bag", line.bagSize, { v -> set { it.copy(bagSize = v ?: 1.0) } }, Modifier.weight(1f), !editable)
                                NumField(if (line.unit == SeedUnit.SEEDS) "Seeding rate (seeds/ha)" else "Seeding rate (kg/ha)", line.ratePerHa, { v -> set { it.copy(ratePerHa = v ?: 0.0) } }, Modifier.weight(1f), !editable)
                            }
                            Inputs {
                                NumField("Cost per bag (€)", line.pricePerBag, { v -> set { it.copy(pricePerBag = v ?: 0.0) } }, Modifier.weight(1f), !editable)
                                NumField("Bags used", line.bagsUsed, { v -> set { it.copy(bagsUsed = v) } }, Modifier.weight(1f), !editable)
                            }
                            Txt(
                                (if (line.unit == SeedUnit.KG) "${formatEuro(cost.costPerKg, 2)}/kg" else "${formatEuro(cost.costPer1000Seeds, 2)} per 1,000 seeds") + ", ${formatEuro(cost.costPerHa, 2)}/ha",
                                TextVariant.Small,
                            )
                        }
                    }
                    if (editable) {
                        OutlinedButton(onClick = {
                            val defaults = crop?.let { SEED_DEFAULTS[it] } ?: SeedDefaults(SeedUnit.SEEDS, 80_000.0, 70_000.0, 180.0)
                            update { it.copy(seeds = it.seeds + SeedLine(newLineId(), "", "", null, defaults.unit, defaults.bagSize, defaults.ratePerHa, defaults.pricePerBag, null)) }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Add seed") }
                    }
                }
            }

            Section("Fertilisers") {
                Panel {
                    draft.fertilizers.forEach { line ->
                        val cost = fertilizerCost(line, areaHa)
                        fun set(change: (FertilizerLine) -> FertilizerLine) = update { sheet -> sheet.copy(fertilizers = sheet.fertilizers.map { if (it.id == line.id) change(it) else it }) }
                        val passes = line.applications ?: 1.0
                        LineCard(
                            line.product,
                            "${number(line.ratePerHaKg, 0)} kg/ha${if (passes > 1) " × ${number(passes, 0)}" else ""}, N ${cost.nutrients.n} P ${cost.nutrients.p} K ${cost.nutrients.k} kg/ha",
                            "${formatEuro(cost.costPerHa, 2)}/ha",
                            formatEuro(cost.total, 0),
                            editable,
                            { update { sheet -> sheet.copy(fertilizers = sheet.fertilizers.filter { it.id != line.id }) } },
                        ) {
                            Field(line.product, { p -> set { it.copy(product = p) } }, "Product")
                            Inputs {
                                NumField("Dose (kg/ha)", line.ratePerHaKg, { v -> set { it.copy(ratePerHaKg = v ?: 0.0) } }, Modifier.weight(1f), !editable)
                                NumField("Price (€/t)", line.pricePerT, { v -> set { it.copy(pricePerT = v ?: 0.0) } }, Modifier.weight(1f), !editable)
                                NumField("Passes", passes, { v -> set { it.copy(applications = maxOf(1, jsRoundInt(v ?: 1.0)).toDouble()) } }, Modifier.weight(1f), !editable)
                            }
                            Txt("${formatEuro(cost.pricePerKg, 3)}/kg, ${formatEuro(cost.costPerHa, 2)}/ha, ${formatEuro(cost.total, 0)} for the parcel", TextVariant.Small)
                        }
                    }
                    if (editable) {
                        Txt("Add a product", TextVariant.Small)
                        PresetChips(FERTILIZER_PRESETS.map { it.product }) { index ->
                            update { it.copy(fertilizers = it.fertilizers + FERTILIZER_PRESETS[index].copy(id = newLineId(), applications = 1.0)) }
                        }
                    }
                }
            }

            Section("Crop protection") {
                Panel {
                    draft.chemicals.forEach { line ->
                        val cost = chemicalCost(line, areaHa)
                        fun set(change: (ChemicalLine) -> ChemicalLine) = update { sheet -> sheet.copy(chemicals = sheet.chemicals.map { if (it.id == line.id) change(it) else it }) }
                        val unit = if (line.unit == DoseUnit.L) "l" else "kg"
                        val passes = line.applications ?: 1.0
                        LineCard(
                            line.product,
                            "${line.kind.label}, ${number(line.dosePerHa, 2)} $unit/ha${if (passes > 1) " × ${number(passes, 0)}" else ""}",
                            "${formatEuro(cost.costPerHa, 2)}/ha",
                            formatEuro(cost.total, 0),
                            editable,
                            { update { sheet -> sheet.copy(chemicals = sheet.chemicals.filter { it.id != line.id }) } },
                        ) {
                            Field(line.product, { p -> set { it.copy(product = p) } }, "Product")
                            Choice(ChemicalKind.entries.map { ChoiceOption(it, it.label) }, line.kind, { k -> set { it.copy(kind = k) } })
                            Choice(listOf(ChoiceOption(DoseUnit.L, "Litres"), ChoiceOption(DoseUnit.KG, "Kilograms")), line.unit, { u -> set { it.copy(unit = u) } })
                            Inputs {
                                NumField("Dose ($unit/ha)", line.dosePerHa, { v -> set { it.copy(dosePerHa = v ?: 0.0) } }, Modifier.weight(1f), !editable)
                                NumField("Price (€/$unit)", line.pricePerUnit, { v -> set { it.copy(pricePerUnit = v ?: 0.0) } }, Modifier.weight(1f), !editable)
                                NumField("Treatments", passes, { v -> set { it.copy(applications = maxOf(1, jsRoundInt(v ?: 1.0)).toDouble()) } }, Modifier.weight(1f), !editable)
                            }
                            Txt("${number(cost.quantity)} $unit for the campaign, ${formatEuro(cost.total, 0)}", TextVariant.Small)
                        }
                    }
                    if (draft.chemicals.isNotEmpty()) InfoRow("Treatments, whole campaign", formatEuro(draft.chemicals.sumOf { chemicalCost(it, areaHa).total }, 0))
                    if (editable) {
                        Txt("Add a product", TextVariant.Small)
                        PresetChips(CHEMICAL_PRESETS.map { it.product }) { index ->
                            update { it.copy(chemicals = it.chemicals + CHEMICAL_PRESETS[index].copy(id = newLineId(), applications = 1.0)) }
                        }
                    }
                }
            }

            Section("Other costs") {
                Panel {
                    draft.misc.forEach { line ->
                        fun set(change: (MiscLine) -> MiscLine) = update { sheet -> sheet.copy(misc = sheet.misc.map { if (it.id == line.id) change(it) else it }) }
                        LineCard(
                            line.label,
                            if (line.basis == MiscBasis.PER_HA) "${formatEuro(line.amount, 2)}/ha" else "For the whole parcel",
                            formatEuro(miscCost(line, areaHa), 0),
                            "",
                            editable,
                            { update { sheet -> sheet.copy(misc = sheet.misc.filter { it.id != line.id }) } },
                        ) {
                            Field(line.label, { l -> set { it.copy(label = l) } }, "What is it (rent, insurance, drying, transport...)")
                            Inputs { NumField("Amount (€)", line.amount, { v -> set { it.copy(amount = v ?: 0.0) } }, Modifier.weight(1f), !editable) }
                            Choice(listOf(ChoiceOption(MiscBasis.PER_HA, "Per hectare"), ChoiceOption(MiscBasis.TOTAL, "For the parcel")), line.basis, { b -> set { it.copy(basis = b) } })
                        }
                    }
                    if (editable) {
                        OutlinedButton(onClick = { update { it.copy(misc = it.misc + MiscLine(newLineId(), "", 0.0, MiscBasis.PER_HA)) } }, modifier = Modifier.fillMaxWidth()) { Text("Add a cost") }
                    }
                }
            }

            if (editable) {
                Button(onClick = {
                    saving = true
                    actions.run("economics", "report", onSuccess = { dirty = false; saving = false }) {
                        try {
                            Api.post("/economics/sheets/$parcelId/$year", AppJson.encodeToJsonElement(EconomicSheet.serializer(), draft))
                        } finally {
                            saving = false
                        }
                    }
                }, enabled = dirty && !saving, modifier = Modifier.fillMaxWidth()) { Text(if (dirty) "Save and recalculate" else "Saved") }
            }

            Section("Field operations") {
                Panel {
                    if (item.result.operations.isEmpty() && item.result.projected.isEmpty()) Txt("No operations yet.", TextVariant.Small)
                    item.result.operations.forEach { OperationRow(it) }
                    if (item.result.projected.isNotEmpty()) Txt("Still to come, estimated from the technology and the inputs:", TextVariant.Small)
                    item.result.projected.forEach { OperationRow(it) }
                }
            }
        }
    }
}
