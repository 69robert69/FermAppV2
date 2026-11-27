package com.fermapp.api.integrations

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fermapp.api.common.cache.CacheService
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.http.UpstreamHttp
import com.fermapp.api.common.http.UpstreamHttp.UpstreamRequest
import com.fermapp.api.config.AppProperties
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import org.springframework.stereotype.Component

/** Sentinel-2 statistics and index images from Sentinel Hub / Copernicus Data Space (integrations/sentinel.ts). */
@Component
class SentinelClient(
    private val http: UpstreamHttp,
    private val cache: CacheService,
    private val properties: AppProperties,
    private val mapper: ObjectMapper,
) {

    @JvmRecord
    data class SentinelObservation(val date: String, val ndvi: Double, val ndre: Double?, val cloudPct: Double)

    fun isConfigured(): Boolean = properties.hasSentinel()

    private fun token(): String {
        if (!isConfigured()) {
            throw Errors.notConfigured("The satellite imagery service")
        }
        return cache.cached("sentinel:token", 240, String::class.java) {
            val form = "grant_type=client_credentials" +
                "&client_id=" + URLEncoder.encode(properties.sentinelClientId(), StandardCharsets.UTF_8) +
                "&client_secret=" + URLEncoder.encode(properties.sentinelClientSecret(), StandardCharsets.UTF_8)
            val data = http.fetchJson(
                UpstreamRequest.post(
                    SERVICE, properties.sentinelTokenUrl(), mapOf("Content-Type" to "application/x-www-form-urlencoded"), form,
                ),
            )
            data.path("access_token").asText()
        }
    }

    private fun json(value: Any): String = mapper.writeValueAsString(value)

    private fun bounds(geometry: JsonNode) = mapOf("geometry" to geometry, "properties" to mapOf("crs" to CRS84))

    fun fetchObservations(geometry: JsonNode, from: String, to: String): List<SentinelObservation> {
        val token = token()
        val body = mapOf(
            "input" to mapOf(
                "bounds" to bounds(geometry),
                "data" to listOf(mapOf("type" to "sentinel-2-l2a", "dataFilter" to mapOf("mosaickingOrder" to "leastCC"))),
            ),
            "aggregation" to mapOf(
                "timeRange" to mapOf("from" to "${from}T00:00:00Z", "to" to "${to}T23:59:59Z"),
                "aggregationInterval" to mapOf("of" to "P1D"),
                "evalscript" to STATS_SCRIPT,
                "resx" to RESOLUTION_DEG,
                "resy" to RESOLUTION_DEG,
            ),
        )
        val response = http.fetchJson(
            UpstreamRequest(
                SERVICE, "POST", properties.sentinelApiUrl() + "/api/v1/statistics",
                mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"), json(body),
                Duration.ofSeconds(60), 2,
            ),
        )
        return parseStatistics(response)
    }

    fun fetchIndexImage(geometry: JsonNode, date: String, index: String): ByteArray {
        val token = token()
        val body = mapOf(
            "input" to mapOf(
                "bounds" to bounds(geometry),
                "data" to listOf(
                    mapOf(
                        "type" to "sentinel-2-l2a",
                        "dataFilter" to mapOf("timeRange" to mapOf("from" to "${date}T00:00:00Z", "to" to "${date}T23:59:59Z")),
                    ),
                ),
            ),
            "output" to mapOf(
                "width" to 512,
                "height" to 512,
                "responses" to listOf(mapOf("identifier" to "default", "format" to mapOf("type" to "image/png"))),
            ),
            "evalscript" to imageScript(index),
        )
        return http.fetchBytes(
            UpstreamRequest(
                SERVICE, "POST", properties.sentinelApiUrl() + "/api/v1/process",
                mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json", "Accept" to "image/png"),
                json(body), Duration.ofSeconds(60), 2,
            ),
        )
    }

    companion object {
        private const val SERVICE = "Sentinel Hub"
        private const val CRS84 = "http://www.opengis.net/def/crs/OGC/1.3/CRS84"

        /** About 10 m, the resolution of the bands used for NDVI. */
        private const val RESOLUTION_DEG = 0.0001

        /** Observations with more than 60% of pixels covered by clouds are ignored. */
        private const val MAX_CLOUD_RATIO = 0.6

        private val STATS_SCRIPT = """
            //VERSION=3
            function setup() {
              return {
                input: [{ bands: ["B04", "B05", "B08", "SCL", "dataMask"] }],
                output: [
                  { id: "ndvi", bands: 1, sampleType: "FLOAT32" },
                  { id: "ndre", bands: 1, sampleType: "FLOAT32" },
                  { id: "dataMask", bands: 1 }
                ]
              };
            }
            function evaluatePixel(s) {
              var clear = (s.SCL === 4 || s.SCL === 5) ? 1 : 0;
              return {
                ndvi: [(s.B08 - s.B04) / (s.B08 + s.B04 + 1e-6)],
                ndre: [(s.B08 - s.B05) / (s.B08 + s.B05 + 1e-6)],
                dataMask: [s.dataMask * clear]
              };
            }
        """.trimIndent()

        private fun imageScript(index: String): String {
            val red = if (index == "NDVI") "s.B04" else "s.B05"
            return """
                //VERSION=3
                function setup() {
                  return { input: [{ bands: ["B04", "B05", "B08", "dataMask"] }], output: { bands: 4 } };
                }
                var ramp = [[0.1, 0xb5362a], [0.3, 0xd9a521], [0.5, 0xc7cf4a], [0.7, 0x5da342], [0.9, 0x1f6b35]];
                var viz = new ColorRampVisualizer(ramp);
                function evaluatePixel(s) {
                  var v = (s.B08 - $red) / (s.B08 + $red + 1e-6);
                  var c = viz.process(v);
                  return [c[0], c[1], c[2], s.dataMask];
                }
            """.trimIndent()
        }

        @JvmStatic
        fun parseStatistics(response: JsonNode): List<SentinelObservation> = response.path("data").mapNotNull { item ->
            val ndvi = item.path("outputs").path("ndvi").path("bands").path("B0").path("stats")
            val ndre = item.path("outputs").path("ndre").path("bands").path("B0").path("stats")
            if (ndvi.isMissingNode || !ndvi.path("mean").isNumber || ndvi.path("sampleCount").asDouble() == 0.0) {
                return@mapNotNull null
            }
            val cloudRatio = ndvi.path("noDataCount").asDouble() / ndvi.path("sampleCount").asDouble()
            if (cloudRatio > MAX_CLOUD_RATIO) {
                return@mapNotNull null
            }
            SentinelObservation(
                item.path("interval").path("from").asText().substring(0, 10),
                Math.round(ndvi.path("mean").asDouble() * 1000) / 1000.0,
                if (ndre.path("mean").isNumber) Math.round(ndre.path("mean").asDouble() * 1000) / 1000.0 else null,
                Math.round(cloudRatio * 100).toDouble(),
            )
        }
    }
}
