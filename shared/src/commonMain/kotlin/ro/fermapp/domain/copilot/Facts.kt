package ro.fermapp.domain.copilot

import ro.fermapp.domain.CROP_CALENDARS
import ro.fermapp.domain.CropCode
import ro.fermapp.domain.CropProgress
import ro.fermapp.domain.FieldOperation
import ro.fermapp.domain.HourlyWeather
import ro.fermapp.domain.SoilProperties
import ro.fermapp.domain.SoilTexture
import ro.fermapp.domain.WorkWindow
import ro.fermapp.domain.cropProgress
import ro.fermapp.domain.findWorkWindows
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.jsRoundInt
import ro.fermapp.domain.parseJsDate
import ro.fermapp.domain.profile
import ro.fermapp.domain.soilIndices

/** What the copilot derives about one parcel before it reasons about it. */
data class ParcelFacts(
    val parcel: CopilotParcel,
    val today: String,
    val year: Int,
    /** The season in progress or planned for this year. */
    val season: CopilotSeason?,
    val crop: CropCode?,
    val progress: CropProgress?,
    /** Earlier seasons, newest first. */
    val previous: List<CopilotSeason>,
    val yields: List<YieldRecord>,
    /** Mean of recorded yields relative to each crop's reference yield; 1 = typical. */
    val productivity: Double?,
    val texture: SoilTexture?,
    val soilScore: Int?,
    val open: List<CopilotTask>,
    val overdue: List<CopilotTask>,
    /** Open tasks due within 14 days. */
    val upcoming: List<CopilotTask>,
    val done: List<CopilotTask>,
    val runs: List<CopilotRun>,
    val activeRuns: List<CopilotRun>,
    /** Day of the last finished work of each type. */
    val lastWork: Map<String, String>,
    val irrigated: Boolean,
)

data class YieldRecord(val year: Int, val crop: CropCode, val tHa: Double)

data class WeatherFacts(
    val rain48h: Double,
    val rain7d: Double,
    /** Highest rain probability in the next 48 hours. */
    val rainChance48h: Double,
    /** Hours until the first hour with meaningful rain, within the forecast. */
    val hoursToRain: Int?,
    val maxWind48h: Double,
    val minTemp7d: Double?,
    val maxTemp7d: Double?,
    val et0_7d: Double,
    /** Rain minus reference evapotranspiration over 7 days. */
    val balance7d: Double,
    val past30RainMm: Double?,
    val sprayWindows: List<WorkWindow>,
    val harvestWindows: List<WorkWindow>,
    val fertilizeWindows: List<WorkWindow>,
    /** The first day of the next 7 without meaningful rain and with moderate wind. */
    val firstDryDay: String?,
)

private fun round1(value: Double) = jsRound(value * 10) / 10

fun textureOf(soil: CopilotSoil?): SoilTexture? {
    if (soil == null) return null
    val text = (soil.texture ?: soil.label ?: "").lowercase()
    val clay = soil.clayPct
    if (clay != null) {
        return when {
            clay >= 40 -> SoilTexture.CLAY
            clay >= 27 -> SoilTexture.CLAY_LOAM
            (soil.sandPct ?: 0.0) >= 65 -> SoilTexture.SANDY
            else -> SoilTexture.LOAMY
        }
    }
    return when {
        text.contains("clay loam") -> SoilTexture.CLAY_LOAM
        text.contains("clay") -> SoilTexture.CLAY
        text.contains("sand") -> SoilTexture.SANDY
        text.contains("loam") || text.contains("silt") -> SoilTexture.LOAMY
        else -> null
    }
}

val TEXTURE_LABELS: Map<SoilTexture, String> = mapOf(
    SoilTexture.SANDY to "sandy",
    SoilTexture.LOAMY to "loamy",
    SoilTexture.CLAY_LOAM to "clay-loam",
    SoilTexture.CLAY to "clay",
)

fun parcelFacts(snapshot: FarmSnapshot, parcel: CopilotParcel): ParcelFacts {
    val today = snapshot.now.take(10)
    val year = today.take(4).toInt()
    val now = parseJsDate(snapshot.now)
    // The season in the field: this year's, or one sown and not yet harvested (winter crops).
    val season = parcel.seasons.firstOrNull { it.year == year }
        ?: parcel.seasons.firstOrNull { it.sowingDate != null && it.harvestDate == null && it.year >= year - 1 }
    val crop = season?.crop
    val progress = season?.let {
        cropProgress(
            crop = it.crop,
            sowingDate = it.sowingDate?.let(::parseJsDate),
            harvestDate = it.harvestDate?.let(::parseJsDate),
            now = now,
        )
    }
    val previous = parcel.seasons.filter { it !== season && it.year <= year }.sortedByDescending { it.year }
    val yields = parcel.seasons
        .filter { it.yieldT != null && it.yieldT > 0 && parcel.areaHa > 0 }
        .map { YieldRecord(it.year, it.crop, round1(it.yieldT!! / parcel.areaHa)) }
    val productivity = if (yields.isNotEmpty()) {
        jsRound((yields.sumOf { it.tHa / it.crop.profile.referenceYieldTHa } / yields.size) * 100) / 100
    } else {
        null
    }
    val soil = parcel.soil
    val soilScore = if (soil?.ph != null && soil.organicMatterPct != null && soil.clayPct != null && soil.sandPct != null && soil.waterHoldingMmPerM != null) {
        soilIndices(SoilProperties(soil.ph, soil.organicMatterPct, soil.clayPct, soil.sandPct, soil.waterHoldingMmPerM)).soilScore
    } else {
        parcel.tillage?.profile?.soilHealth
    }
    val tasks = snapshot.tasks.filter { it.parcelId == parcel.id }
    val open = tasks.filter { it.status != CopilotTaskStatus.DONE }.sortedBy { it.dueDate }
    val runs = snapshot.runs.filter { it.parcelId == parcel.id }
    val lastWork = mutableMapOf<String, String>()
    for (run in runs) {
        if (run.status != RunStatus.COMPLETED) continue
        val day = (run.finishDate ?: run.startDate).take(10)
        if ((lastWork[run.type] ?: "") < day) lastWork[run.type] = day
    }
    return ParcelFacts(
        parcel = parcel,
        today = today,
        year = year,
        season = season,
        crop = crop,
        progress = progress,
        previous = previous,
        yields = yields,
        productivity = productivity,
        texture = textureOf(soil),
        soilScore = soilScore,
        open = open,
        overdue = open.filter { it.dueDate.take(10) < today },
        upcoming = open.filter { it.dueDate.take(10) >= today && daysBetween(today, it.dueDate) <= 14 },
        done = tasks.filter { it.status == CopilotTaskStatus.DONE },
        runs = runs,
        activeRuns = runs.filter { it.status == RunStatus.RUNNING || it.status == RunStatus.PAUSED },
        lastWork = lastWork,
        irrigated = (parcel.irrigatedHa ?: 0.0) > 0,
    )
}

private fun CopilotHour.toHourly() = HourlyWeather(
    time = time,
    temperatureC = temperatureC,
    humidityPct = humidityPct,
    precipitationMm = precipitationMm,
    precipitationProbabilityPct = precipitationProbabilityPct,
    windKmh = windKmh,
    weatherCode = weatherCode,
)

fun weatherFacts(snapshot: FarmSnapshot): WeatherFacts? {
    val weather = snapshot.weather ?: return null
    if (weather.days.isEmpty()) return null
    val week = weather.days.take(7)
    val hours48 = weather.hours.take(48)
    val rain7d = round1(week.sumOf { it.precipitationMm })
    val et07d = round1(week.sumOf { it.et0Mm ?: 0.0 })
    val rainIndex = weather.hours.indexOfFirst { it.precipitationMm >= 0.5 || (it.precipitationProbabilityPct ?: 0.0) >= 60 }
    val from = weather.hours.firstOrNull()?.time
    val hourly = weather.hours.map { it.toHourly() }
    val firstTwoDays = week.take(2)
    return WeatherFacts(
        rain48h = round1(if (hours48.isNotEmpty()) hours48.sumOf { it.precipitationMm } else firstTwoDays.sumOf { it.precipitationMm }),
        rain7d = rain7d,
        rainChance48h = (hours48.map { it.precipitationProbabilityPct ?: 0.0 } + firstTwoDays.map { it.rainProbabilityPct ?: 0.0 } + 0.0).max(),
        hoursToRain = if (rainIndex >= 0) rainIndex else null,
        maxWind48h = jsRound((hours48.map { it.windKmh } + firstTwoDays.map { it.windMaxKmh ?: 0.0 } + 0.0).max()),
        minTemp7d = week.minOfOrNull { it.tMinC },
        maxTemp7d = week.maxOfOrNull { it.tMaxC },
        et0_7d = et07d,
        balance7d = round1(rain7d - et07d),
        past30RainMm = weather.past30RainMm,
        sprayWindows = if (hourly.isNotEmpty()) findWorkWindows(FieldOperation.SPRAYING, hourly, 2, from) else emptyList(),
        harvestWindows = if (hourly.isNotEmpty()) findWorkWindows(FieldOperation.HARVESTING, hourly, 4, from) else emptyList(),
        fertilizeWindows = if (hourly.isNotEmpty()) findWorkWindows(FieldOperation.FERTILIZING, hourly, 3, from) else emptyList(),
        firstDryDay = week.firstOrNull { it.precipitationMm < 1 && (it.windMaxKmh ?: 0.0) < 25 }?.date,
    )
}

/** The usual day a crop is sown this season, from the planning calendar. */
fun usualSowingDate(crop: CropCode, today: String): String {
    val calendar = CROP_CALENDARS.getValue(crop)
    val year = today.take(4).toInt()
    val candidate = "$year-${calendar.sowingMonth.toString().padStart(2, '0')}-${calendar.sowingDay.toString().padStart(2, '0')}"
    return if (candidate < today) "${year + 1}${candidate.drop(4)}" else candidate
}

/** The expected harvest day: recorded sowing plus the crop calendar, or the progress estimate. */
fun expectedHarvest(facts: ParcelFacts): String? {
    val season = facts.season ?: return null
    val crop = facts.crop ?: return null
    season.harvestDate?.let { return it.take(10) }
    season.sowingDate?.let { return addDays(it, CROP_CALENDARS.getValue(crop).harvestDays) }
    return facts.progress?.estimatedHarvestDate?.take(10)
}

fun describeSeason(season: CopilotSeason) = "${season.crop.profile.name.lowercase()} (${season.year})"

internal fun roundInt(value: Double) = jsRoundInt(value)
