package com.fermapp.api.integrations

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fermapp.api.common.cache.CacheService
import com.fermapp.api.common.http.UpstreamHttp
import com.fermapp.api.common.http.UpstreamHttp.UpstreamRequest
import com.fermapp.api.common.util.Js
import com.fermapp.api.common.util.toIsoString
import com.fermapp.api.config.AppProperties
import com.fermapp.api.domain.weather.WeatherRules.ForecastHour
import java.time.Instant
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder

/** The OpenWeatherMap 5-day forecast, used for weather alerts when a key is configured. */
@Component
class OpenWeatherClient(
    private val http: UpstreamHttp,
    private val cache: CacheService,
    private val properties: AppProperties,
) {

    fun isConfigured(): Boolean = properties.hasOpenWeather()

    fun getHours(lat: Double, lng: Double): List<ForecastHour> {
        val key = "owm:" + Js.toFixed(lat, 2) + ":" + Js.toFixed(lng, 2)
        return cache.cached(key, 1800, object : TypeReference<List<ForecastHour>>() {}) {
            val url = UriComponentsBuilder.fromUriString("https://api.openweathermap.org/data/2.5/forecast")
                .queryParam("lat", Js.toFixed(lat, 4))
                .queryParam("lon", Js.toFixed(lng, 4))
                .queryParam("units", "metric")
                .queryParam("appid", properties.openweatherApiKey() ?: "")
                .build().encode().toUriString()
            val data = http.fetchJson(UpstreamRequest.get("OpenWeatherMap", url))
            expandOwmForecast(data.path("list"), data.path("city").path("timezone").asLong(), System.currentTimeMillis())
        }
    }

    companion object {
        private const val HOUR_S = 3600L

        /** OpenWeatherMap codes: group 2xx is thunderstorm; 906 is hail. */
        private fun toWmoCode(weather: JsonNode?): Int? {
            if (weather == null || !weather.isArray || weather.isEmpty || !weather.get(0).has("id")) {
                return null
            }
            val id = weather.get(0).path("id").asInt()
            return when {
                id == 906 -> 96
                id in 200..299 -> 95
                else -> null
            }
        }

        private fun localTime(timestampS: Long, timezoneOffsetS: Long): String =
            Instant.ofEpochSecond(timestampS + timezoneOffsetS).toIsoString().substring(0, 16)

        /** Converts the 3-hour steps into hours, starting from the current hour, so the "in N hours" message is exact. */
        @JvmStatic
        fun expandOwmForecast(list: JsonNode, timezoneOffsetS: Long, nowMs: Long): List<ForecastHour> {
            val hours = mutableListOf<ForecastHour>()
            val currentHourS = Math.floorDiv(nowMs / 1000, HOUR_S) * HOUR_S
            for (entry in list) {
                val precipitation = (entry.path("rain").path("3h").asDouble(0.0) + entry.path("snow").path("3h").asDouble(0.0)) / 3
                val dt = entry.path("dt").asLong()
                for (step in 0 until 3) {
                    val timestamp = dt + step * HOUR_S
                    if (timestamp < currentHourS) {
                        continue
                    }
                    hours += ForecastHour(
                        localTime(timestamp, timezoneOffsetS), entry.path("main").path("temp").asDouble(),
                        entry.path("main").path("humidity").asDouble(), precipitation,
                        Math.round(entry.path("pop").asDouble(0.0) * 100).toDouble(),
                        entry.path("wind").path("speed").asDouble() * 3.6, toWmoCode(entry.get("weather")),
                    )
                }
            }
            // The first step may start in the future; the missing hours are filled in without rain.
            if (!list.isEmpty && list.get(0).path("dt").asLong() > currentHourS) {
                val first = list.get(0)
                val missing = Math.round((first.path("dt").asLong() - currentHourS) / HOUR_S.toDouble())
                val padding = (0 until missing).map { i ->
                    ForecastHour(
                        localTime(currentHourS + i * HOUR_S, timezoneOffsetS),
                        first.path("main").path("temp").asDouble(), first.path("main").path("humidity").asDouble(), 0.0, 0.0,
                        first.path("wind").path("speed").asDouble() * 3.6, null,
                    )
                }
                hours.addAll(0, padding)
            }
            return hours
        }
    }
}
