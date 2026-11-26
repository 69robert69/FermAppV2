package ro.fermapp.app.features

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.AppJson
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.data.rememberParcels
import ro.fermapp.app.data.rememberQuery
import ro.fermapp.app.screens.WeatherPrefs
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.rememberNow
import ro.fermapp.domain.BriefCurrent
import ro.fermapp.domain.BriefDay
import ro.fermapp.domain.BriefHour
import ro.fermapp.domain.ConditionKind
import ro.fermapp.domain.FieldAlertSeverity
import ro.fermapp.domain.InsightTone
import ro.fermapp.domain.WeatherBrief
import ro.fermapp.domain.WeatherBriefInput
import ro.fermapp.domain.buildWeatherBrief
import ro.fermapp.domain.jsNum
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.toFixed
import ro.fermapp.domain.toJsIsoString
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The map's weather card, as features/weather: Open-Meteo forecast and the domain's weather brief. */

class Forecast(val utcOffsetSeconds: Int, val current: BriefCurrent?, val hours: List<BriefHour>, val days: List<BriefDay>)

private const val HOURLY = "temperature_2m,relative_humidity_2m,precipitation,precipitation_probability,wind_speed_10m,wind_gusts_10m,shortwave_radiation,et0_fao_evapotranspiration,weather_code"
private const val CURRENT = "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_gusts_10m,is_day"
private const val DAILY = "temperature_2m_max,temperature_2m_min,precipitation_sum"

private fun JsonObject.series(key: String): List<Double?> = (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull } ?: emptyList()

/** Downloads the forecast for a point; the farm's parcels share it. */
suspend fun loadForecast(lat: Double, lng: Double): Forecast {
    val url = "https://api.open-meteo.com/v1/forecast?latitude=${lat.toFixed(2)}&longitude=${lng.toFixed(2)}" +
        "&hourly=$HOURLY&daily=$DAILY&current=$CURRENT&forecast_days=8&past_days=1&timezone=auto&wind_speed_unit=kmh"
    val root = AppJson.parseToJsonElement(Api.http.get(url).bodyAsText()).jsonObject
    val hourly = root.getValue("hourly").jsonObject
    val times = hourly.getValue("time").jsonArray.map { it.jsonPrimitive.content }
    val temperature = hourly.series("temperature_2m")
    val humidity = hourly.series("relative_humidity_2m")
    val rain = hourly.series("precipitation")
    val probability = hourly.series("precipitation_probability")
    val wind = hourly.series("wind_speed_10m")
    val gust = hourly.series("wind_gusts_10m")
    val radiation = hourly.series("shortwave_radiation")
    val et0 = hourly.series("et0_fao_evapotranspiration")
    val code = hourly.series("weather_code")
    val hours = times.mapIndexedNotNull { i, time ->
        val t = temperature.getOrNull(i) ?: return@mapIndexedNotNull null
        BriefHour(
            time = time,
            temperatureC = t,
            humidityPct = humidity.getOrNull(i) ?: 0.0,
            precipitationMm = rain.getOrNull(i) ?: 0.0,
            precipitationProbabilityPct = probability.getOrNull(i),
            windKmh = wind.getOrNull(i) ?: 0.0,
            weatherCode = code.getOrNull(i)?.toInt(),
            gustKmh = gust.getOrNull(i),
            et0Mm = et0.getOrNull(i),
            radiationWm2 = radiation.getOrNull(i),
        )
    }
    val daily = root.getValue("daily").jsonObject
    val dayTimes = daily.getValue("time").jsonArray.map { it.jsonPrimitive.content }
    val tMax = daily.series("temperature_2m_max")
    val tMin = daily.series("temperature_2m_min")
    val dayRain = daily.series("precipitation_sum")
    val days = dayTimes.mapIndexedNotNull { i, date ->
        val max = tMax.getOrNull(i) ?: return@mapIndexedNotNull null
        val min = tMin.getOrNull(i) ?: return@mapIndexedNotNull null
        BriefDay(date, max, min, dayRain.getOrNull(i) ?: 0.0)
    }
    val current = (root["current"] as? JsonObject)?.let { c ->
        val time = (c["time"] as? JsonPrimitive)?.content ?: return@let null
        val t = (c["temperature_2m"] as? JsonPrimitive)?.doubleOrNull ?: return@let null
        BriefCurrent(
            time = time,
            temperatureC = t,
            humidityPct = (c["relative_humidity_2m"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            windKmh = (c["wind_speed_10m"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            gustKmh = (c["wind_gusts_10m"] as? JsonPrimitive)?.doubleOrNull,
            weatherCode = (c["weather_code"] as? JsonPrimitive)?.intOrNull,
            isDay = (c["is_day"] as? JsonPrimitive)?.intOrNull?.let { it == 1 },
        )
    }
    val offset = (root["utc_offset_seconds"] as? JsonPrimitive)?.intOrNull ?: 0
    return Forecast(offset, current, hours, days)
}

/** The current hour at the forecast's place, YYYY-MM-DDTHH:00. */
fun localHour(forecast: Forecast, now: Instant): String = (now + forecast.utcOffsetSeconds.seconds).toJsIsoString().take(13) + ":00"

/** The area-weighted centre of the farm's parcels, rounded to about a kilometre. */
@Composable
fun rememberFarmCentre(): Pair<Double, Double>? {
    val parcels = rememberParcels().data.orEmpty()
    return remember(parcels) {
        if (parcels.isEmpty()) return@remember null
        val area = parcels.sumOf { it.areaHa }
        fun weight(a: Double) = if (area > 0) a / area else 1.0 / parcels.size
        jsRound(parcels.sumOf { it.centerLat * weight(it.areaHa) }, 2) to jsRound(parcels.sumOf { it.centerLng * weight(it.areaHa) }, 2)
    }
}

@Composable
fun rememberWeatherBrief(): WeatherBrief? {
    val centre = rememberFarmCentre() ?: return null
    val (lat, lng) = centre
    val forecast = rememberQuery("weather/$lat/$lng") { loadForecast(lat, lng) }.data ?: return null
    // The brief is recomputed every minute, so "now" and the alerts move on between downloads.
    val now = rememberNow(60_000)
    return remember(forecast, now) { buildWeatherBrief(WeatherBriefInput(forecast.hours, forecast.days, forecast.current, localHour(forecast, now))) }
}

fun conditionIcon(kind: ConditionKind, isDay: Boolean = true): ImageVector = when (kind) {
    ConditionKind.CLEAR -> if (isDay) Icons.Outlined.WbSunny else Icons.Outlined.DarkMode
    ConditionKind.PARTLY_CLOUDY -> Icons.Outlined.WbCloudy
    ConditionKind.CLOUDY -> Icons.Outlined.Cloud
    ConditionKind.FOG -> Icons.Outlined.Dehaze
    ConditionKind.DRIZZLE, ConditionKind.RAIN -> Icons.Outlined.Umbrella
    ConditionKind.HEAVY_RAIN -> Icons.Outlined.WaterDrop
    ConditionKind.SNOW -> Icons.Outlined.AcUnit
    ConditionKind.SLEET -> Icons.Outlined.Grain
    ConditionKind.STORM, ConditionKind.HAIL -> Icons.Outlined.Thunderstorm
}

private fun toneColor(tone: InsightTone): Color = when (tone) {
    InsightTone.GOOD -> FarmColors.leaf
    InsightTone.INFO -> FarmColors.water
    InsightTone.CAUTION -> FarmColors.amber
    InsightTone.WARNING -> FarmColors.rust
}

private fun degrees(value: Double) = "${jsNum(jsRound(value))}°"

/** Collapsed pill, or the expanded card with today, five days, field advice and alerts. */
@Composable
fun WeatherWidget(wide: Boolean, modifier: Modifier = Modifier) {
    val prefs by WeatherPrefs.store.flow.collectAsState()
    if (!prefs.enabled) return
    val brief = rememberWeatherBrief() ?: return
    var expanded by remember { mutableStateOf(wide) }
    val shape = RoundedCornerShape(if (expanded) 16.dp else 22.dp)
    val auth = rememberAuth()
    Column(
        modifier.widthIn(max = 360.dp).clip(shape).background(FarmColors.loam.copy(alpha = 0.82f))
            .border(1.dp, FarmColors.chaff.copy(alpha = 0.14f), shape)
            .animateContentSize()
            .clickable { expanded = !expanded },
    ) {
        val current = brief.current
        if (!expanded) {
            Row(Modifier.height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(conditionIcon(current.condition.kind, current.isDay), current.condition.label, tint = FarmColors.wheat, modifier = Modifier.size(22.dp))
                Txt(degrees(current.temperatureC), TextVariant.Subtitle)
                brief.today?.let { Txt("${degrees(it.tMinC)} / ${degrees(it.tMaxC)}", TextVariant.Small, color = FarmColors.chaff) }
                if (brief.alerts.isNotEmpty()) Icon(Icons.Outlined.WarningAmber, "Weather alert", tint = FarmColors.rust, modifier = Modifier.size(18.dp))
            }
            return@Column
        }
        Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Icon(conditionIcon(current.condition.kind, current.isDay), null, tint = FarmColors.wheat, modifier = Modifier.size(34.dp))
                Column(Modifier.weight(1f)) {
                    Txt(degrees(current.temperatureC), TextVariant.Figure)
                    Txt("${current.condition.label} · ${auth.session?.farm?.name ?: "Farm"}", TextVariant.Small, maxLines = 1)
                }
                Icon(Icons.Outlined.ExpandLess, "Collapse", tint = FarmColors.straw)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                Txt("Humidity ${jsNum(jsRound(current.humidityPct))}%", TextVariant.Small, color = FarmColors.chaff)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(Icons.Outlined.Air, null, tint = FarmColors.straw, modifier = Modifier.size(14.dp))
                    Txt("${jsNum(jsRound(current.windKmh))} km/h", TextVariant.Small, color = FarmColors.chaff)
                }
                brief.today?.rainProbabilityPct?.let { Txt("Rain ${jsNum(jsRound(it))}%", TextVariant.Small, color = FarmColors.chaff) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                brief.days.forEach { day ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Txt(day.weekday, TextVariant.Small)
                        Icon(conditionIcon(day.condition.kind), day.condition.label, tint = FarmColors.wheat, modifier = Modifier.size(18.dp))
                        Txt(degrees(day.tMaxC), TextVariant.Small, color = FarmColors.chaff)
                        Txt(degrees(day.tMinC), TextVariant.Small)
                    }
                }
            }
            brief.alerts.forEach { alert ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background((if (alert.severity == FieldAlertSeverity.SEVERE) FarmColors.rust else FarmColors.amber).copy(alpha = 0.18f)).padding(Space.sm),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = if (alert.severity == FieldAlertSeverity.SEVERE) FarmColors.rust else FarmColors.amber, modifier = Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(alert.title, TextVariant.Small, color = FarmColors.chaff)
                        Txt(alert.message, TextVariant.Small)
                    }
                }
            }
            brief.insights.forEach { insight ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.Top) {
                    Txt("●", TextVariant.Small, color = toneColor(insight.tone))
                    Txt(insight.message, TextVariant.Small, color = FarmColors.chaff, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
