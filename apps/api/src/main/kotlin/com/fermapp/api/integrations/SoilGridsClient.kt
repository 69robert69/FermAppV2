package com.fermapp.api.integrations

import com.fasterxml.jackson.databind.JsonNode
import com.fermapp.api.common.http.UpstreamHttp
import com.fermapp.api.common.http.UpstreamHttp.UpstreamRequest
import com.fermapp.api.common.util.Js
import com.fermapp.api.domain.soil.SoilRules.SoilInput
import java.time.Duration
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder

/** Topsoil properties from ISRIC SoilGrids (integrations/soilGrids.ts). */
@Component
class SoilGridsClient(private val http: UpstreamHttp) {

    fun fetch(lat: Double, lng: Double): SoilInput? {
        val builder = UriComponentsBuilder.fromUriString("https://rest.isric.org/soilgrids/v2.0/properties/query")
            .queryParam("lon", Js.toFixed(lng, 5))
            .queryParam("lat", Js.toFixed(lat, 5))
            .queryParam("value", "mean")
        for (property in listOf("phh2o", "clay", "sand", "soc", "wv0033", "wv1500")) {
            builder.queryParam("property", property)
        }
        for (depth in DEPTH_WEIGHTS.keys) {
            builder.queryParam("depth", depth)
        }
        val data = http.fetchJson(
            UpstreamRequest.get("SoilGrids", builder.build().encode().toUriString()).timeout(Duration.ofSeconds(30)),
        )
        return parseSoilGrids(data)
    }

    companion object {
        /** Thickness of the analysed layers, in cm. */
        private val DEPTH_WEIGHTS = linkedMapOf("0-5cm" to 5, "5-15cm" to 10, "15-30cm" to 15)

        private fun layerMean(data: JsonNode, name: String): Double? {
            val layer = data.path("properties").path("layers").firstOrNull { it.path("name").asText() == name } ?: return null
            val factor = layer.path("unit_measure").path("d_factor").asDouble(1.0)
            var sum = 0.0
            var weight = 0.0
            for (depth in layer.path("depths")) {
                val w = DEPTH_WEIGHTS[depth.path("label").asText()]
                val mean = depth.path("values").path("mean")
                if (w == null || mean.isNull || mean.isMissingNode) {
                    continue
                }
                sum += mean.asDouble() / factor * w
                weight += w
            }
            return if (weight > 0) Js.round2(sum / weight) else null
        }

        /** Null for points without data (water, urban areas), which cannot be classified. */
        @JvmStatic
        fun parseSoilGrids(data: JsonNode): SoilInput? {
            val ph = layerMean(data, "phh2o")
            val clay = layerMean(data, "clay")
            val sand = layerMean(data, "sand")
            val carbon = layerMean(data, "soc")
            if (ph == null || clay == null || sand == null || carbon == null) {
                return null
            }
            return SoilInput(ph, clay, sand, carbon, layerMean(data, "wv0033"), layerMean(data, "wv1500"))
        }
    }
}
