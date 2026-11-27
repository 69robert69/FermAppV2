package com.fermapp.api.integrations

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fermapp.api.common.cache.CacheService
import com.fermapp.api.common.http.UpstreamHttp
import com.fermapp.api.common.http.UpstreamHttp.UpstreamRequest
import com.fermapp.api.common.util.Js
import com.fermapp.api.domain.crops.CropRules
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.TreeMap
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder

/** Daily climate data from NASA POWER (integrations/nasaPower.ts). */
@Component
class NasaPowerClient(private val http: UpstreamHttp, private val cache: CacheService) {

    @JvmRecord
    data class PowerDay(
        val date: String,
        val tMeanC: Double,
        val tMaxC: Double,
        val tMinC: Double,
        val precipitationMm: Double,
        val radiationMjM2: Double?,
    )

    @JvmRecord
    data class ClimateYear(
        val year: Int,
        val precipitationMm: Double,
        val seasonPrecipitationMm: Double,
        val meanTempC: Double,
        val seasonTempC: Double,
        val radiationMjM2: Double,
        val gddBase10: Double,
        val days: Int,
    )

    @JvmRecord
    data class ClimateNormals(val seasonTempC: Double, val annualPrecipitationMm: Double, val gddBase10: Double)

    fun getPowerDaily(lat: Double, lng: Double, startDate: String, endDate: String): List<PowerDay> {
        val key = "nasa:" + Js.toFixed(lat, 2) + ":" + Js.toFixed(lng, 2) + ":" + startDate + ":" + endDate
        return cache.cached(key, 604_800, object : TypeReference<List<PowerDay>>() {}) {
            val url = UriComponentsBuilder.fromUriString("https://power.larc.nasa.gov/api/temporal/daily/point")
                .queryParam("parameters", "T2M,T2M_MAX,T2M_MIN,PRECTOTCORR,ALLSKY_SFC_SW_DWN")
                .queryParam("community", "AG")
                .queryParam("latitude", Js.toFixed(lat, 4))
                .queryParam("longitude", Js.toFixed(lng, 4))
                .queryParam("start", compact(startDate))
                .queryParam("end", compact(endDate))
                .queryParam("format", "JSON")
                .build().encode().toUriString()
            val parameter = http.fetchJson(UpstreamRequest.get("NASA POWER", url).timeout(Duration.ofSeconds(45)))
                .path("properties").path("parameter")
            parameter.path("T2M").fieldNames().asSequence().mapNotNull { day ->
                val tMean = value(parameter, "T2M", day)
                val tMax = value(parameter, "T2M_MAX", day)
                val tMin = value(parameter, "T2M_MIN", day)
                if (tMean == null || tMax == null || tMin == null) {
                    return@mapNotNull null
                }
                PowerDay(
                    day.substring(0, 4) + "-" + day.substring(4, 6) + "-" + day.substring(6, 8),
                    tMean, tMax, tMin, value(parameter, "PRECTOTCORR", day) ?: 0.0, value(parameter, "ALLSKY_SFC_SW_DWN", day),
                )
            }.toMutableList()
        }
    }

    /** Average of the last complete years, used for crop recommendations; null when no year is complete. */
    fun getClimateNormals(lat: Double, lng: Double): ClimateNormals? {
        val years = 5
        val lastYear = Instant.now().atZone(ZoneOffset.UTC).year - 1
        val days = getPowerDaily(lat, lng, "${lastYear - years + 1}-01-01", "$lastYear-12-31")
        val complete = summarizeClimateYears(days).filter { it.days >= 360 }
        if (complete.isEmpty()) {
            return null
        }
        val seasonTemp = complete.stream().mapToDouble { it.seasonTempC }.sum() / complete.size
        val precipitation = complete.stream().mapToDouble { it.precipitationMm }.sum() / complete.size
        val gdd = complete.stream().mapToDouble { it.gddBase10 }.sum() / complete.size
        return ClimateNormals(Js.round1(seasonTemp), Js.round(precipitation), Js.round(gdd))
    }

    companion object {
        private const val FILL_VALUE = -999.0

        private fun compact(date: String) = date.replace("-", "")

        private fun value(parameter: JsonNode, name: String, key: String): Double? {
            val node = parameter.path(name).get(key)
            if (node == null || !node.isNumber || node.asDouble() == FILL_VALUE) {
                return null
            }
            return node.asDouble()
        }

        private fun isSeason(date: String): Boolean = date.substring(5, 7).toInt() in 4..9

        /** DoubleStream.sum(), with its compensated summation, as the Java version used. */
        private inline fun sum(list: List<PowerDay>, crossinline pick: (PowerDay) -> Double): Double =
            list.stream().mapToDouble { pick(it) }.sum()

        /** Aggregates by calendar year; the growing season is April-September. */
        @JvmStatic
        fun summarizeClimateYears(days: List<PowerDay>): List<ClimateYear> {
            val byYear = TreeMap<Int, MutableList<PowerDay>>()
            for (day in days) {
                byYear.getOrPut(day.date.substring(0, 4).toInt()) { mutableListOf() } += day
            }
            return byYear.map { (year, items) ->
                val season = items.filter { isSeason(it.date) }
                ClimateYear(
                    year,
                    Js.round1(sum(items) { it.precipitationMm }),
                    Js.round1(sum(season) { it.precipitationMm }),
                    Js.round1(sum(items) { it.tMeanC } / items.size),
                    if (season.isEmpty()) 0.0 else Js.round1(sum(season) { it.tMeanC } / season.size),
                    Js.round1(sum(items) { it.radiationMjM2 ?: 0.0 }),
                    Js.round(sum(items) { CropRules.dailyGdd(it.tMaxC, it.tMinC, 10.0) }),
                    items.size,
                )
            }
        }
    }
}
