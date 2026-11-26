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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Agriculture
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Pentagon
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import ro.fermapp.app.Navigator
import ro.fermapp.app.Routes
import ro.fermapp.app.data.Dashboard
import ro.fermapp.app.data.canPlan
import ro.fermapp.app.data.rememberApi
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.data.rememberFieldStatuses
import ro.fermapp.app.data.rememberParcels
import ro.fermapp.app.data.rememberTillageSettings
import ro.fermapp.app.features.OperationalSection
import ro.fermapp.app.features.colour
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.theme.hexColor
import ro.fermapp.app.ui.Empty
import ro.fermapp.app.ui.IRRIGATION_TYPES
import ro.fermapp.app.ui.IconAction
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.ParcelShape
import ro.fermapp.app.ui.ProgressTrack
import ro.fermapp.app.ui.QueryContent
import ro.fermapp.app.ui.RunCard
import ro.fermapp.app.ui.Screen
import ro.fermapp.app.ui.Section
import ro.fermapp.app.ui.Stat
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.WORK_TYPES
import ro.fermapp.app.ui.isRunActive
import ro.fermapp.app.ui.localZone
import ro.fermapp.app.ui.number
import ro.fermapp.domain.EconomicSettings
import ro.fermapp.domain.FarmEconomics
import ro.fermapp.domain.FarmParcelEconomics
import ro.fermapp.domain.ParcelTillageInput
import ro.fermapp.domain.TILLAGE_LIST
import ro.fermapp.domain.analyzeTillage
import ro.fermapp.domain.formatEuro
import ro.fermapp.domain.profile
import kotlin.time.Clock

@Serializable
data class FarmEconomicsResponse(
    val year: Int,
    val settings: EconomicSettings,
    val parcels: List<FarmParcelEconomics>,
    val summary: FarmEconomics,
)

fun currentYear(): Int = Clock.System.now().toLocalDateTime(localZone).year

@Composable
fun rememberFarmEconomics(year: Int) = rememberApi<FarmEconomicsResponse>("economics/farm/$year", "/economics/farm", mapOf("year" to year))

private val SEVERITY_COLOR = mapOf("INFO" to FarmColors.water, "WARNING" to FarmColors.amber, "SEVERE" to FarmColors.rust)

/** A tappable panel with an icon, a title and a chevron. */
@Composable
fun LinkPanel(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onOpen: () -> Unit, content: @Composable () -> Unit) {
    Panel(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Icon(icon, null, tint = FarmColors.wheat, modifier = Modifier.size(20.dp))
            Txt(title, TextVariant.Subtitle, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = FarmColors.straw)
        }
        content()
    }
}

/** Real cost per hectare and profit of the farm this season, with a link to the financial dashboard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinancialCard(onOpen: () -> Unit) {
    val year = currentYear()
    val summary = rememberFarmEconomics(year).data?.summary
    LinkPanel(Icons.Outlined.Payments, "Economics $year", onOpen) {
        if (summary == null || summary.cost <= 0) {
            Txt("Add seed, fertiliser and crop protection on a parcel to see the real cost per hectare.", TextVariant.Small)
        } else {
            FlowRow(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Stat(formatEuro(summary.costPerHa, 0), "Average cost", unit = "/ha", color = FarmColors.wheat)
                val profit = summary.profitPerHa
                Stat(
                    profit?.let { formatEuro(it, 0) } ?: "-",
                    "Average profit",
                    unit = if (profit == null) null else "/ha",
                    color = profit?.let { if (it >= 0) FarmColors.leaf else FarmColors.rust },
                )
                Stat(formatEuro(summary.cost, 0), "Season cost")
            }
        }
    }
}

/** Farm statistics by tillage technology, with a link to the full analysis. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TillageCard(onOpen: () -> Unit) {
    val parcels = rememberParcels().data
    val settings = rememberTillageSettings()
    val analysis = remember(parcels, settings) {
        analyzeTillage(parcels.orEmpty().filter { it.archivedAt == null }.map { ParcelTillageInput(it.areaHa, it.tillageSystem) }, settings)
    }
    val total = analysis.totalAreaHa.takeIf { it != 0.0 } ?: 1.0
    LinkPanel(Icons.Outlined.Agriculture, "Tillage technologies", onOpen) {
        if (analysis.assignedAreaHa == 0.0) {
            Txt("Set a technology on your parcels to compare costs, diesel and soil health.", TextVariant.Small)
        } else {
            // One bar split by area share; segments keep a 2px gap and the legend names each one.
            Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(FarmColors.ridge), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                analysis.bySystem.filter { it.areaHa > 0 }.forEach { row ->
                    Box(Modifier.weight((row.areaHa / total).toFloat()).height(12.dp).background(hexColor(row.system.profile.color)))
                }
                if (analysis.unassignedAreaHa > 0) Box(Modifier.weight((analysis.unassignedAreaHa / total).toFloat()))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TILLAGE_LIST.forEach { profile ->
                    val row = analysis.bySystem.firstOrNull { it.system == profile.code }
                    if (row != null && row.areaHa > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(hexColor(profile.color)))
                            Txt("${profile.badge} ${number(row.areaHa)} ha", TextVariant.Small)
                        }
                    }
                }
            }
            FlowRow(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Stat(formatEuro(analysis.costSavings, 0), "Saved per year", color = if (analysis.costSavings > 0) FarmColors.leaf else null)
                Stat(number(analysis.fuelSavingsL, 0), "Less diesel", unit = "l")
                Stat(analysis.avgSoilHealth?.let { number(it, 0) } ?: "-", "Soil health", unit = "/100")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(navigator: Navigator) {
    val auth = rememberAuth()
    val statuses = rememberFieldStatuses()
    val dashboard = rememberApi<Dashboard>("dashboard", "/dashboard")
    val parcels = rememberParcels()

    Screen(
        title = auth.session?.farm?.name ?: "My farm",
        action = { IconAction(Icons.Outlined.Settings, "Settings", { navigator.open(Routes.Settings) }) },
        onRefresh = {
            dashboard.refetch()
            parcels.refetch()
        },
        refreshing = dashboard.loading,
    ) {
        QueryContent(dashboard) { data ->
            if (data.parcels.count == 0) {
                Empty(Icons.Outlined.Pentagon, "The farm has no parcels yet", "Draw the first parcel on the map or import the APIA file.") {
                    Button(onClick = { navigator.open(Routes.ParcelEditor()) }) { Text("Add a parcel") }
                }
                return@QueryContent
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                Panel {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        parcels.data.orEmpty().take(24).forEach { parcel ->
                            val status = statuses[parcel.id]
                            // Each parcel takes its plan status colour, with its field completion score below.
                            Column(
                                Modifier.width(52.dp).clickable { navigator.open(Routes.ParcelDetail(parcel.id, parcel.name)) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                ParcelShape(parcel.geometry, 46.dp, status?.state?.colour ?: FarmColors.wheat)
                                Txt(status?.completionScore?.let { "$it%" } ?: "-", TextVariant.Small, align = TextAlign.Center)
                            }
                        }
                    }
                    FlowRow(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                        Stat(data.parcels.count.toString(), "Parcels")
                        Stat(number(data.parcels.totalAreaHa), "Total area", unit = "ha")
                        Stat(number(data.parcels.cultivatedAreaHa), "Cultivated in ${data.year}", unit = "ha", color = FarmColors.leaf)
                        Stat(number(data.parcels.freeAreaHa), "Free area", unit = "ha")
                    }
                }

                OperationalSection { parcel -> navigator.open(Routes.ParcelDetail(parcel.id, parcel.name)) }

                if (data.alerts.isNotEmpty()) {
                    Section("Weather alerts") {
                        data.alerts.take(3).forEach { alert ->
                            Panel {
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                                    Icon(Icons.Outlined.Thunderstorm, null, tint = SEVERITY_COLOR[alert.severity] ?: FarmColors.water, modifier = Modifier.size(22.dp))
                                    Column(Modifier.weight(1f)) {
                                        Txt(alert.title, TextVariant.Subtitle)
                                        Txt(alert.message, TextVariant.Small)
                                    }
                                }
                            }
                        }
                    }
                }

                val activeWork = data.activeWork.filter { isRunActive(it) }
                if (activeWork.size + data.activeIrrigation.size > 0) {
                    Section("In progress") {
                        activeWork.forEach { work -> RunCard(work, "${WORK_TYPES[work.type]}, ${work.parcel.name}", work.operator.fullName) }
                        data.activeIrrigation.forEach { run ->
                            RunCard(run, "Irrigation, ${run.parcel.name}", IRRIGATION_TYPES[run.systemType.name] ?: "", color = FarmColors.water)
                        }
                    }
                }

                if (canPlan(auth.role)) FinancialCard { navigator.open(Routes.FinancialDashboard) }
                TillageCard { navigator.open(Routes.TillageAnalysis) }

                Panel {
                    Row {
                        Stat(data.tasks.open.toString(), "Open tasks", modifier = Modifier.weight(1f))
                        Stat(data.tasks.overdue.toString(), "Overdue", color = if (data.tasks.overdue > 0) FarmColors.rust else null, modifier = Modifier.weight(1f))
                    }
                }

                if (data.crops.isNotEmpty()) {
                    Section("Crops in ${data.year}") {
                        Panel {
                            data.crops.forEach { crop ->
                                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Txt(crop.name)
                                        Txt("${number(crop.areaHa)} ha", TextVariant.Small)
                                    }
                                    ProgressTrack((crop.areaHa / data.parcels.totalAreaHa) * 100, FarmColors.leaf)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
