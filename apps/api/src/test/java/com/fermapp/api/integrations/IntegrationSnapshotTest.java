package com.fermapp.api.integrations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.Snapshots;
import com.fermapp.api.common.cache.CacheService;
import com.fermapp.api.common.error.ApiException;
import com.fermapp.api.common.http.UpstreamHttp;
import com.fermapp.api.common.http.UpstreamHttp.UpstreamRequest;
import com.fermapp.api.config.AppProperties;
import com.fermapp.api.config.JacksonConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Pins what the integration clients ask the external services (URL, method, headers, body)
 * and what they make of the answers, including values read back from the cache as JSON.
 * The upstream calls are answered from fixtures, so no network is used.
 */
@SpringBootTest(classes = {JacksonAutoConfiguration.class, JacksonConfig.class})
class IntegrationSnapshotTest {

    @Autowired
    private ObjectMapper mapper;

    private final List<Object> requests = new ArrayList<>();

    private static AppProperties properties(String openWeather, String sentinelId, String anthropic) {
        return new AppProperties("development", "*", null, "x".repeat(32), 900, 30, openWeather, sentinelId,
                sentinelId == null ? null : "secret &=", "https://token.example/oauth", "https://sh.example", anthropic,
                "claude-opus-5-5", null, null, null, null, "uploads", false, false, 600);
    }

    @SuppressWarnings("unchecked")
    private CacheService cache() {
        return new CacheService(properties(null, null, null), mock(ObjectProvider.class), mapper);
    }

    private Map<String, Object> describe(UpstreamRequest request) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", request.service());
        out.put("method", request.method());
        out.put("url", request.url());
        out.put("headers", new TreeMap<>(request.headers()));
        Object body = request.body();
        if (request.body() != null && request.body().startsWith("{")) {
            body = Snapshots.canonical(mapper.readTree(request.body()));
        }
        out.put("body", body);
        out.put("timeoutMs", request.timeout().toMillis());
        out.put("retries", request.retries());
        return out;
    }

    private UpstreamHttp upstream(Map<String, String> fixtures) throws Exception {
        UpstreamHttp http = mock(UpstreamHttp.class);
        when(http.fetchJson(any())).thenAnswer(invocation -> {
            UpstreamRequest request = invocation.getArgument(0);
            requests.add(describe(request));
            for (Map.Entry<String, String> fixture : fixtures.entrySet()) {
                if (request.url().contains(fixture.getKey())) {
                    return mapper.readTree(fixture.getValue());
                }
            }
            throw new AssertionError("no fixture for " + request.url());
        });
        when(http.fetchBytes(any())).thenAnswer(invocation -> {
            requests.add(describe(invocation.getArgument(0)));
            return "PNG".getBytes(StandardCharsets.UTF_8);
        });
        return http;
    }

    private static Object attempt(java.util.concurrent.Callable<Object> action) {
        try {
            return action.call();
        } catch (Exception error) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("class", error.getClass().getSimpleName());
            out.put("message", error.getMessage());
            if (error instanceof ApiException api) {
                out.put("code", api.code());
            }
            return out;
        }
    }

    private static final String OPEN_METEO_FORECAST = """
            {"timezone":"Europe/Bucharest","utc_offset_seconds":7200,
             "hourly":{"time":["2026-05-20T10:00","2026-05-20T11:00","2026-05-20T12:00","2026-05-20T13:00"],
                       "temperature_2m":[18.5,null,21,22.25],"relative_humidity_2m":[60,61,null,55],
                       "precipitation":[0,0.2,null,1.5],"precipitation_probability":[null,35,40,80],
                       "wind_speed_10m":[9,10,11,null],"shortwave_radiation":[500,null,620,610],
                       "evapotranspiration":[0.2,0.3,null,0.1],"et0_fao_evapotranspiration":[0.25,0.3,0.31,null],
                       "weather_code":[1,2,95.0,null]},
             "daily":{"time":["2026-05-20","2026-05-21","2026-05-22"],"temperature_2m_max":[24,null,26],
                      "temperature_2m_min":[12,13,14],"temperature_2m_mean":[18,19,null],"precipitation_sum":[null,3,0.4],
                      "shortwave_radiation_sum":[22.5,20,null],"et0_fao_evapotranspiration":[4.1,3.9,4.4],
                      "wind_speed_10m_max":[20,null,15],"relative_humidity_2m_mean":[65,70,null]}}
            """;

    private static final String NASA = """
            {"properties":{"parameter":{
              "T2M":{"20240115":-1.5,"20240716":24.2,"20240717":-999,"20250410":12.1},
              "T2M_MAX":{"20240115":3.0,"20240716":31.5,"20240717":30,"20250410":18.4},
              "T2M_MIN":{"20240115":-6.2,"20240716":17.0,"20240717":16,"20250410":5.3},
              "PRECTOTCORR":{"20240115":-999,"20240716":2.4,"20250410":0.0},
              "ALLSKY_SFC_SW_DWN":{"20240115":4.1,"20240716":-999,"20250410":"n/a"}}}}
            """;

    private static final String SOILGRIDS = """
            {"properties":{"layers":[
              {"name":"phh2o","unit_measure":{"d_factor":10},"depths":[
                {"label":"0-5cm","values":{"mean":68}},{"label":"5-15cm","values":{"mean":70}},
                {"label":"15-30cm","values":{"mean":72}},{"label":"30-60cm","values":{"mean":80}}]},
              {"name":"clay","unit_measure":{"d_factor":10},"depths":[
                {"label":"0-5cm","values":{"mean":280}},{"label":"5-15cm","values":{"mean":null}},
                {"label":"15-30cm","values":{"mean":300}}]},
              {"name":"sand","unit_measure":{"d_factor":10},"depths":[{"label":"0-5cm","values":{"mean":250}}]},
              {"name":"soc","unit_measure":{},"depths":[{"label":"5-15cm","values":{"mean":19.5}}]},
              {"name":"wv0033","unit_measure":{"d_factor":10},"depths":[{"label":"0-5cm","values":{}}]},
              {"name":"wv1500","unit_measure":{"d_factor":10},"depths":[{"label":"15-30cm","values":{"mean":180}}]}]}}
            """;

    private static final String SENTINEL_STATS = """
            {"data":[
              {"interval":{"from":"2026-05-01T00:00:00Z"},"outputs":{
                "ndvi":{"bands":{"B0":{"stats":{"mean":0.71234,"sampleCount":100,"noDataCount":10}}}},
                "ndre":{"bands":{"B0":{"stats":{"mean":0.3456}}}}}},
              {"interval":{"from":"2026-05-03T00:00:00Z"},"outputs":{
                "ndvi":{"bands":{"B0":{"stats":{"mean":0.5,"sampleCount":100,"noDataCount":70}}}}}},
              {"interval":{"from":"2026-05-05T00:00:00Z"},"outputs":{
                "ndvi":{"bands":{"B0":{"stats":{"mean":"NaN","sampleCount":100,"noDataCount":0}}}}}},
              {"interval":{"from":"2026-05-06T00:00:00Z"},"outputs":{
                "ndvi":{"bands":{"B0":{"stats":{"mean":0.4,"sampleCount":0,"noDataCount":0}}}}}},
              {"interval":{"from":"2026-05-08T00:00:00Z"},"outputs":{
                "ndvi":{"bands":{"B0":{"stats":{"mean":0.6005,"sampleCount":200,"noDataCount":120}}}},
                "ndre":{"bands":{"B0":{"stats":{"mean":null}}}}}},
              {"interval":{"from":"2026-05-09T00:00:00Z"},"outputs":{}}]}
            """;

    private static final String OWM_LIST = """
            [{"dt":1779264000,"main":{"temp":18.2,"humidity":60},"wind":{"speed":3},"pop":0.35,
              "rain":{"3h":1.5},"weather":[{"id":201}]},
             {"dt":1779274800,"main":{"temp":15,"humidity":70},"wind":{"speed":5.5},"snow":{"3h":0.6},
              "weather":[{"id":906}]},
             {"dt":1779285600,"main":{"temp":12},"wind":{},"weather":[]}]
            """;

    @Test
    void integrationsMatchTheSnapshot() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();

        UpstreamHttp meteoHttp = upstream(Map.of("api.open-meteo.com", OPEN_METEO_FORECAST, "archive-api.open-meteo.com",
                "{\"daily\":" + mapper.readTree(OPEN_METEO_FORECAST).path("daily") + "}"));
        OpenMeteoClient meteo = new OpenMeteoClient(meteoHttp, cache());
        OpenMeteoClient.Forecast forecast = meteo.getForecast(45.51755, 27.71874);
        OpenMeteoClient.Forecast cached = meteo.getForecast(45.51755, 27.71874);
        out.put("forecast", forecast);
        out.put("forecastFromCacheEqual", forecast.equals(cached));
        out.put("forecastFromCache", cached);
        out.put("localHour", OpenMeteoClient.localHour(forecast, Instant.parse("2026-05-20T09:45:00Z")));
        out.put("upcomingHours", OpenMeteoClient.upcomingHours(forecast, Instant.parse("2026-05-20T09:45:00Z")));
        out.put("history", meteo.getHistory(45.5, 27.7, "2026-05-01", "2026-05-31"));
        out.put("historyFromCache", meteo.getHistory(45.5, 27.7, "2026-05-01", "2026-05-31"));

        UpstreamHttp nasaHttp = upstream(Map.of("power.larc.nasa.gov", NASA));
        NasaPowerClient nasa = new NasaPowerClient(nasaHttp, cache());
        List<NasaPowerClient.PowerDay> days = nasa.getPowerDaily(45.12345, 27.98765, "2024-01-01", "2025-12-31");
        out.put("powerDays", days);
        out.put("powerDaysFromCache", nasa.getPowerDaily(45.12345, 27.98765, "2024-01-01", "2025-12-31"));
        List<NasaPowerClient.PowerDay> year = new ArrayList<>();
        for (int i = 0; i < 366; i++) {
            java.time.LocalDate date = java.time.LocalDate.of(2023, 1, 1).plusDays(i);
            year.add(new NasaPowerClient.PowerDay(date.toString(), 5 + (i % 30) * 0.7, 12 + (i % 25), (i % 20) - 4,
                    i % 7 == 0 ? 3.3 : 0, i % 5 == 0 ? null : 14.2));
        }
        out.put("climateYears", NasaPowerClient.summarizeClimateYears(year));
        out.put("climateYearsShort", NasaPowerClient.summarizeClimateYears(days));
        UpstreamHttp normalsHttp = mock(UpstreamHttp.class);
        when(normalsHttp.fetchJson(any())).thenAnswer(invocation -> {
            Map<String, Object> parameter = new LinkedHashMap<>();
            for (String name : List.of("T2M", "T2M_MAX", "T2M_MIN", "PRECTOTCORR", "ALLSKY_SFC_SW_DWN")) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (NasaPowerClient.PowerDay day : year) {
                    String key = day.date().replace("-", "");
                    values.put(key, switch (name) {
                        case "T2M" -> day.tMeanC();
                        case "T2M_MAX" -> day.tMaxC();
                        case "T2M_MIN" -> day.tMinC();
                        case "PRECTOTCORR" -> day.precipitationMm();
                        default -> day.radiationMjM2() == null ? -999 : day.radiationMjM2();
                    });
                }
                parameter.put(name, values);
            }
            return mapper.valueToTree(Map.of("properties", Map.of("parameter", parameter)));
        });
        out.put("climateNormals", new NasaPowerClient(normalsHttp, cache()).getClimateNormals(45, 27));
        // The years asked for depend on today, so these two calls are not recorded in "requests".
        UpstreamHttp shortHttp = mock(UpstreamHttp.class);
        when(shortHttp.fetchJson(any())).thenAnswer(invocation -> mapper.readTree(NASA));
        out.put("climateNormalsNone", new NasaPowerClient(shortHttp, cache()).getClimateNormals(45, 27));

        SoilGridsClient soil = new SoilGridsClient(upstream(Map.of("rest.isric.org", SOILGRIDS)));
        out.put("soil", soil.fetch(45.517554, 27.718749));
        out.put("soilEmpty", SoilGridsClient.parseSoilGrids(mapper.readTree("{\"properties\":{\"layers\":[]}}")));

        OpenWeatherClient owm = new OpenWeatherClient(upstream(Map.of("api.openweathermap.org",
                "{\"list\":[],\"city\":{\"timezone\":10800}}")), cache(), properties("owm key&x", null, null));
        out.put("owmConfigured", List.of(owm.isConfigured(),
                new OpenWeatherClient(mock(UpstreamHttp.class), cache(), properties(null, null, null)).isConfigured()));
        out.put("owmHours", owm.getHours(45.51755, 27.71874));
        JsonNode owmList = mapper.readTree(OWM_LIST);
        out.put("owmExpanded", OpenWeatherClient.expandOwmForecast(owmList, 10800, 1779264000L * 1000 + 5_400_000));
        out.put("owmExpandedPadding", OpenWeatherClient.expandOwmForecast(owmList, 0, 1779253200L * 1000 + 1000));
        out.put("owmExpandedEmpty", OpenWeatherClient.expandOwmForecast(mapper.readTree("[]"), 0, 0));

        UpstreamHttp sentinelHttp = upstream(Map.of("token.example", "{\"access_token\":\"tok-1\",\"expires_in\":300}",
                "/api/v1/statistics", SENTINEL_STATS));
        SentinelClient sentinel = new SentinelClient(sentinelHttp, cache(), properties(null, "client id", null), mapper);
        JsonNode geometry = mapper.readTree("{\"type\":\"Polygon\",\"coordinates\":[[[27,45],[27.01,45],[27.01,45.01],[27,45]]]}");
        out.put("sentinelConfigured", sentinel.isConfigured());
        out.put("sentinelObservations", sentinel.fetchObservations(geometry, "2026-05-01", "2026-05-10"));
        out.put("sentinelImageNdvi", new String(sentinel.fetchIndexImage(geometry, "2026-05-01", "NDVI"), StandardCharsets.UTF_8));
        out.put("sentinelImageNdre", new String(sentinel.fetchIndexImage(geometry, "2026-05-01", "NDRE"), StandardCharsets.UTF_8));
        out.put("sentinelStatistics", SentinelClient.parseStatistics(mapper.readTree(SENTINEL_STATS)));
        SentinelClient unconfigured = new SentinelClient(mock(UpstreamHttp.class), cache(), properties(null, null, null), mapper);
        out.put("sentinelUnconfigured", List.of(unconfigured.isConfigured(),
                attempt(() -> unconfigured.fetchObservations(geometry, "2026-05-01", "2026-05-02"))));

        AnthropicAssistantClient assistant = new AnthropicAssistantClient(properties(null, null, null));
        out.put("assistantUnconfigured", java.util.Arrays.asList(assistant.isConfigured(),
                assistant.ask("system", List.of(new AnthropicAssistantClient.Turn(true, "hi")), "question")));
        out.put("assistantTurn", new AnthropicAssistantClient.Turn(false, "answer"));

        out.put("requests", requests);
        Snapshots.assertMatches(mapper, "integrations", out);
    }
}
