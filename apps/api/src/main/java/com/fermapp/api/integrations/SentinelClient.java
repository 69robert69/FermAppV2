package com.fermapp.api.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.common.cache.CacheService;
import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.http.UpstreamHttp;
import com.fermapp.api.common.http.UpstreamHttp.UpstreamRequest;
import com.fermapp.api.config.AppProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Sentinel-2 statistics and index images from Sentinel Hub / Copernicus Data Space (integrations/sentinel.ts). */
@Component
public class SentinelClient {

    private static final String SERVICE = "Sentinel Hub";
    private static final String CRS84 = "http://www.opengis.net/def/crs/OGC/1.3/CRS84";
    /** About 10 m, the resolution of the bands used for NDVI. */
    private static final double RESOLUTION_DEG = 0.0001;
    /** Observations with more than 60% of pixels covered by clouds are ignored. */
    private static final double MAX_CLOUD_RATIO = 0.6;

    private static final String STATS_SCRIPT = """
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
            }""";

    public record SentinelObservation(String date, double ndvi, Double ndre, double cloudPct) {
    }

    private final UpstreamHttp http;
    private final CacheService cache;
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public SentinelClient(UpstreamHttp http, CacheService cache, AppProperties properties, ObjectMapper mapper) {
        this.http = http;
        this.cache = cache;
        this.properties = properties;
        this.mapper = mapper;
    }

    public boolean isConfigured() {
        return properties.hasSentinel();
    }

    private String token() {
        if (!isConfigured()) {
            throw Errors.notConfigured("The satellite imagery service");
        }
        return cache.cached("sentinel:token", 240, String.class, () -> {
            String form = "grant_type=client_credentials"
                    + "&client_id=" + URLEncoder.encode(properties.sentinelClientId(), StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(properties.sentinelClientSecret(), StandardCharsets.UTF_8);
            JsonNode data = http.fetchJson(UpstreamRequest.post(SERVICE, properties.sentinelTokenUrl(),
                    Map.of("Content-Type", "application/x-www-form-urlencoded"), form));
            return data.path("access_token").asText();
        });
    }

    public static List<SentinelObservation> parseStatistics(JsonNode response) {
        List<SentinelObservation> result = new ArrayList<>();
        for (JsonNode item : response.path("data")) {
            JsonNode ndvi = item.path("outputs").path("ndvi").path("bands").path("B0").path("stats");
            JsonNode ndre = item.path("outputs").path("ndre").path("bands").path("B0").path("stats");
            if (ndvi.isMissingNode() || !ndvi.path("mean").isNumber() || ndvi.path("sampleCount").asDouble() == 0) {
                continue;
            }
            double cloudRatio = ndvi.path("noDataCount").asDouble() / ndvi.path("sampleCount").asDouble();
            if (cloudRatio > MAX_CLOUD_RATIO) {
                continue;
            }
            result.add(new SentinelObservation(
                    item.path("interval").path("from").asText().substring(0, 10),
                    Math.round(ndvi.path("mean").asDouble() * 1000) / 1000.0,
                    ndre.path("mean").isNumber() ? Math.round(ndre.path("mean").asDouble() * 1000) / 1000.0 : null,
                    Math.round(cloudRatio * 100)));
        }
        return result;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public List<SentinelObservation> fetchObservations(JsonNode geometry, String from, String to) {
        String token = token();
        Map<String, Object> body = Map.of(
                "input", Map.of(
                        "bounds", Map.of("geometry", geometry, "properties", Map.of("crs", CRS84)),
                        "data", List.of(Map.of("type", "sentinel-2-l2a", "dataFilter", Map.of("mosaickingOrder", "leastCC")))),
                "aggregation", Map.of(
                        "timeRange", Map.of("from", from + "T00:00:00Z", "to", to + "T23:59:59Z"),
                        "aggregationInterval", Map.of("of", "P1D"),
                        "evalscript", STATS_SCRIPT,
                        "resx", RESOLUTION_DEG,
                        "resy", RESOLUTION_DEG));
        JsonNode response = http.fetchJson(new UpstreamRequest(SERVICE, "POST", properties.sentinelApiUrl() + "/api/v1/statistics",
                Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json"), json(body),
                Duration.ofSeconds(60), 2));
        return parseStatistics(response);
    }

    private static String imageScript(String index) {
        String red = "NDVI".equals(index) ? "s.B04" : "s.B05";
        return """
                //VERSION=3
                function setup() {
                  return { input: [{ bands: ["B04", "B05", "B08", "dataMask"] }], output: { bands: 4 } };
                }
                var ramp = [[0.1, 0xb5362a], [0.3, 0xd9a521], [0.5, 0xc7cf4a], [0.7, 0x5da342], [0.9, 0x1f6b35]];
                var viz = new ColorRampVisualizer(ramp);
                function evaluatePixel(s) {
                  var v = (s.B08 - %s) / (s.B08 + %s + 1e-6);
                  var c = viz.process(v);
                  return [c[0], c[1], c[2], s.dataMask];
                }""".formatted(red, red);
    }

    public byte[] fetchIndexImage(JsonNode geometry, String date, String index) {
        String token = token();
        Map<String, Object> body = Map.of(
                "input", Map.of(
                        "bounds", Map.of("geometry", geometry, "properties", Map.of("crs", CRS84)),
                        "data", List.of(Map.of("type", "sentinel-2-l2a",
                                "dataFilter", Map.of("timeRange", Map.of("from", date + "T00:00:00Z", "to", date + "T23:59:59Z"))))),
                "output", Map.of("width", 512, "height", 512,
                        "responses", List.of(Map.of("identifier", "default", "format", Map.of("type", "image/png")))),
                "evalscript", imageScript(index));
        return http.fetchBytes(new UpstreamRequest(SERVICE, "POST", properties.sentinelApiUrl() + "/api/v1/process",
                Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json", "Accept", "image/png"),
                json(body), Duration.ofSeconds(60), 2));
    }
}
