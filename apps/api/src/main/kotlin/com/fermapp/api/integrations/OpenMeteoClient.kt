package com.fermapp.api.integrations

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fermapp.api.common.cache.CacheService
import com.fermapp.api.common.http.UpstreamHttp
import com.fermapp.api.common.http.UpstreamHttp.UpstreamRequest
import com.fermapp.api.common.util.Js
import com.fermapp.api.common.util.toIsoString
import com.fermapp.api.domain.weather.WeatherRules.HourlyWeather
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder

/** Forecast and weather history from Open-Meteo (integrations/openMeteo.ts). */
@Component
class OpenMeteoClient(private val http: UpstreamHttp, private val cache: CacheService) {

    /**
     * One forecast hour, local time at the parcel. The properties are private so that the
     * record-style methods (as the HourlyWeather interface declares them) are the only accessors.
     */
    @JsonPropertyOrder(
        "time", "temperatureC", "humidityPct", "precipitationMm", "precipitationProbabilityPct", "windKmh", "weatherCode",
        "radiationWm2", "evapotranspirationMm", "et0Mm",
    )
    data class ForecastHourFull(
        private val time: String,
        private val temperatureC: Double,
        private val humidityPct: Double,
        private val precipitationMm: Double,
        private val precipitationProbabilityPct: Double?,
        private val windKmh: Double,
        private val weatherCode: Int?,
        private val radiationWm2: Double?,
        private val evapotranspirationMm: Double?,
        private val et0Mm: Double?,
    ) : HourlyWeather {
        @JsonProperty("time")
        override fun time() = time

        @JsonProperty("temperatureC")
        override fun temperatureC() = temperatureC

        @JsonProperty("humidityPct")
        override fun humidityPct() = humidityPct

        @JsonProperty("precipitationMm")
        override fun precipitationMm() = precipitationMm

        @JsonProperty("precipitationProbabilityPct")
        override fun precipitationProbabilityPct() = precipitationProbabilityPct

        @JsonProperty("windKmh")
        override fun windKmh() = windKmh

        @JsonProperty("weatherCode")
        override fun weatherCode() = weatherCode

        @JsonProperty("radiationWm2")
        fun radiationWm2() = radiationWm2

        @JsonProperty("evapotranspirationMm")
        fun evapotranspirationMm() = evapotranspirationMm

        @JsonProperty("et0Mm")
        fun et0Mm() = et0Mm
    }

    @JvmRecord
    data class WeatherDay(
        val date: String,
        val tMaxC: Double,
        val tMinC: Double,
        val tMeanC: Double?,
        val precipitationMm: Double,
        val radiationMjM2: Double?,
        val et0Mm: Double?,
        val windMaxKmh: Double?,
        val humidityPct: Double?,
    )

    @JvmRecord
    data class Forecast(
        val timezone: String,
        val utcOffsetSeconds: Int,
        val hours: List<ForecastHourFull>,
        val days: List<WeatherDay>,
    )

    fun getForecast(lat: Double, lng: Double): Forecast =
        cache.cached("weather:forecast:" + coord(lat) + ":" + coord(lng), 1800, Forecast::class.java) {
            val url = UriComponentsBuilder.fromUriString("https://api.open-meteo.com/v1/forecast")
                .queryParam("latitude", coord(lat))
                .queryParam("longitude", coord(lng))
                .queryParam("hourly", HOURLY)
                .queryParam("daily", DAILY)
                // The current day plus the next 14 full days.
                .queryParam("forecast_days", "15")
                .queryParam("past_days", "1")
                .queryParam("timezone", "auto")
                .queryParam("wind_speed_unit", "kmh")
                .build().encode().toUriString()
            val data = http.fetchJson(UpstreamRequest.get(SERVICE, url))
            val hourly = data.path("hourly")
            val hours = (0 until hourly.path("time").size()).mapNotNull { i ->
                val temperature = at(hourly, "temperature_2m", i) ?: return@mapNotNull null
                ForecastHourFull(
                    hourly.path("time").get(i).asText(), temperature, orZero(at(hourly, "relative_humidity_2m", i)),
                    orZero(at(hourly, "precipitation", i)), at(hourly, "precipitation_probability", i),
                    orZero(at(hourly, "wind_speed_10m", i)), at(hourly, "weather_code", i)?.toInt(),
                    at(hourly, "shortwave_radiation", i), at(hourly, "evapotranspiration", i),
                    at(hourly, "et0_fao_evapotranspiration", i),
                )
            }
            Forecast(data.path("timezone").asText(), data.path("utc_offset_seconds").asInt(), hours, mapDays(data.path("daily")))
        }

    fun getHistory(lat: Double, lng: Double, startDate: String, endDate: String): List<WeatherDay> {
        val key = "weather:history:" + coord(lat) + ":" + coord(lng) + ":" + startDate + ":" + endDate
        return cache.cached(key, 43_200, object : TypeReference<List<WeatherDay>>() {}) {
            val url = UriComponentsBuilder.fromUriString("https://archive-api.open-meteo.com/v1/archive")
                .queryParam("latitude", coord(lat))
                .queryParam("longitude", coord(lng))
                .queryParam("start_date", startDate)
                .queryParam("end_date", endDate)
                .queryParam("daily", DAILY)
                .queryParam("timezone", "auto")
                .queryParam("wind_speed_unit", "kmh")
                .build().encode().toUriString()
            mapDays(http.fetchJson(UpstreamRequest.get(SERVICE, url).timeout(Duration.ofSeconds(25))).path("daily"))
        }
    }

    companion object {
        private const val SERVICE = "Open-Meteo"
        private val HOURLY = listOf(
            "temperature_2m", "relative_humidity_2m", "precipitation", "precipitation_probability", "wind_speed_10m",
            "shortwave_radiation", "evapotranspiration", "et0_fao_evapotranspiration", "weather_code",
        ).joinToString(",")
        private val DAILY = listOf(
            "temperature_2m_max", "temperature_2m_min", "temperature_2m_mean", "precipitation_sum", "shortwave_radiation_sum",
            "et0_fao_evapotranspiration", "wind_speed_10m_max", "relative_humidity_2m_mean",
        ).joinToString(",")

        private fun coord(value: Double) = Js.toFixed(value, 2)

        private fun at(series: JsonNode, name: String, index: Int): Double? {
            val values = series.get(name)
            if (values == null || !values.isArray || index >= values.size() || values.get(index).isNull) {
                return null
            }
            return values.get(index).asDouble()
        }

        private fun orZero(value: Double?) = value ?: 0.0

        private fun mapDays(daily: JsonNode): List<WeatherDay> {
            val time = daily.path("time")
            return (0 until time.size()).mapNotNull { i ->
                val tMax = at(daily, "temperature_2m_max", i)
                val tMin = at(daily, "temperature_2m_min", i)
                if (tMax == null || tMin == null) {
                    return@mapNotNull null
                }
                WeatherDay(
                    time.get(i).asText(), tMax, tMin, at(daily, "temperature_2m_mean", i),
                    orZero(at(daily, "precipitation_sum", i)), at(daily, "shortwave_radiation_sum", i),
                    at(daily, "et0_fao_evapotranspiration", i), at(daily, "wind_speed_10m_max", i),
                    at(daily, "relative_humidity_2m_mean", i),
                )
            }
        }

        /** Current hour in the parcel's time zone, in the format used by the forecast. */
        @JvmStatic
        fun localHour(forecast: Forecast, now: Instant): String =
            now.plusSeconds(forecast.utcOffsetSeconds.toLong()).toIsoString().substring(0, 13) + ":00"

        @JvmStatic
        fun upcomingHours(forecast: Forecast, now: Instant): List<ForecastHourFull> {
            val current = localHour(forecast, now)
            return forecast.hours.filter { it.time() >= current }
        }
    }
}
