package ro.fermapp.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Satellite
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.Button
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import ro.fermapp.app.Navigator
import ro.fermapp.app.Routes
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.CropSeason
import ro.fermapp.app.data.Parcel
import ro.fermapp.app.data.Role
import ro.fermapp.app.data.body
import ro.fermapp.app.data.canManage
import ro.fermapp.app.data.canPlan
import ro.fermapp.app.data.rememberActions
import ro.fermapp.app.data.rememberApi
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.data.rememberFieldStatuses
import ro.fermapp.app.data.rememberParcels
import ro.fermapp.app.features.CompletionScore
import ro.fermapp.app.features.FieldStatusBadge
import ro.fermapp.app.features.ParcelEconomicsTab
import ro.fermapp.app.features.TillageBadge
import ro.fermapp.app.features.TillageSection
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.theme.healthColor
import ro.fermapp.app.ui.AuthImage
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.Empty
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.IconAction
import ro.fermapp.app.ui.InfoRow
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.ParcelShape
import ro.fermapp.app.ui.ProgressTrack
import ro.fermapp.app.ui.QueryContent
import ro.fermapp.app.ui.Screen
import ro.fermapp.app.ui.Section
import ro.fermapp.app.ui.Stat
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.fullDate
import ro.fermapp.app.ui.ha
import ro.fermapp.app.ui.isPositive
import ro.fermapp.app.ui.number
import ro.fermapp.app.ui.parseNumber
import ro.fermapp.app.ui.parseRoDate
import ro.fermapp.app.ui.shortDate
import ro.fermapp.domain.CROP_CODES
import ro.fermapp.domain.CostCategory
import ro.fermapp.domain.CropCode
import ro.fermapp.domain.RotationResult
import ro.fermapp.domain.RotationVerdict
import ro.fermapp.domain.formatEuro
import ro.fermapp.domain.jsNum
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.profile
import ro.fermapp.domain.toJsIsoString

private enum class DetailTab(val label: String) {
    CROPS("Crops"), ECONOMICS("Economic analysis"), TILLAGE("Tillage"), ROTATION("Rotation"), SOIL("Soil"),
    WEATHER("Weather"), WINDOWS("Work windows"), WATER("Water"), SATELLITE("Satellite"), COSTS("Expenses"),
}

private val VERDICT_COLOR = mapOf(RotationVerdict.RECOMMENDED to FarmColors.leaf, RotationVerdict.ACCEPTABLE to FarmColors.amber, RotationVerdict.NOT_RECOMMENDED to FarmColors.rust)

@Composable
private fun SeasonsTab(parcelId: String, editable: Boolean) {
    val seasons = rememberApi<List<CropSeason>>("seasons/$parcelId", "/parcels/$parcelId/seasons")
    val actions = rememberActions()
    var crop by remember { mutableStateOf<CropCode?>(null) }
    var year by remember { mutableStateOf(currentYear().toString()) }
    var sowing by remember { mutableStateOf("") }
    var harvest by remember { mutableStateOf("") }
    var yieldT by remember { mutableStateOf("") }
    var moisture by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val validDates = (sowing.isEmpty() || parseRoDate(sowing) != null) && (harvest.isEmpty() || parseRoDate(harvest) != null)

    QueryContent(seasons) { items ->
        if (items.isEmpty()) {
            Empty(Icons.Outlined.Spa, "No crop history", "Add the crops from recent years to get rotation recommendations.")
        } else {
            Panel {
                items.forEach { season ->
                    Column(Modifier.padding(vertical = Space.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        InfoRow(season.year.toString(), listOfNotNull(season.crop.profile.name, season.seedName).joinToString(", "))
                        val details = listOfNotNull(
                            season.sowingDate?.let { "Sown ${shortDate(it)}" },
                            season.harvestDate?.let { "Harvested ${shortDate(it)}" },
                            season.yieldT?.let { "${jsNum(it)} t" },
                            season.harvestMoisture?.let { "moisture ${jsNum(it)}%" },
                        )
                        if (details.isNotEmpty()) Txt(details.joinToString(", "), TextVariant.Small)
                    }
                }
            }
        }
    }
    if (editable) {
        Section("Add a crop") {
            Panel {
                Choice(CROP_CODES.map { ChoiceOption(it, it.profile.name) }, crop, { crop = it }, "Crop")
                Field(year, { year = it }, "Year", keyboard = KeyboardType.Number)
                Field(sowing, { sowing = it }, "Sowing date (DD.MM.YYYY)")
                Field(harvest, { harvest = it }, "Harvest date (DD.MM.YYYY)")
                Field(yieldT, { yieldT = it }, "Yield (tonnes)", keyboard = KeyboardType.Decimal)
                Field(moisture, { moisture = it }, "Harvest moisture (%)", keyboard = KeyboardType.Decimal)
                Field(notes, { notes = it }, "Notes", singleLine = false)
                Button(onClick = {
                    saving = true
                    actions.run("seasons/$parcelId", "parcels", "dashboard", "rotation/$parcelId", onSuccess = {
                        crop = null; sowing = ""; harvest = ""; yieldT = ""; moisture = ""; notes = ""
                    }) {
                        try {
                            Api.post(
                                "/parcels/$parcelId/seasons",
                                body(
                                    "crop" to crop,
                                    "year" to year.toInt(),
                                    "sowingDate" to (if (sowing.isNotEmpty()) parseRoDate(sowing)?.toJsIsoString() else null),
                                    "harvestDate" to (if (harvest.isNotEmpty()) parseRoDate(harvest)?.toJsIsoString() else null),
                                    "yieldT" to (if (yieldT.isNotEmpty()) parseNumber(yieldT) else null),
                                    "harvestMoisture" to (if (moisture.isNotEmpty()) parseNumber(moisture) else null),
                                    "notes" to notes.trim().ifEmpty { null },
                                ),
                            )
                        } finally {
                            saving = false
                        }
                    }
                }, enabled = crop != null && Regex("^\\d{4}$").matches(year) && validDates && !saving, modifier = Modifier.fillMaxWidth()) { Text("Save crop") }
            }
        }
    }
}

@Serializable
private data class RecommendationItem(val crop: CropCode, val name: String, val score: Int, val reasons: List<String>, val missingData: List<String> = emptyList())

@Serializable
private data class RecommendationList(val recommendations: List<RecommendationItem>)

@Composable
private fun RotationTab(parcelId: String) {
    val year = currentYear() + 1
    val options = rememberApi<List<RotationResult>>("rotation/$parcelId/$year", "/parcels/$parcelId/rotation/options", mapOf("year" to year))
    val recommendations = rememberApi<RecommendationList>("recommendations/$parcelId/$year", "/parcels/$parcelId/recommendations", mapOf("year" to year))
    Section("Rotation for $year") {
        QueryContent(options) { items ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                items.forEach { item ->
                    val color = VERDICT_COLOR.getValue(item.verdict)
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(item.crop.profile.name, TextVariant.Subtitle, modifier = Modifier.weight(1f))
                            Txt(item.score.toString(), TextVariant.Figure, color = color)
                        }
                        Txt(item.label, color = color)
                        Txt(item.explanation, TextVariant.Small)
                    }
                }
            }
        }
    }
    Section("Fit by soil and climate") {
        QueryContent(recommendations) { data ->
            Panel {
                data.recommendations.take(5).forEach { item ->
                    Column(Modifier.padding(vertical = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        InfoRow(item.name, "${item.score}%")
                        ProgressTrack(item.score.toDouble(), FarmColors.leaf)
                        Txt(item.reasons.drop(1).take(2).joinToString(" "), TextVariant.Small)
                        if (item.missingData.isNotEmpty()) Txt("Missing data: ${item.missingData.joinToString(", ")}.", TextVariant.Small)
                    }
                }
            }
        }
    }
}

@Serializable
private data class SoilProfile(
    val status: String,
    val label: String? = null,
    val ph: Double? = null,
    val clayPct: Double? = null,
    val sandPct: Double? = null,
    val siltPct: Double? = null,
    val organicMatterPct: Double? = null,
    val organicCarbonGKg: Double? = null,
    val waterHoldingMmPerM: Double? = null,
    val errorMessage: String? = null,
)

@Composable
private fun SoilTab(parcelId: String, editable: Boolean) {
    val soil = rememberApi<SoilProfile>("soil/$parcelId", "/parcels/$parcelId/soil")
    val actions = rememberActions()
    // While the analysis runs, the profile is checked again every few seconds.
    LaunchedEffect(soil.data?.status) {
        while (soil.data?.status == "PENDING") {
            delay(4000)
            soil.refetch()
        }
    }
    QueryContent(soil) { data ->
        if (data.status == "READY") {
            Panel {
                Txt(data.label ?: "", TextVariant.Title)
                Txt("Indicative classification based on SoilGrids maps.", TextVariant.Small)
                InfoRow("pH", data.ph?.let(::jsNum) ?: "-")
                InfoRow("Clay", "${data.clayPct?.let(::jsNum)}%")
                InfoRow("Sand", "${data.sandPct?.let(::jsNum)}%")
                InfoRow("Silt", "${data.siltPct?.let(::jsNum)}%")
                InfoRow("Organic matter", "${data.organicMatterPct?.let(::jsNum)}%")
                InfoRow("Organic carbon", "${data.organicCarbonGKg?.let(::jsNum)} g/kg")
                InfoRow("Water retention", "${data.waterHoldingMmPerM?.let(::jsNum)} mm/m")
            }
        } else {
            Empty(
                Icons.Outlined.Layers,
                if (data.status == "PENDING") "Analysing the soil" else "The soil profile is not available",
                data.errorMessage,
            ) {
                if (data.status == "FAILED" && editable) {
                    TextButton(onClick = { actions.run("soil/$parcelId") { Api.post("/parcels/$parcelId/soil/refresh", offline = false) } }) { Text("Try again") }
                }
            }
        }
    }
}

@Serializable
private data class ForecastDay(
    val date: String,
    val tMaxC: Double,
    val tMinC: Double,
    val precipitationMm: Double,
    val et0Mm: Double? = null,
    val radiationMjM2: Double? = null,
    val windMaxKmh: Double? = null,
    val humidityPct: Double? = null,
)

@Serializable
private data class ForecastHourView(val time: String, val temperatureC: Double, val precipitationMm: Double, val windKmh: Double, val humidityPct: Double)

@Serializable
private data class ForecastView(val current: ForecastHourView? = null, val hours: List<ForecastHourView> = emptyList(), val days: List<ForecastDay> = emptyList())

@Serializable
private data class HistoryTotals(val precipitationMm: Double, val et0Mm: Double)

@Serializable
private data class HistoryView(val totals: HistoryTotals, val days: List<ForecastDay> = emptyList())

@Composable
private fun WeatherTab(parcelId: String) {
    var range by remember { mutableStateOf("7d") }
    val isHistory = range == "30d" || range == "12m"
    Choice(
        listOf(ChoiceOption("24h", "24 hours"), ChoiceOption("7d", "7 days"), ChoiceOption("14d", "14 days"), ChoiceOption("30d", "Last 30 days"), ChoiceOption("12m", "Last 12 months")),
        range,
        { range = it },
    )
    if (isHistory) {
        val history = rememberApi<HistoryView>("history/$parcelId/$range", "/parcels/$parcelId/weather/history", mapOf("range" to range))
        QueryContent(history) { data ->
            Panel {
                Row {
                    Stat(number(data.totals.precipitationMm), "Rainfall", unit = "mm", color = FarmColors.water, modifier = Modifier.weight(1f))
                    Stat(number(data.totals.et0Mm), "Evapotranspiration", unit = "mm", modifier = Modifier.weight(1f))
                }
            }
        }
    } else {
        val forecast = rememberApi<ForecastView>("forecast/$parcelId/$range", "/parcels/$parcelId/weather/forecast", mapOf("range" to range))
        QueryContent(forecast) { data ->
            Panel {
                data.hours.forEach { hour ->
                    InfoRow(hour.time.substring(11, 16), "${jsNum(jsRound(hour.temperatureC))}°, ${jsNum(hour.precipitationMm)} mm, wind ${jsNum(jsRound(hour.windKmh))} km/h")
                }
                data.days.forEach { day ->
                    Column(Modifier.padding(vertical = Space.xs)) {
                        InfoRow(shortDate(day.date), "${jsNum(jsRound(day.tMinC))}° / ${jsNum(jsRound(day.tMaxC))}°, ${jsNum(day.precipitationMm)} mm")
                        Txt(
                            "Wind ${jsNum(jsRound(day.windMaxKmh ?: 0.0))} km/h, humidity ${jsNum(jsRound(day.humidityPct ?: 0.0))}%, ET₀ ${jsNum(day.et0Mm ?: 0.0)} mm, radiation ${jsNum(day.radiationMjM2 ?: 0.0)} MJ/m²",
                            TextVariant.Small,
                        )
                    }
                }
            }
        }
    }
}

@Serializable
private data class WindowView(val label: String, val hours: Int, val score: Int)

@Serializable
private data class OperationWindows(val operation: String, val message: String, val windows: List<WindowView>)

@Composable
private fun WindowsTab(parcelId: String) {
    val windows = rememberApi<List<OperationWindows>>("windows/$parcelId", "/parcels/$parcelId/weather/windows")
    QueryContent(windows) { items ->
        Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
            items.forEach { item ->
                Panel {
                    Txt(item.message, TextVariant.Subtitle)
                    item.windows.drop(1).take(3).forEach { InfoRow(it.label, "${it.hours} hours") }
                }
            }
        }
    }
}

@Serializable
private data class WaterView(
    val cropName: String,
    val requiredMm: Double,
    val requiredMinMm: Double,
    val requiredMaxMm: Double,
    val precipitationMm: Double,
    val irrigationMm: Double,
    val deficitMm: Double,
    val coveragePct: Double,
    val suggestion: String,
)

@Composable
private fun WaterTab(parcelId: String) {
    val water = rememberApi<WaterView>("water/$parcelId", "/parcels/$parcelId/water")
    QueryContent(water) { data ->
        Panel {
            Txt(data.cropName, TextVariant.Title)
            ProgressTrack(data.coveragePct, FarmColors.water)
            Row {
                Stat(number(data.precipitationMm + data.irrigationMm, 0), "Water received", unit = "mm", color = FarmColors.water, modifier = Modifier.weight(1f))
                Stat(number(data.deficitMm, 0), "Deficit", unit = "mm", color = if (data.deficitMm != 0.0) FarmColors.rust else FarmColors.leaf, modifier = Modifier.weight(1f))
            }
            InfoRow("Seasonal requirement", "${jsNum(data.requiredMinMm)}-${jsNum(data.requiredMaxMm)} mm")
            InfoRow("Rainfall", "${jsNum(data.precipitationMm)} mm")
            InfoRow("Irrigation", "${jsNum(data.irrigationMm)} mm")
            Txt(data.suggestion)
        }
    }
}

@Serializable
private data class HealthLabel(val label: String)

@Serializable
private data class SatelliteObservation(val id: String, val date: String, val ndvi: Double, val ndre: Double? = null, val healthIndex: Double, val healthClass: String, val health: HealthLabel)

@Serializable
private data class SatelliteHistory(val configured: Boolean, val observations: List<SatelliteObservation>)

@Serializable
private data class SatelliteChange(val ndvi: Double, val healthIndex: Double)

@Serializable
private data class SatelliteCompare(val change: SatelliteChange)

@Composable
private fun CompareResult(parcelId: String, from: String, to: String) {
    val compare = rememberApi<SatelliteCompare>("satellite-compare/$parcelId/$from/$to", "/parcels/$parcelId/satellite/compare", mapOf("from" to from, "to" to to))
    val change = compare.data?.change ?: return
    Row {
        Stat("${if (change.ndvi > 0) "+" else ""}${jsNum(change.ndvi)}", "NDVI change", color = if (change.ndvi >= 0) FarmColors.leaf else FarmColors.rust, modifier = Modifier.weight(1f))
        Stat("${if (change.healthIndex > 0) "+" else ""}${jsNum(change.healthIndex)}", "Health Index", modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun SatelliteTab(parcelId: String, editable: Boolean) {
    val data = rememberApi<SatelliteHistory>("satellite/$parcelId", "/parcels/$parcelId/satellite")
    val actions = rememberActions()
    var from by remember { mutableStateOf<String?>(null) }
    var to by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    QueryContent(data) { result ->
        val dates = result.observations.map { ChoiceOption(it.date.take(10), shortDate(it.date)) }
        val latest = result.observations.firstOrNull()
        Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
            if (!result.configured) {
                Empty(Icons.Outlined.Satellite, "Satellite imagery is not enabled", "The administrator must add the Copernicus Sentinel account on the server.")
            } else if (editable) {
                OutlinedButton(onClick = {
                    refreshing = true
                    actions.run("satellite/$parcelId", "parcels", onSuccess = { refreshing = false }) {
                        try {
                            Api.post("/parcels/$parcelId/satellite/refresh", body("days" to 90), offline = false)
                        } finally {
                            refreshing = false
                        }
                    }
                }, enabled = !refreshing, modifier = Modifier.fillMaxWidth()) { Text(if (refreshing) "Downloading…" else "Download latest images") }
            }
            if (latest != null) {
                Panel {
                    AuthImage("/parcels/$parcelId/satellite/image?date=${latest.date.take(10)}&index=NDVI", "NDVI map from ${fullDate(latest.date)}", contentScale = ContentScale.Fit)
                    Txt(latest.health.label, TextVariant.Title, color = healthColor(latest.healthClass))
                    Row {
                        Stat(jsNum(latest.ndvi), "NDVI", modifier = Modifier.weight(1f))
                        Stat(latest.ndre?.let(::jsNum) ?: "-", "NDRE", modifier = Modifier.weight(1f))
                        Stat(jsNum(latest.healthIndex), "Health Index", modifier = Modifier.weight(1f))
                    }
                }
            } else if (result.configured) {
                Empty(Icons.Outlined.HideImage, "No cloud-free image", "Download the images for the last 90 days.")
            }
            if (dates.size > 1) {
                Section("Compare two dates") {
                    Panel {
                        Choice(dates, from, { from = it }, "From")
                        Choice(dates, to, { to = it }, "To")
                        val a = from
                        val b = to
                        if (a != null && b != null && a != b) CompareResult(parcelId, a, b)
                    }
                }
            }
            if (result.observations.isNotEmpty()) {
                Section("History") {
                    Panel { result.observations.forEach { InfoRow(fullDate(it.date), "NDVI ${jsNum(it.ndvi)}", healthColor(it.healthClass)) } }
                }
            }
        }
    }
}

private val COST_LABELS = linkedMapOf(
    CostCategory.SEED to "Seeds",
    CostCategory.FERTILIZER to "Fertilizers",
    CostCategory.PESTICIDE to "Pesticides",
    CostCategory.FUEL to "Fuel",
    CostCategory.LABOR to "Labour",
    CostCategory.IRRIGATION to "Irrigation",
    CostCategory.OTHER to "Other",
)

@Serializable
private data class CostSummaryView(
    val totalCost: Double,
    val costPerHa: Double,
    val byCategory: Map<CostCategory, Double>,
    val estimatedProfit: Double? = null,
    val actualProfit: Double? = null,
)

@Composable
private fun CostsTab(parcelId: String, editable: Boolean) {
    val year = currentYear()
    val summary = rememberApi<CostSummaryView>("costs/$parcelId/$year", "/parcels/$parcelId/costs/summary", mapOf("year" to year))
    val actions = rememberActions()
    var category by remember { mutableStateOf(CostCategory.SEED) }
    var amount by remember { mutableStateOf("") }
    fun money(value: Double?) = value?.let { formatEuro(it, 2) } ?: "Insufficient data"
    QueryContent(summary) { data ->
        Panel {
            Row {
                Stat(formatEuro(data.totalCost, 0), "Recorded expenses $year", modifier = Modifier.weight(1f))
                Stat(formatEuro(data.costPerHa, 2), "Per hectare", unit = "/ha", modifier = Modifier.weight(1f))
            }
            COST_LABELS.forEach { (key, label) -> InfoRow(label, formatEuro(data.byCategory[key] ?: 0.0, 2)) }
            InfoRow("Estimated profit", money(data.estimatedProfit))
            InfoRow("Actual profit", money(data.actualProfit))
        }
    }
    if (editable) {
        Section("Add a cost") {
            Panel {
                Choice(COST_LABELS.map { (value, label) -> ChoiceOption(value, label) }, category, { category = it })
                Field(amount, { amount = it }, "Amount (€)", keyboard = KeyboardType.Decimal)
                Button(onClick = {
                    actions.run("costs/$parcelId/$year", "economics", onSuccess = { amount = "" }) {
                        Api.post("/costs", body("parcelId" to parcelId, "year" to year, "category" to category, "amount" to parseNumber(amount)))
                    }
                }, enabled = isPositive(amount), modifier = Modifier.fillMaxWidth()) { Text("Save cost") }
            }
        }
    }
}

@Composable
fun ParcelDetailScreen(parcelId: String, navigator: Navigator) {
    val auth = rememberAuth()
    val role = auth.role
    var tab by remember { mutableStateOf(DetailTab.CROPS) }
    val parcel = rememberApi<Parcel>("parcel/$parcelId", "/parcels/$parcelId")
    // The farm list carries the same parcel with its current-season crop; it is kept fresh by the map.
    val listed = rememberParcels().data?.firstOrNull { it.id == parcelId }
    val fieldState = rememberFieldStatuses()[parcelId]
    val visibleTabs = if (role == Role.OPERATOR) DetailTab.entries.filter { it != DetailTab.COSTS && it != DetailTab.ECONOMICS } else DetailTab.entries

    Screen {
        QueryContent(parcel) { data ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                ParcelShape(data.geometry, 72.dp)
                Column(Modifier.weight(1f)) {
                    Txt(ha(data.areaHa), TextVariant.Title)
                    Txt("${data.code}, perimeter ${number(data.perimeterM, 0)} m", TextVariant.Small)
                    val place = listOfNotNull(data.locality, data.county).joinToString(", ")
                    if (place.isNotEmpty()) Txt(place, TextVariant.Small)
                }
                TillageBadge((listed ?: data).tillageSystem)
                if (canManage(role)) IconAction(Icons.Outlined.Edit, "Edit parcel", { navigator.open(Routes.ParcelEditor(parcelId)) })
            }
        }
        if (fieldState != null) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                FieldStatusBadge(fieldState)
                CompletionScore(fieldState)
            }
        }
        Choice(visibleTabs.map { ChoiceOption(it, it.label) }, tab, { tab = it })
        when (tab) {
            DetailTab.CROPS -> SeasonsTab(parcelId, canPlan(role))
            DetailTab.ECONOMICS -> ParcelEconomicsTab(parcelId)
            DetailTab.TILLAGE -> (listed ?: parcel.data)?.let { current ->
                TillageSection(current, canManage(role), current.seasons?.firstOrNull { it.year == currentYear() }?.crop)
            }
            DetailTab.ROTATION -> RotationTab(parcelId)
            DetailTab.SOIL -> SoilTab(parcelId, canPlan(role))
            DetailTab.WEATHER -> WeatherTab(parcelId)
            DetailTab.WINDOWS -> WindowsTab(parcelId)
            DetailTab.WATER -> WaterTab(parcelId)
            DetailTab.SATELLITE -> SatelliteTab(parcelId, canPlan(role))
            DetailTab.COSTS -> CostsTab(parcelId, canManage(role))
        }
    }
}
