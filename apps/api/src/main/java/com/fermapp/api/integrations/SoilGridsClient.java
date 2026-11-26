package com.fermapp.api.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fermapp.api.common.http.UpstreamHttp;
import com.fermapp.api.common.http.UpstreamHttp.UpstreamRequest;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.domain.soil.SoilRules.SoilInput;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Topsoil properties from ISRIC SoilGrids (integrations/soilGrids.ts). */
@Component
public class SoilGridsClient {

    /** Thickness of the analysed layers, in cm. */
    private static final Map<String, Integer> DEPTH_WEIGHTS = new LinkedHashMap<>();

    static {
        DEPTH_WEIGHTS.put("0-5cm", 5);
        DEPTH_WEIGHTS.put("5-15cm", 10);
        DEPTH_WEIGHTS.put("15-30cm", 15);
    }

    private final UpstreamHttp http;

    public SoilGridsClient(UpstreamHttp http) {
        this.http = http;
    }

    private static Double layerMean(JsonNode data, String name) {
        for (JsonNode layer : data.path("properties").path("layers")) {
            if (!name.equals(layer.path("name").asText())) {
                continue;
            }
            double factor = layer.path("unit_measure").path("d_factor").asDouble(1);
            double sum = 0;
            double weight = 0;
            for (JsonNode depth : layer.path("depths")) {
                Integer w = DEPTH_WEIGHTS.get(depth.path("label").asText());
                JsonNode mean = depth.path("values").path("mean");
                if (w == null || mean.isNull() || mean.isMissingNode()) {
                    continue;
                }
                sum += mean.asDouble() / factor * w;
                weight += w;
            }
            return weight > 0 ? Js.round2(sum / weight) : null;
        }
        return null;
    }

    /** Null for points without data (water, urban areas), which cannot be classified. */
    public static SoilInput parseSoilGrids(JsonNode data) {
        Double ph = layerMean(data, "phh2o");
        Double clay = layerMean(data, "clay");
        Double sand = layerMean(data, "sand");
        Double carbon = layerMean(data, "soc");
        if (ph == null || clay == null || sand == null || carbon == null) {
            return null;
        }
        return new SoilInput(ph, clay, sand, carbon, layerMean(data, "wv0033"), layerMean(data, "wv1500"));
    }

    public SoilInput fetch(double lat, double lng) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString("https://rest.isric.org/soilgrids/v2.0/properties/query")
                .queryParam("lon", Js.toFixed(lng, 5))
                .queryParam("lat", Js.toFixed(lat, 5))
                .queryParam("value", "mean");
        for (String property : new String[] {"phh2o", "clay", "sand", "soc", "wv0033", "wv1500"}) {
            builder.queryParam("property", property);
        }
        for (String depth : DEPTH_WEIGHTS.keySet()) {
            builder.queryParam("depth", depth);
        }
        JsonNode data = http.fetchJson(UpstreamRequest.get("SoilGrids", builder.build().encode().toUriString())
                .timeout(Duration.ofSeconds(30)));
        return parseSoilGrids(data);
    }
}
