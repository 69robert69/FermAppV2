package ro.fermapp.app.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ro.fermapp.app.data.rememberParcels
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.FarmText
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.InfoRow
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.number
import ro.fermapp.app.ui.shortDate
import ro.fermapp.app.ui.toLocal
import ro.fermapp.domain.CompatibilityCheck
import ro.fermapp.domain.ParcelPlanData
import ro.fermapp.domain.ParcelReport
import ro.fermapp.domain.PlanStep
import ro.fermapp.domain.jsNum
import androidx.compose.material3.Text

/** The plan views of features/plan/PlanViews.tsx and the small shared pickers. */

data class CalendarEntry(val id: String, val date: String, val title: String, val subtitle: String? = null, val done: Boolean = false)

private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

private fun monthLabel(date: String): String {
    val t = toLocal(date)
    return "${MONTHS[t.month.ordinal]} ${t.year}"
}

/** Activities grouped by month, in chronological order. */
@Composable
fun CalendarList(entries: List<CalendarEntry>) {
    val months = entries.sortedBy { it.date }.groupBy { monthLabel(it.date) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        months.forEach { (month, items) ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Txt(month, TextVariant.Subtitle, color = FarmColors.wheat)
                items.forEach { entry ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        Text(
                            toLocal(entry.date).day.toString(),
                            style = FarmText.figure.copy(fontSize = 24.sp, lineHeight = 28.sp, color = if (entry.done) FarmColors.straw else FarmColors.chaff),
                            modifier = Modifier.width(38.dp),
                            textAlign = TextAlign.End,
                        )
                        Column(Modifier.weight(1f)) {
                            Txt(entry.title, color = if (entry.done) FarmColors.straw else FarmColors.chaff)
                            entry.subtitle?.let { Txt(it, TextVariant.Small) }
                        }
                        if (entry.done) Icon(Icons.Outlined.CheckCircle, "Done", tint = FarmColors.leaf, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PlanChecklist(steps: List<PlanStep>, doneKeys: Set<String> = emptySet()) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        steps.forEach { step ->
            val done = step.key.name in doneKeys
            Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Icon(
                    if (done) Icons.Outlined.CheckCircle else Icons.Outlined.CheckCircleOutline,
                    null,
                    tint = when {
                        done -> FarmColors.leaf
                        step.past -> FarmColors.straw
                        else -> FarmColors.wheat
                    },
                    modifier = Modifier.size(22.dp),
                )
                Txt(step.title, color = if (step.past) FarmColors.straw else FarmColors.chaff, modifier = Modifier.weight(1f))
                Txt(shortDate(step.date), TextVariant.Small)
            }
        }
    }
}

fun stepsToCalendar(steps: List<PlanStep>, doneKeys: Set<String> = emptySet()) =
    steps.map { CalendarEntry(it.key.name, it.date, it.title, done = it.key.name in doneKeys) }

/** Water requirement, fertilization scheme and monitoring plan. */
@Composable
fun PlanResources(plan: ParcelPlanData) {
    val irrigation = plan.irrigation
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Panel {
            Txt("Irrigation requirement", TextVariant.Subtitle)
            InfoRow("Seasonal requirement", "${number(irrigation.requiredMm, 0)} mm")
            InfoRow("Estimated effective rain", irrigation.effectiveRainMm?.let { "${number(it.toDouble(), 0)} mm" } ?: "Data unavailable")
            InfoRow("Deficit", irrigation.deficitMm?.let { "${number(it.toDouble(), 0)} mm" } ?: "Data unavailable")
            InfoRow("Recommended cycles", if (irrigation.deficitMm == null) "-" else "${irrigation.passes} x ${irrigation.doseMm} mm")
            InfoRow("Critical period", irrigation.criticalPeriod)
        }
        Panel {
            Txt("Fertilization scheme", TextVariant.Subtitle)
            plan.fertilization.forEach { stage ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt("${stage.stage}${stage.date?.let { ", ${shortDate(it)}" } ?: ""}")
                    Txt("N ${stage.nKgHa}, P₂O₅ ${stage.pKgHa}, K₂O ${stage.kKgHa} kg/ha", TextVariant.Small)
                }
            }
            Txt("Indicative doses. Adjust them according to the soil analysis.", TextVariant.Small)
        }
        Panel {
            Txt("Monitoring plan", TextVariant.Subtitle)
            plan.monitoring.forEach { InfoRow(it.title, shortDate(it.date)) }
        }
    }
}

@Composable
fun PlanReport(report: ParcelReport) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Panel {
            InfoRow("Parcel", report.parcelName)
            InfoRow("Area", "${number(report.areaHa, 2)} ha")
            InfoRow("Soil type", report.soilType ?: "Data unavailable")
            InfoRow("Last crop", report.lastCropName ?: "Unknown")
            InfoRow("Planned crop", report.plannedCropName)
            InfoRow("Compatibility score", "${report.compatibilityScore}%")
            InfoRow("Recommended crop", "${report.recommendedCropName}, ${report.recommendedScore}%")
            InfoRow("Estimated yield", "${number(report.expectedYieldTHa)} t/ha")
            report.expectedProfitEurHa?.let { InfoRow("Estimated profit", "${number(it, 0)} EUR/ha") }
        }
        Panel {
            Txt("Main risks", TextVariant.Subtitle)
            report.risks.forEach { risk ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = FarmColors.amber, modifier = Modifier.size(18.dp))
                    Txt(risk, modifier = Modifier.weight(1f))
                }
            }
        }
        Panel {
            Txt("Recommendation", TextVariant.Subtitle)
            Txt(report.recommendation)
        }
        val price = report.priceEurPerT
        Txt(
            (if (price != null) "Indicative estimates for a non-irrigated crop, calculated with ${jsNum(price)} EUR/t and costs of ${jsNum(report.costEurHa ?: 0.0)} EUR/ha." else "Indicative estimates for a non-irrigated crop.") +
                (if (report.missingData.isNotEmpty()) " Missing data about: ${report.missingData.joinToString(", ")}." else ""),
            TextVariant.Small,
        )
    }
}

@Composable
fun ParcelPicker(value: String?, onChange: (String) -> Unit, label: String = "Parcel") {
    val parcels = rememberParcels().data
    if (parcels != null && parcels.isEmpty()) {
        Txt("First add a parcel from the Map screen.", TextVariant.Small)
        return
    }
    Choice(parcels.orEmpty().map { ChoiceOption(it.id, "${it.name} (${jsNum(it.areaHa)} ha)") }, value, onChange, label)
}

/** A warning when a planned operation contradicts the parcel's tillage technology. */
@Composable
fun CompatibilityNotice(check: CompatibilityCheck) {
    if (check.compatible || check.message == null) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(FarmColors.amber.copy(alpha = 0.12f)).padding(Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(Icons.Outlined.WarningAmber, null, tint = FarmColors.amber, modifier = Modifier.size(18.dp))
        Txt(check.message!!, TextVariant.Small, color = FarmColors.chaff, modifier = Modifier.weight(1f))
    }
}
