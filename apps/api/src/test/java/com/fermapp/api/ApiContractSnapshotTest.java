package com.fermapp.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fermapp.api.common.http.UpstreamHttp;
import com.fermapp.api.common.http.UpstreamHttp.UpstreamRequest;
import com.fermapp.api.integrations.ExpoPushClient;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The API contract, end to end: the whole application on a real PostgreSQL (Flyway migrations and
 * the demo farm), called through every route the way the phone does, writes included, with the
 * external services answered from generated fixtures. Every status, the main headers and every
 * body are compared with a snapshot. PDF reports are compared by their text and Excel reports by
 * their cells.
 *
 * <p>Values that depend on when the test runs are normalised: generated ids and tokens become
 * placeholders, and times close to now become offsets from now. Some answers still depend on the
 * hour of the day, because the application reads the real clock: weather alerts say "in N hours"
 * and work windows start at the next suitable hour. The snapshot is therefore not committed: it is
 * a baseline recorded from the last commit and compared, within the same hour, with the working
 * tree. {@code scripts/api-contract-check.sh} does both steps. The test runs only with
 * {@code -Dapi.contract=true}.</p>
 */
@EnabledIfSystemProperty(named = "api.contract", matches = "true")
@SpringBootTest(properties = {
    // Every setting is given here, so that apps/api/.env (read by application.yml) changes nothing.
    "DATABASE_URL=", "REDIS_URL=", "JDBC_DATABASE_URL=",
    "fermapp.environment=test",
    "fermapp.cors-origins=http://localhost:8081",
    "fermapp.redis-url=",
    "fermapp.jwt-access-secret=contract-test-secret-contract-test-secret",
    "fermapp.jwt-access-ttl-seconds=900",
    "fermapp.refresh-token-ttl-days=30",
    "fermapp.openweather-api-key=",
    "fermapp.sentinel-client-id=contract-client",
    "fermapp.sentinel-client-secret=contract-secret",
    "fermapp.sentinel-token-url=https://token.example/oauth",
    "fermapp.sentinel-api-url=https://sh.example",
    "fermapp.anthropic-api-key=",
    "fermapp.anthropic-model=claude-opus-5-5",
    "fermapp.mqtt-url=",
    "fermapp.mqtt-username=",
    "fermapp.mqtt-password=",
    "fermapp.expo-access-token=",
    "fermapp.upload-dir=target/contract-uploads",
    "fermapp.jobs-enabled=false",
    "fermapp.seed-demo-data=true",
    "fermapp.global-rate-limit-per-minute=600",
})
@AutoConfigureMockMvc
class ApiContractSnapshotTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Bucharest");
    private static final Instant STARTED = Instant.now();
    private static EmbeddedPostgres postgres;

    /** Started here rather than in a static block, so a disabled test starts no database. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws IOException {
        // fieldStatus and a few other rules count days in the server's zone.
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
        if (postgres == null) {
            postgres = EmbeddedPostgres.builder().start();
        }
        String url = postgres.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    static void stopDatabase() throws IOException {
        if (postgres != null) {
            postgres.close();
            postgres = null;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private UpstreamHttp upstream;

    @MockitoBean
    private ExpoPushClient push;

    private final Map<String, Object> calls = new LinkedHashMap<>();
    private final List<Object> upstreamCalls = new ArrayList<>();
    private int step;

    // ------------------------------------------------------------------ fixtures

    private static String query(String url, String name) {
        Matcher matcher = Pattern.compile("[?&]" + name + "=([^&]*)").matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    private ObjectNode openMeteoForecast() {
        LocalDate today = LocalDate.now(ZONE);
        int offset = ZONE.getRules().getOffset(Instant.now()).getTotalSeconds();
        ObjectNode root = mapper.createObjectNode();
        root.put("timezone", "Europe/Bucharest");
        root.put("utc_offset_seconds", offset);
        ObjectNode hourly = root.putObject("hourly");
        String[] series = {"temperature_2m", "relative_humidity_2m", "precipitation", "precipitation_probability",
            "wind_speed_10m", "shortwave_radiation", "evapotranspiration", "et0_fao_evapotranspiration", "weather_code"};
        ArrayNode time = hourly.putArray("time");
        Map<String, ArrayNode> values = new LinkedHashMap<>();
        for (String name : series) {
            values.put(name, hourly.putArray(name));
        }
        for (int h = 0; h < 16 * 24; h++) {
            LocalDateTime at = today.minusDays(1).atStartOfDay().plusHours(h);
            int hour = at.getHour();
            time.add(at.toString());
            values.get("temperature_2m").add(4 + hour * 0.75 + (h / 24) % 4);
            values.get("relative_humidity_2m").add(50 + (h * 7) % 45);
            values.get("precipitation").add(h % 29 == 0 ? 1.4 : h % 31 == 0 ? 0.3 : 0);
            if (h % 6 == 0) {
                values.get("precipitation_probability").addNull();
            } else {
                values.get("precipitation_probability").add((h * 13) % 90);
            }
            values.get("wind_speed_10m").add(4 + (h * 5) % 21);
            values.get("shortwave_radiation").add(hour < 7 || hour > 17 ? 0 : 120 + hour * 30);
            values.get("evapotranspiration").add(hour < 7 || hour > 17 ? 0 : 0.18);
            values.get("et0_fao_evapotranspiration").add(hour < 7 || hour > 17 ? 0.01 : 0.21);
            values.get("weather_code").add(h == 40 ? 95 : h == 70 ? 96 : (h % 4));
        }
        root.set("daily", daily(today.minusDays(1), today.plusDays(14)));
        return root;
    }

    private ObjectNode daily(LocalDate from, LocalDate to) {
        ObjectNode daily = mapper.createObjectNode();
        ArrayNode time = daily.putArray("time");
        String[] series = {"temperature_2m_max", "temperature_2m_min", "temperature_2m_mean", "precipitation_sum",
            "shortwave_radiation_sum", "et0_fao_evapotranspiration", "wind_speed_10m_max", "relative_humidity_2m_mean"};
        Map<String, ArrayNode> values = new LinkedHashMap<>();
        for (String name : series) {
            values.put(name, daily.putArray(name));
        }
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            int n = (int) (day.toEpochDay() % 97);
            int season = day.getMonthValue();
            time.add(day.toString());
            double max = 6 + Math.abs(7 - season) * -1.5 + 16 + n % 7;
            values.get("temperature_2m_max").add(max);
            values.get("temperature_2m_min").add(max - 9 - n % 4);
            values.get("temperature_2m_mean").add(max - 5);
            values.get("precipitation_sum").add(n % 5 == 0 ? 6.5 : n % 3 == 0 ? 1.2 : 0);
            values.get("shortwave_radiation_sum").add(8 + n % 13);
            values.get("et0_fao_evapotranspiration").add(1.5 + (n % 9) * 0.4);
            values.get("wind_speed_10m_max").add(10 + n % 17);
            values.get("relative_humidity_2m_mean").add(55 + n % 30);
        }
        return daily;
    }

    private ObjectNode nasaPower(String start, String end) {
        LocalDate from = LocalDate.parse(start.substring(0, 4) + "-" + start.substring(4, 6) + "-" + start.substring(6, 8));
        LocalDate to = LocalDate.parse(end.substring(0, 4) + "-" + end.substring(4, 6) + "-" + end.substring(6, 8));
        ObjectNode root = mapper.createObjectNode();
        ObjectNode parameter = root.putObject("properties").putObject("parameter");
        ObjectNode t2m = parameter.putObject("T2M");
        ObjectNode max = parameter.putObject("T2M_MAX");
        ObjectNode min = parameter.putObject("T2M_MIN");
        ObjectNode rain = parameter.putObject("PRECTOTCORR");
        ObjectNode sun = parameter.putObject("ALLSKY_SFC_SW_DWN");
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            String key = day.toString().replace("-", "");
            double seasonal = 11 - 12 * Math.cos((day.getDayOfYear() - 15) / 365.0 * 2 * Math.PI);
            double mean = Math.round(seasonal * 100) / 100.0;
            t2m.put(key, mean);
            max.put(key, Math.round((mean + 6) * 100) / 100.0);
            min.put(key, Math.round((mean - 6) * 100) / 100.0);
            rain.put(key, day.getDayOfMonth() % 4 == 0 ? 6.2 : 0.4);
            if (day.getDayOfMonth() == 13) {
                sun.put(key, -999);
            } else {
                sun.put(key, 14.5);
            }
        }
        return root;
    }

    private static final String SOILGRIDS = """
            {"properties":{"layers":[
              {"name":"phh2o","unit_measure":{"d_factor":10},"depths":[
                {"label":"0-5cm","values":{"mean":68}},{"label":"5-15cm","values":{"mean":70}},{"label":"15-30cm","values":{"mean":72}}]},
              {"name":"clay","unit_measure":{"d_factor":10},"depths":[
                {"label":"0-5cm","values":{"mean":280}},{"label":"5-15cm","values":{"mean":290}},{"label":"15-30cm","values":{"mean":300}}]},
              {"name":"sand","unit_measure":{"d_factor":10},"depths":[
                {"label":"0-5cm","values":{"mean":250}},{"label":"5-15cm","values":{"mean":240}},{"label":"15-30cm","values":{"mean":230}}]},
              {"name":"soc","unit_measure":{"d_factor":10},"depths":[
                {"label":"0-5cm","values":{"mean":230}},{"label":"5-15cm","values":{"mean":200}},{"label":"15-30cm","values":{"mean":160}}]},
              {"name":"wv0033","unit_measure":{"d_factor":10},"depths":[{"label":"0-5cm","values":{"mean":380}}]},
              {"name":"wv1500","unit_measure":{"d_factor":10},"depths":[{"label":"0-5cm","values":{"mean":190}}]}]}}
            """;

    private static final String SENTINEL_STATS = """
            {"data":[
              {"interval":{"from":"2026-04-02T00:00:00Z"},"outputs":{
                "ndvi":{"bands":{"B0":{"stats":{"mean":0.41,"sampleCount":100,"noDataCount":5}}}},
                "ndre":{"bands":{"B0":{"stats":{"mean":0.18}}}}}},
              {"interval":{"from":"2026-04-20T00:00:00Z"},"outputs":{
                "ndvi":{"bands":{"B0":{"stats":{"mean":0.63,"sampleCount":100,"noDataCount":20}}}},
                "ndre":{"bands":{"B0":{"stats":{"mean":0.29}}}}}},
              {"interval":{"from":"2026-05-05T00:00:00Z"},"outputs":{
                "ndvi":{"bands":{"B0":{"stats":{"mean":0.77,"sampleCount":100,"noDataCount":10}}}}}}]}
            """;

    private JsonNode answer(UpstreamRequest request) throws IOException {
        String url = request.url();
        Map<String, Object> seen = new LinkedHashMap<>();
        seen.put("service", request.service());
        seen.put("method", request.method());
        seen.put("url", url);
        upstreamCalls.add(seen);
        if (url.contains("api.open-meteo.com/v1/forecast")) {
            return openMeteoForecast();
        }
        if (url.contains("archive-api.open-meteo.com")) {
            ObjectNode root = mapper.createObjectNode();
            root.set("daily", daily(LocalDate.parse(query(url, "start_date")), LocalDate.parse(query(url, "end_date"))));
            return root;
        }
        if (url.contains("power.larc.nasa.gov")) {
            return nasaPower(query(url, "start"), query(url, "end"));
        }
        if (url.contains("rest.isric.org")) {
            return mapper.readTree(SOILGRIDS);
        }
        if (url.contains("token.example")) {
            return mapper.readTree("{\"access_token\":\"sentinel-token\"}");
        }
        if (url.contains("/api/v1/statistics")) {
            return mapper.readTree(SENTINEL_STATS);
        }
        throw new IllegalStateException("No fixture for " + url);
    }

    private void stubUpstream() throws IOException {
        when(upstream.fetchJson(any())).thenAnswer(invocation -> answer(invocation.getArgument(0)));
        when(upstream.fetchBytes(any())).thenAnswer(invocation -> {
            UpstreamRequest request = invocation.getArgument(0);
            upstreamCalls.add(map("service", request.service(), "method", request.method(), "url", request.url()));
            return new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        });
        when(push.send(any(), any(), any(), any())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------ calls

    private record Call(int status, JsonNode body, MockHttpServletResponse response) {
        String id() {
            return body.path("data").path("id").asText(null);
        }
    }

    private Call call(String label, MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        Map<String, Object> recorded = new LinkedHashMap<>();
        recorded.put("status", response.getStatus());
        String type = response.getContentType();
        recorded.put("contentType", type);
        for (String header : List.of("Content-Disposition", "Idempotency-Replayed", "Retry-After")) {
            if (response.getHeader(header) != null) {
                recorded.put(header, response.getHeader(header));
            }
        }
        byte[] bytes = response.getContentAsByteArray();
        JsonNode body = JsonNodeFactory.instance.nullNode();
        if (bytes.length > 0 && type != null && (type.contains("json"))) {
            body = mapper.readTree(bytes);
            recorded.put("body", body);
        } else if (bytes.length > 0) {
            recorded.put("body", describeBinary(type, bytes));
        }
        calls.put(String.format("%03d %s", ++step, label), recorded);
        return new Call(response.getStatus(), body, response);
    }

    private Object describeBinary(String type, byte[] bytes) throws IOException {
        if (type == null) {
            return "bytes:" + bytes.length;
        }
        if (type.startsWith("application/pdf")) {
            try (PDDocument document = Loader.loadPDF(bytes)) {
                Map<String, Object> pdf = new LinkedHashMap<>();
                pdf.put("pages", document.getNumberOfPages());
                pdf.put("text", List.of(new PDFTextStripper().getText(document).split("\\R")));
                return pdf;
            }
        }
        if (type.contains("spreadsheetml")) {
            Map<String, Object> workbook = new LinkedHashMap<>();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                for (Sheet sheet : book) {
                    List<List<String>> rows = new ArrayList<>();
                    for (Row row : sheet) {
                        List<String> cells = new ArrayList<>();
                        for (Cell cell : row) {
                            cells.add(formatter.formatCellValue(cell));
                        }
                        rows.add(cells);
                    }
                    workbook.put(sheet.getSheetName(), rows);
                }
            }
            return workbook;
        }
        if (type.contains("zip") || type.contains("kmz")) {
            List<String> entries = new ArrayList<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    entries.add(entry.getName());
                }
            }
            return Map.of("zipEntries", entries);
        }
        if (type.startsWith("text") || type.contains("xml") || type.contains("kml")) {
            return List.of(new String(bytes, StandardCharsets.UTF_8).split("\\R"));
        }
        return type + ":" + bytes.length + " bytes";
    }

    private String json(Object value) throws IOException {
        return mapper.writeValueAsString(value);
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object value) throws IOException {
        return request.contentType(MediaType.APPLICATION_JSON).content(json(value));
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static List<List<List<Double>>> rectangle(double lng, double lat, double width, double height) {
        return List.of(List.of(List.of(lng, lat), List.of(lng + width, lat), List.of(lng + width, lat + height),
                List.of(lng, lat + height), List.of(lng, lat)));
    }

    private static String iso(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    // ------------------------------------------------------------------ scenario

    @Test
    void apiContractMatchesTheSnapshot() throws Exception {
        stubUpstream();
        int year = LocalDate.now(ZONE).getYear();

        call("GET /health", get("/health"), null);
        // The OpenAPI document lists every request and response field, with its type and constraints.
        call("GET /api/docs.json", get("/api/docs.json"), null);
        call("GET /api/v1/parcels without token", get("/api/v1/parcels"), null);
        call("POST /auth/login wrong password", body(post("/api/v1/auth/login"),
                map("email", "demo@fermapp.local", "password", "wrong-password")), null);
        call("POST /auth/login invalid body", body(post("/api/v1/auth/login"), map("email", "not-an-email")), null);
        Call login = call("POST /auth/login", body(post("/api/v1/auth/login"),
                map("email", " Demo@FermApp.local ", "password", "FermApp-demo-2026")), null);
        String admin = login.body().path("data").path("accessToken").asText();
        String refresh = login.body().path("data").path("refreshToken").asText();
        call("GET /auth/me", get("/api/v1/auth/me"), admin);
        Call refreshed = call("POST /auth/refresh", body(post("/api/v1/auth/refresh"), map("refreshToken", refresh)), null);
        call("POST /auth/refresh reused token", body(post("/api/v1/auth/refresh"), map("refreshToken", refresh)), null);
        admin = refreshed.body().path("data").path("accessToken").asText(admin);
        String adminRefresh = refreshed.body().path("data").path("refreshToken").asText();
        call("POST /auth/push-token invalid", body(post("/api/v1/auth/push-token"), map("token", "nope")), admin);
        call("POST /auth/push-token", body(post("/api/v1/auth/push-token"),
                map("token", "ExponentPushToken[contract-device]", "platform", "android")), admin);

        Call registered = call("POST /auth/register", body(post("/api/v1/auth/register"), map("email", "Second@Farm.example",
                "password", "Second-farm-2026", "fullName", " Second Owner ", "farmName", "Second Farm", "county", "Prahova")), null);
        call("POST /auth/register duplicate", body(post("/api/v1/auth/register"), map("email", "second@farm.example",
                "password", "Second-farm-2026", "fullName", "Second Owner", "farmName", "Other", "county", null)), null);
        String secondOwner = registered.body().path("data").path("accessToken").asText();
        call("GET /parcels as second farm", get("/api/v1/parcels"), secondOwner);

        // Team: an operator and an agronomist in the demo farm.
        call("GET /team", get("/api/v1/team"), admin);
        Call operatorMember = call("POST /team operator", body(post("/api/v1/team"), map("email", "Operator@Demo.example",
                "fullName", "Ion Operator", "role", "OPERATOR", "password", "Operator-pass-2026")), admin);
        call("POST /team agronomist", body(post("/api/v1/team"), map("email", "agro@demo.example", "fullName", "Ana Agro",
                "role", "AGRONOMIST", "password", "Agronomist-pass-2026")), admin);
        call("POST /team existing user", body(post("/api/v1/team"), map("email", "second@farm.example",
                "fullName", "Second Owner", "role", "FARM_MANAGER")), admin);
        String operatorId = operatorMember.body().path("data").path("userId").asText();
        String operator = call("POST /auth/login operator", body(post("/api/v1/auth/login"),
                map("email", "operator@demo.example", "password", "Operator-pass-2026")), null)
                .body().path("data").path("accessToken").asText();
        String agronomist = call("POST /auth/login agronomist", body(post("/api/v1/auth/login"),
                map("email", "agro@demo.example", "password", "Agronomist-pass-2026")), null)
                .body().path("data").path("accessToken").asText();
        Call secondLogin = call("POST /auth/login second owner", body(post("/api/v1/auth/login"),
                map("email", "second@farm.example", "password", "Second-farm-2026")), null);
        JsonNode farms = secondLogin.body().path("data").path("farms");
        String demoFarm = null;
        for (JsonNode farm : farms) {
            if ("Demo Farm".equals(farm.path("name").asText())) {
                demoFarm = farm.path("id").asText();
            }
        }
        call("POST /auth/switch-farm", body(post("/api/v1/auth/switch-farm"), map("farmId", demoFarm)),
                secondLogin.body().path("data").path("accessToken").asText());
        call("POST /team as operator", body(post("/api/v1/team"), map("email", "x@y.example", "fullName", "Xx",
                "role", "OPERATOR", "password", "Something-long-1")), operator);

        // Catalog and farm-wide reads.
        call("GET /crops", get("/api/v1/crops"), admin);
        call("GET /tasks/types", get("/api/v1/tasks/types"), admin);
        Call parcels = call("GET /parcels", get("/api/v1/parcels"), admin);
        call("GET /parcels search", get("/api/v1/parcels").param("search", "lunca"), admin);
        Map<String, String> parcelIds = new LinkedHashMap<>();
        for (JsonNode parcel : parcels.body().path("data")) {
            parcelIds.put(parcel.path("code").asText(), parcel.path("id").asText());
        }
        String north = parcelIds.get("NV-003");
        String south = parcelIds.get("SD-001");
        String lunca = parcelIds.get("LM-010");

        // Soil and satellite first, so the later reads see them.
        call("POST /parcels/north/soil/refresh", post("/api/v1/parcels/" + north + "/soil/refresh"), admin);
        call("GET /parcels/north/soil", get("/api/v1/parcels/" + north + "/soil"), admin);
        call("GET /parcels/south/soil", get("/api/v1/parcels/" + south + "/soil"), admin);
        call("POST /parcels/north/satellite/refresh", body(post("/api/v1/parcels/" + north + "/satellite/refresh"),
                map("days", 90)), admin);
        call("POST /parcels/north/satellite/refresh as operator", body(post("/api/v1/parcels/" + north + "/satellite/refresh"),
                map()), operator);
        call("GET /parcels/north/satellite", get("/api/v1/parcels/" + north + "/satellite"), admin);
        call("GET /parcels/north/satellite/compare", get("/api/v1/parcels/" + north + "/satellite/compare")
                .param("from", "2026-04-02").param("to", "2026-05-05"), admin);
        call("GET /parcels/north/satellite/compare missing date", get("/api/v1/parcels/" + north + "/satellite/compare")
                .param("from", "2026-04-01").param("to", "2026-05-05"), admin);
        call("GET /parcels/north/satellite/image", get("/api/v1/parcels/" + north + "/satellite/image")
                .param("date", "2026-04-20").param("index", "NDRE"), admin);
        call("GET /parcels/north/ndvi", get("/api/v1/parcels/" + north + "/ndvi"), admin);

        for (Map.Entry<String, String> parcel : parcelIds.entrySet()) {
            String name = parcel.getKey();
            String base = "/api/v1/parcels/" + parcel.getValue();
            call("GET parcel " + name, get(base), admin);
            call("GET parcel " + name + " seasons", get(base + "/seasons"), admin);
            call("GET parcel " + name + " recommendations", get(base + "/recommendations"), admin);
            call("GET parcel " + name + " recommendations 2027", get(base + "/recommendations").param("year", "2027"), admin);
            call("GET parcel " + name + " climate", get(base + "/climate").param("years", "3"), admin);
            call("GET parcel " + name + " development", get(base + "/development"), admin);
            call("GET parcel " + name + " rotation options", get(base + "/rotation/options"), admin);
            call("GET parcel " + name + " rotation score", get(base + "/rotation-score"), admin);
            call("POST parcel " + name + " rotation evaluate", body(post(base + "/rotation/evaluate"),
                    map("crop", "SUNFLOWER", "year", year + 1)), admin);
            call("GET parcel " + name + " intelligence", get(base + "/intelligence"), admin);
            call("GET parcel " + name + " economics", get(base + "/economics"), admin);
            call("GET parcel " + name + " costs summary", get(base + "/costs/summary"), admin);
            call("GET parcel " + name + " water", get(base + "/water"), admin);
            call("GET parcel " + name + " weather", get(base + "/weather"), admin);
            call("GET parcel " + name + " forecast 24h", get(base + "/weather/forecast").param("range", "24h"), admin);
            call("GET parcel " + name + " history 30d", get(base + "/weather/history"), admin);
            call("GET parcel " + name + " windows", get(base + "/weather/windows"), admin);
            call("POST parcel " + name + " alerts check", post(base + "/alerts/check"), admin);
            call("GET parcel " + name + " tasks", get(base + "/tasks"), admin);
            call("GET parcel " + name + " plan", get(base + "/plan"), admin);
            call("GET parcel " + name + " report", get(base + "/report"), admin);
        }
        call("GET parcel north forecast 14d", get("/api/v1/parcels/" + north + "/weather/forecast").param("range", "14d"), admin);
        call("GET parcel north history 12m", get("/api/v1/parcels/" + north + "/weather/history").param("range", "12m"), admin);
        call("GET parcel north economics as operator", get("/api/v1/parcels/" + north + "/economics"), operator);
        call("GET parcel north report as operator", get("/api/v1/parcels/" + north + "/report"), operator);
        call("GET parcel unknown", get("/api/v1/parcels/00000000-0000-0000-0000-000000000000"), admin);
        call("GET parcel bad id", get("/api/v1/parcels/not-a-uuid"), admin);
        call("GET demo parcel from second farm", get("/api/v1/parcels/" + north), secondOwner);

        // Plan wizard.
        call("POST wizard analyze existing", body(post("/api/v1/parcels/wizard/analyze"),
                map("lastCrop", "WHEAT", "plannedCrop", "CORN", "parcelId", lunca)), admin);
        call("POST wizard analyze outline", body(post("/api/v1/parcels/wizard/analyze"), map("lastCrop", "OTHER",
                "lastCropName", " Tomatoes ", "plannedCrop", "SOY", "name", "New field",
                "geometry", map("type", "Polygon", "coordinates", rectangle(26.2, 44.6, 0.01, 0.006)))), admin);
        call("POST wizard analyze invalid", body(post("/api/v1/parcels/wizard/analyze"),
                map("lastCrop", "WHEAT", "plannedCrop", "CORN")), admin);
        call("POST wizard complete existing", body(post("/api/v1/parcels/wizard/complete"),
                map("lastCrop", "ALFALFA", "plannedCrop", "CORN", "parcelId", lunca)), admin);
        call("GET parcel lunca plan after wizard", get("/api/v1/parcels/" + lunca + "/plan"), admin);
        call("GET parcel lunca tasks after wizard", get("/api/v1/parcels/" + lunca + "/tasks"), admin);
        call("POST wizard complete new parcel", body(post("/api/v1/parcels/wizard/complete"),
                map("lastCrop", "SUNFLOWER", "plannedCrop", "WHEAT", "draft", map("name", "Wizard Field", "code", "WZ-1",
                        "geometry", map("type", "Polygon", "coordinates", rectangle(26.3, 44.65, 0.01, 0.008)),
                        "county", "Ilfov"))), admin);

        // Parcels: create, update, tillage, archive.
        Call created = call("POST /parcels", body(post("/api/v1/parcels"), map("name", " Contract Field ", "code", "CT-1",
                "geometry", map("type", "Polygon", "coordinates", rectangle(26.05, 44.75, 0.008, 0.005)),
                "locality", "Snagov", "county", "Ilfov", "notes", "Created by the contract test", "irrigatedAreaHa", 2.5)), admin);
        String field = created.id();
        call("POST /parcels duplicate code", body(post("/api/v1/parcels"), map("name", "Copy", "code", "CT-1",
                "geometry", map("type", "Polygon", "coordinates", rectangle(26.06, 44.76, 0.008, 0.005)))), admin);
        call("POST /parcels self-intersecting", body(post("/api/v1/parcels"), map("name", "Bow tie",
                "geometry", map("type", "Polygon", "coordinates", List.of(List.of(List.of(26.0, 44.0), List.of(26.01, 44.01),
                        List.of(26.01, 44.0), List.of(26.0, 44.01), List.of(26.0, 44.0)))))), admin);
        call("POST /parcels as operator", body(post("/api/v1/parcels"), map("name", "No",
                "geometry", map("type", "Polygon", "coordinates", rectangle(26.07, 44.77, 0.008, 0.005)))), operator);
        call("PATCH /parcels/field", body(patch("/api/v1/parcels/" + field), map("name", "Contract Field 2",
                "notes", null, "geometry", map("type", "Polygon", "coordinates", rectangle(26.05, 44.75, 0.01, 0.005)))), admin);
        call("PATCH /parcels/field empty name", body(patch("/api/v1/parcels/" + field), map("name", null)), admin);
        call("POST /parcels/field/tillage", body(post("/api/v1/parcels/" + field + "/tillage"),
                map("system", "STRIP_TILL", "year", year)), admin);
        call("POST /parcels/north/tillage", body(post("/api/v1/parcels/" + north + "/tillage"),
                map("system", "NO_TILL")), admin);

        // Seasons.
        Call season = call("POST /parcels/field/seasons", body(post("/api/v1/parcels/" + field + "/seasons"), map("crop", "CORN",
                "year", year, "sowingDate", year + "-04-10", "expectedHarvestDate", year + "-09-25", "expectedYieldTHa", 8.5,
                "expectedPricePerT", 190, "notes", "Contract season")), admin);
        call("POST /parcels/field/seasons duplicate year", body(post("/api/v1/parcels/" + field + "/seasons"),
                map("crop", "SOY", "year", year)), admin);
        call("POST /parcels/field/seasons previous", body(post("/api/v1/parcels/" + field + "/seasons"),
                map("crop", "WHEAT", "year", year - 1, "yieldT", 31.5, "harvestDate", (year - 1) + "-07-12")), admin);
        call("PATCH /seasons/field", body(patch("/api/v1/seasons/" + season.id()), map("yieldT", 41.25,
                "harvestMoisture", 18.5, "actualPricePerT", 205, "notes", null)), admin);
        call("GET /parcels/field/seasons", get("/api/v1/parcels/" + field + "/seasons"), admin);
        call("GET /parcels/field/development", get("/api/v1/parcels/" + field + "/development"), admin);

        // Costs and incomes.
        Call cost = call("POST /costs", body(post("/api/v1/costs"), map("parcelId", field, "year", year, "category", "SEED",
                "amount", 1830.45, "date", year + "-03-15", "notes", " Seed lot ")), admin);
        call("POST /costs fuel", body(post("/api/v1/costs"), map("parcelId", field, "year", year, "category", "FUEL",
                "amount", 640, "currency", "EUR")), admin);
        call("POST /costs bad currency", body(post("/api/v1/costs"), map("parcelId", field, "year", year,
                "category", "FUEL", "amount", 10, "currency", "RON")), admin);
        call("POST /costs as agronomist", body(post("/api/v1/costs"), map("parcelId", field, "year", year,
                "category", "FUEL", "amount", 10)), agronomist);
        call("PATCH /costs", body(patch("/api/v1/costs/" + cost.id()), map("amount", 1900.5, "notes", null)), admin);
        call("GET /costs", get("/api/v1/costs").param("parcelId", field), admin);
        call("GET /costs as operator", get("/api/v1/costs"), operator);
        call("POST /parcels/field/incomes", body(post("/api/v1/parcels/" + field + "/incomes"),
                map("year", year, "amount", 12500, "source", " Grain sale ", "date", year + "-10-01")), admin);
        call("GET /parcels/field/costs/summary", get("/api/v1/parcels/" + field + "/costs/summary").param("year", "" + year), admin);
        call("GET /parcels/field/economics", get("/api/v1/parcels/" + field + "/economics"), admin);

        // Economics sheets and settings.
        call("GET /economics/settings", get("/api/v1/economics/settings"), admin);
        call("POST /economics/settings", body(post("/api/v1/economics/settings"), map("fuelPricePerL", 1.52,
                "defaultLaborRate", 11, "labor", map(operatorId, map("monthlySalary", 1400)),
                "machines", map())), admin);
        call("POST /economics/settings invalid", body(post("/api/v1/economics/settings"), map("fuelPricePerL", 50)), admin);
        call("POST /economics/sheets/field", body(post("/api/v1/economics/sheets/" + field + "/" + year), map(
                "seeds", List.of(map("id", "s1", "supplier", "Pioneer", "hybrid", "P9911", "unit", "SEEDS", "bagSize", 80000,
                        "ratePerHa", 72000, "pricePerBag", 245)),
                "fertilizers", List.of(map("id", "f1", "product", "NPK 20-20-0", "ratePerHaKg", 200, "pricePerT", 610,
                        "applications", 1, "n", 20, "p", 20)),
                "chemicals", List.of(map("id", "c1", "product", "Adengo", "kind", "HERBICIDE", "dosePerHa", 0.44, "unit", "L",
                        "pricePerUnit", 160)),
                "misc", List.of(map("id", "m1", "label", "Insurance", "amount", 35, "basis", "PER_HA")),
                "revenue", map("expectedYieldTHa", 9.2, "pricePerT", 190))), admin);
        call("GET /economics/sheets/field", get("/api/v1/economics/sheets/" + field + "/" + year), admin);
        call("GET /economics/sheets", get("/api/v1/economics/sheets").param("year", "" + year), admin);
        call("GET /economics/farm", get("/api/v1/economics/farm").param("year", "" + year), admin);
        call("GET /economics/farm as operator", get("/api/v1/economics/farm").param("year", "" + year), operator);

        // Machines.
        call("GET /machines", get("/api/v1/machines"), admin);
        call("POST /machines zero power", body(post("/api/v1/machines"), map("name", " Seeder X ", "type", "SEEDER",
                "brand", "Vaderstad", "model", "Tempo", "powerHp", 0, "operatorId", operatorId,
                "tillageSystems", List.of("STRIP_TILL", "NO_TILL"))), admin);
        Call machine2 = call("POST /machines valid", body(post("/api/v1/machines"), map("name", "Seeder X", "type", "SEEDER",
                "brand", "Vaderstad", "model", "Tempo", "avgConsumptionLh", 14, "operatorId", operatorId,
                "tillageSystems", List.of("STRIP_TILL", "NO_TILL"))), admin);
        String seeder = machine2.id();
        call("PATCH /machines", body(patch("/api/v1/machines/" + seeder), map("model", null, "powerHp", 180)), admin);

        // Tasks.
        // Whole days from the start of the test, so the normalised offsets do not depend on the minute of the run.
        Instant due = STARTED.plus(Duration.ofDays(3));
        Call task = call("POST /tasks", body(post("/api/v1/tasks"), map("type", "SOWING", "title", " Sow corn ",
                "description", "Field CT-1", "dueDate", iso(due), "parcelId", field, "assigneeId", operatorId)), admin);
        call("POST /tasks overdue", body(post("/api/v1/tasks"), map("type", "SOIL_PREP",
                "dueDate", iso(STARTED.minus(Duration.ofDays(2))), "parcelId", field,
                "priority", "URGENT")), admin);
        call("POST /tasks as operator", body(post("/api/v1/tasks"), map("type", "SOWING", "dueDate", iso(due))), operator);
        call("GET /tasks", get("/api/v1/tasks"), admin);
        call("GET /tasks mine operator", get("/api/v1/tasks").param("mine", "true"), operator);
        call("PATCH /tasks", body(patch("/api/v1/tasks/" + task.id()), map("priority", "HIGH", "description", null)), admin);
        call("POST /tasks/status operator", body(post("/api/v1/tasks/" + task.id() + "/status"),
                map("status", "IN_PROGRESS")), operator);
        call("GET parcel field tasks", get("/api/v1/parcels/" + field + "/tasks"), admin);

        // Field work, with GPS positions, pause and completion.
        call("POST /work/estimate", body(post("/api/v1/work/estimate"), map("parcelId", field, "workingWidthM", 6,
                "speedKmh", 8, "efficiencyPct", 75)), admin);
        call("POST /work/estimate invalid", body(post("/api/v1/work/estimate"), map("parcelId", field,
                "workingWidthM", 6, "speedKmh", 80, "efficiencyPct", 75)), admin);
        Call work = call("POST /work", post("/api/v1/work").header("Idempotency-Key", "contract-work-1")
                .contentType(MediaType.APPLICATION_JSON).content(json(map("parcelId", field, "machineId", seeder,
                        "operatorId", operatorId, "type", "SOWING", "notes", " Sowing ", "workingWidthM", 6, "speedKmh", 8,
                        "efficiencyPct", 75))), operator);
        call("POST /work replayed", post("/api/v1/work").header("Idempotency-Key", "contract-work-1")
                .contentType(MediaType.APPLICATION_JSON).content(json(map("parcelId", field, "machineId", seeder,
                        "operatorId", operatorId, "type", "SOWING", "notes", " Sowing ", "workingWidthM", 6, "speedKmh", 8,
                        "efficiencyPct", 75))), operator);
        call("POST /work same machine busy", body(post("/api/v1/work"), map("parcelId", south, "machineId", seeder,
                "type", "DISCING", "workingWidthM", 4, "speedKmh", 10, "efficiencyPct", 80)), operator);
        String workId = work.id();
        call("POST /work/location", body(post("/api/v1/work/" + workId + "/location"), map("lat", 44.752, "lng", 26.054,
                "headingDeg", 90, "speedKmh", 7.5, "accuracyM", 3)), operator);
        call("POST /work/location second", body(post("/api/v1/work/" + workId + "/location"), map("lat", 44.7525,
                "lng", 26.056)), operator);
        call("GET /work/location", get("/api/v1/work/" + workId + "/location"), admin);
        call("POST /work/pause", post("/api/v1/work/" + workId + "/pause"), operator);
        call("POST /work/pause again", post("/api/v1/work/" + workId + "/pause"), operator);
        call("POST /work/resume", post("/api/v1/work/" + workId + "/resume"), operator);
        call("GET /work/id", get("/api/v1/work/" + workId), admin);
        call("GET /machines busy", get("/api/v1/machines"), admin);
        call("GET /map/overview running", get("/api/v1/map/overview"), admin);
        call("POST /work/complete", body(post("/api/v1/work/" + workId + "/complete"), map()), operator);
        call("POST /work/cancel completed", post("/api/v1/work/" + workId + "/cancel"), operator);
        Call work2 = call("POST /work disc", body(post("/api/v1/work"), map("parcelId", north, "type", "DISCING",
                "workingWidthM", 4, "speedKmh", 10, "efficiencyPct", 80)), admin);
        call("POST /work/cancel", post("/api/v1/work/" + work2.id() + "/cancel"), admin);
        call("GET /work", get("/api/v1/work"), admin);
        call("GET /work completed", get("/api/v1/work").param("status", "COMPLETED"), admin);
        call("GET /tasks after work", get("/api/v1/tasks").param("parcelId", field), admin);

        // Irrigation.
        call("POST /irrigation/estimate", body(post("/api/v1/irrigation/estimate"), map("routeLengthM", 640,
                "speedMPerH", 35)), admin);
        Call irrigation = call("POST /irrigation", body(post("/api/v1/irrigation"), map("parcelId", field,
                "systemType", "REEL", "doseMm", 30, "routeLengthM", 640, "speedMPerH", 35)), operator);
        String run = irrigation.id();
        call("POST /irrigation/pause", post("/api/v1/irrigation/" + run + "/pause"), operator);
        call("POST /irrigation/resume", post("/api/v1/irrigation/" + run + "/resume"), operator);
        call("GET /irrigation/id", get("/api/v1/irrigation/" + run), admin);
        call("POST /irrigation/complete", body(post("/api/v1/irrigation/" + run + "/complete"), map()), operator);
        Call irrigation2 = call("POST /irrigation pivot", body(post("/api/v1/irrigation"), map("parcelId", north,
                "systemType", "PIVOT", "routeLengthM", 900, "speedMPerH", 60)), admin);
        call("POST /irrigation/cancel", post("/api/v1/irrigation/" + irrigation2.id() + "/cancel"), admin);
        call("GET /irrigation", get("/api/v1/irrigation"), admin);
        call("GET parcel field water", get("/api/v1/parcels/" + field + "/water"), admin);

        // Observations and photos.
        call("POST /parcels/field/observations", body(post("/api/v1/parcels/" + field + "/observations"),
                map("text", " Aphids on the edge ", "lat", 44.751, "lng", 26.052)), operator);
        call("POST /parcels/field/observations empty", body(post("/api/v1/parcels/" + field + "/observations"),
                map("text", "  ")), operator);
        call("GET /parcels/field/observations", get("/api/v1/parcels/" + field + "/observations"), admin);
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F', 0};
        Call photo = call("POST /parcels/field/photos", multipart("/api/v1/parcels/" + field + "/photos")
                .file(new MockMultipartFile("file", "leaf.jpg", "image/jpeg", jpeg)).param("notes", "Leaf spots"), operator);
        call("POST /parcels/field/photos not an image", multipart("/api/v1/parcels/" + field + "/photos")
                .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8))), operator);
        call("POST /parcels/field/photos no file", multipart("/api/v1/parcels/" + field + "/photos"), operator);
        call("GET /parcels/field/photos", get("/api/v1/parcels/" + field + "/photos"), admin);
        String photoId = photo.id();
        call("GET /parcels/field/photos/file", get("/api/v1/parcels/" + field + "/photos/" + photoId + "/file"), admin);
        call("DELETE /parcels/field/photos", delete("/api/v1/parcels/" + field + "/photos/" + photoId), operator);
        call("GET /parcels/field/photos/file deleted", get("/api/v1/parcels/" + field + "/photos/" + photoId + "/file"), admin);

        // IoT.
        Call device = call("POST /iot/devices", body(post("/api/v1/iot/devices"), map("name", " Soil probe ",
                "kind", "SOIL_MOISTURE", "protocol", "REST", "vendor", "Sensoterra", "parcelId", field)), admin);
        String deviceKey = device.body().path("data").path("deviceKey").asText();
        call("POST /iot/ingest", body(post("/api/v1/iot/ingest").header("x-device-key", deviceKey), map("readings",
                List.of(map("metric", "soil_moisture", "value", 27.5, "unit", "%", "recordedAt", iso(Instant.now())),
                        map("metric", "soil_temperature", "value", 14.25, "unit", "C")))), null);
        call("POST /iot/ingest without readings", body(post("/api/v1/iot/ingest").header("x-device-key", deviceKey),
                map("metric", "soil_moisture", "value", 26)), null);
        call("POST /iot/ingest wrong key", body(post("/api/v1/iot/ingest").header("x-device-key", "wrong"),
                map("readings", List.of(map("metric", "soil_moisture", "value", 26)))), null);
        call("POST /iot/ingest no key", body(post("/api/v1/iot/ingest"),
                map("readings", List.of(map("metric", "soil_moisture", "value", 26)))), null);
        call("GET /iot/devices", get("/api/v1/iot/devices"), admin);
        call("GET /iot/devices/readings", get("/api/v1/iot/devices/" + device.id() + "/readings")
                .param("metric", "soil_moisture"), admin);
        call("GET parcel field intelligence with sensor", get("/api/v1/parcels/" + field + "/intelligence"), admin);

        // Assistant (not configured: it answers from the parcel data).
        Call chat = call("POST /ai/chat", body(post("/api/v1/ai/chat"), map("message", " Should I irrigate this week? ",
                "parcelId", field)), admin);
        call("POST /ai/chat follow-up", body(post("/api/v1/ai/chat"), map("message", "What does it cost per hectare?",
                "conversationId", chat.body().path("data").path("conversationId").asText())), admin);
        call("POST /ai/chat empty", body(post("/api/v1/ai/chat"), map("message", "")), admin);
        call("GET /ai/conversations", get("/api/v1/ai/conversations"), admin);
        call("GET /ai/conversations/id", get("/api/v1/ai/conversations/"
                + chat.body().path("data").path("conversationId").asText()), admin);

        // Farm-wide views.
        call("GET /dashboard", get("/api/v1/dashboard"), admin);
        call("GET /dashboard as operator", get("/api/v1/dashboard"), operator);
        call("GET /map/overview", get("/api/v1/map/overview"), admin);
        call("GET /alerts", get("/api/v1/alerts"), admin);
        call("GET /notifications operator", get("/api/v1/notifications"), operator);
        Call notifications = call("GET /notifications admin", get("/api/v1/notifications"), admin);
        JsonNode firstNotification = notifications.body().path("data").path(0);
        if (!firstNotification.isMissingNode()) {
            call("POST /notifications/read", post("/api/v1/notifications/" + firstNotification.path("id").asText() + "/read"), admin);
        }
        call("GET /sync/changes", get("/api/v1/sync/changes").param("since", iso(STARTED.minus(Duration.ofHours(1)))), admin);

        // Reports in every format.
        for (String type : List.of("parcels", "machines", "crops", "profitability", "farm", "costs", "work", "fuel",
                "yields", "irrigation", "rotation", "unknown")) {
            call("GET /reports/" + type, get("/api/v1/reports/" + type).param("year", "" + year), admin);
        }
        call("GET /reports/profitability as operator", get("/api/v1/reports/profitability"), operator);
        for (String type : List.of("parcels", "farm", "profitability", "costs", "work")) {
            call("GET /reports/" + type + " pdf", get("/api/v1/reports/" + type).param("format", "pdf").param("year", "" + year), admin);
            call("GET /reports/" + type + " xlsx", get("/api/v1/reports/" + type).param("format", "xlsx").param("year", "" + year), admin);
        }
        call("GET /reports/machines pdf portrait", get("/api/v1/reports/machines").param("format", "pdf")
                .param("orientation", "portrait"), admin);

        // Export and import.
        for (String format : List.of("geojson", "kml", "kmz", "shp", "lpis", "csv")) {
            call("GET /parcels/export " + format, get("/api/v1/parcels/export").param("format", format), admin);
        }
        String geojson = json(map("type", "FeatureCollection", "features", List.of(
                map("type", "Feature", "properties", map("name", "Imported A", "code", "IMP-A"),
                        "geometry", map("type", "Polygon", "coordinates", rectangle(26.4, 44.5, 0.01, 0.01))),
                map("type", "Feature", "properties", map("name", "Broken"),
                        "geometry", map("type", "Point", "coordinates", List.of(26.4, 44.5))))));
        call("POST /parcels/import dry run", multipart("/api/v1/parcels/import").file(new MockMultipartFile("file",
                "fields.geojson", "application/geo+json", geojson.getBytes(StandardCharsets.UTF_8)))
                .param("format", "geojson").param("dryRun", "true"), admin);
        call("POST /parcels/import", multipart("/api/v1/parcels/import").file(new MockMultipartFile("file",
                "fields.geojson", "application/geo+json", geojson.getBytes(StandardCharsets.UTF_8)))
                .param("format", "geojson"), admin);
        call("POST /parcels/import garbage", multipart("/api/v1/parcels/import").file(new MockMultipartFile("file",
                "fields.kml", "application/xml", "<kml>".getBytes(StandardCharsets.UTF_8))).param("format", "kml"), admin);

        // Clean-up routes: deletes, archive, roles, logout.
        call("DELETE /costs", delete("/api/v1/costs/" + cost.id()), admin);
        call("DELETE /costs again", delete("/api/v1/costs/" + cost.id()), admin);
        call("DELETE /tasks", delete("/api/v1/tasks/" + task.id()), admin);
        call("DELETE /seasons", delete("/api/v1/seasons/" + season.id()), admin);
        call("DELETE /machines", delete("/api/v1/machines/" + seeder), admin);
        call("DELETE /iot/devices", delete("/api/v1/iot/devices/" + device.id()), admin);
        call("POST /parcels/field/archive", post("/api/v1/parcels/" + field + "/archive"), admin);
        call("GET /parcels archived", get("/api/v1/parcels").param("archived", "true"), admin);
        call("POST /parcels/field/restore", post("/api/v1/parcels/" + field + "/restore"), admin);
        call("PATCH /team operator role", body(patch("/api/v1/team/" + operatorId), map("role", "AGRONOMIST")), admin);
        call("GET /auth/me operator after role change", get("/api/v1/auth/me"), operator);
        call("DELETE /team operator", delete("/api/v1/team/" + operatorId), admin);
        call("GET /parcels removed member", get("/api/v1/parcels"), operator);
        call("POST /auth/logout without body", post("/api/v1/auth/logout"), admin);
        call("POST /auth/logout", body(post("/api/v1/auth/logout"), map("refreshToken", adminRefresh)), admin);
        call("POST /auth/refresh after logout", body(post("/api/v1/auth/refresh"), map("refreshToken", adminRefresh)), null);
        call("GET /dashboard final", get("/api/v1/dashboard"), admin);
        call("GET /sync/changes final", get("/api/v1/sync/changes"), admin);

        ObjectNode out = (ObjectNode) normalize(mapper.valueToTree(Map.of("calls", calls, "upstream", upstreamCalls)));
        ObjectNode ordered = JsonNodeFactory.instance.objectNode();
        ordered.set("calls", out.get("calls"));
        // Soil and climate are loaded in parallel, so their upstream calls come in either order.
        ordered.set("upstream", sorted(out.get("upstream"), false));
        // The sync feed lists rows without ORDER BY, in whatever order PostgreSQL returns them.
        ObjectNode recordedCalls = (ObjectNode) ordered.get("calls");
        recordedCalls.properties().forEach(entry -> {
            // Notifications created in the same millisecond tie on ORDER BY createdAt.
            if (entry.getKey().contains("GET /sync/changes") || entry.getKey().contains("GET /notifications")) {
                entry.setValue(sorted(entry.getValue(), true));
            }
        });
        Snapshots.assertMatches(mapper, "api-contract", ordered);
    }

    /** Sorts the items of an array (of every nested array when {@code deep}) by their JSON text. */
    private static JsonNode sorted(JsonNode node, boolean deep) {
        if (node.isObject() && deep) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(entry -> copy.set(entry.getKey(), sorted(entry.getValue(), true)));
            return copy;
        }
        if (!node.isArray()) {
            return node;
        }
        List<JsonNode> items = new ArrayList<>();
        node.forEach(item -> items.add(deep ? sorted(item, true) : item));
        items.sort(java.util.Comparator.comparing(JsonNode::toString));
        ArrayNode copy = JsonNodeFactory.instance.arrayNode();
        items.forEach(copy::add);
        return copy;
    }

    // ------------------------------------------------------------------ normalisation

    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern LOCAL_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?(?![\\d.:Z+-])");
    private static final Pattern DATE = Pattern.compile("(?<![\\dT-])\\d{4}-\\d{2}-\\d{2}(?![\\dT])");
    private static final Pattern DAY_NAME = Pattern.compile("\\b(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\b");
    private static final Pattern GENERATED_AT =
            Pattern.compile("\\b\\d{1,2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{4}, \\d{2}:\\d{2}\\b");
    private static final Pattern JWT = Pattern.compile("eyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+");

    private final Map<String, String> ids = new HashMap<>();

    private String relative(Instant value) {
        long minutes = Duration.between(STARTED, value).toMinutes();
        if (Math.abs(minutes) > 4 * 24 * 60) {
            return null;
        }
        long hours = Math.round(minutes / 60.0);
        return hours == 0 ? "<now>" : "<now" + (hours > 0 ? "+" : "") + hours + "h>";
    }

    private String normalizeText(String text) {
        String out = text;
        Matcher uuid = UUID_PATTERN.matcher(out);
        StringBuilder buffer = new StringBuilder();
        while (uuid.find()) {
            String token = ids.computeIfAbsent(uuid.group(), key -> "<id" + (ids.size() + 1) + ">");
            uuid.appendReplacement(buffer, Matcher.quoteReplacement(token));
        }
        uuid.appendTail(buffer);
        out = buffer.toString();
        out = JWT.matcher(out).replaceAll("<jwt>");
        // Reports print the moment they were generated.
        out = GENERATED_AT.matcher(out).replaceAll("<generated-at>");

        buffer = new StringBuilder();
        Matcher instant = INSTANT.matcher(out);
        while (instant.find()) {
            String replacement = instant.group();
            try {
                String rel = relative(java.time.OffsetDateTime.parse(
                        instant.group().length() == 17 ? instant.group().replace("Z", ":00Z") : instant.group()).toInstant());
                if (rel != null) {
                    replacement = rel;
                }
            } catch (RuntimeException ignored) {
                // Left as it is.
            }
            instant.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        instant.appendTail(buffer);
        out = buffer.toString();

        LocalDate today = LocalDate.ofInstant(STARTED, ZONE);
        buffer = new StringBuilder();
        Matcher local = LOCAL_TIME.matcher(out);
        while (local.find()) {
            LocalDateTime at = LocalDateTime.parse(local.group().length() == 16 ? local.group() + ":00" : local.group());
            long days = ChronoUnit.DAYS.between(today, at.toLocalDate());
            String replacement = Math.abs(days) <= 20 ? "<day" + (days >= 0 ? "+" : "") + days + "T" + local.group().substring(11, 16) + ">"
                    : local.group();
            local.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        local.appendTail(buffer);
        out = buffer.toString();

        buffer = new StringBuilder();
        Matcher date = DATE.matcher(out);
        while (date.find()) {
            String replacement = date.group();
            try {
                long days = ChronoUnit.DAYS.between(today, LocalDate.parse(date.group()));
                if (Math.abs(days) <= 20) {
                    replacement = "<day" + (days >= 0 ? "+" : "") + days + ">";
                }
            } catch (RuntimeException ignored) {
                // Not a date.
            }
            date.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        date.appendTail(buffer);
        out = buffer.toString();

        buffer = new StringBuilder();
        Matcher day = DAY_NAME.matcher(out);
        while (day.find()) {
            int offset = 0;
            // The next occurrence of that weekday, from today: 0 to 6 days ahead.
            for (int i = 0; i < 7; i++) {
                if (today.plusDays(i).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH).equals(day.group())) {
                    offset = i;
                    break;
                }
            }
            day.appendReplacement(buffer, Matcher.quoteReplacement("<weekday" + (offset >= 0 ? "+" : "") + offset + ">"));
        }
        day.appendTail(buffer);
        out = buffer.toString();
        return out;
    }

    private static final List<String> SECRET_KEYS = List.of("accessToken", "refreshToken", "deviceKey", "storageKey");
    private static final List<String> TIMING_KEYS = List.of("pausedMs", "elapsedSeconds");

    private JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(entry -> {
                String key = normalizeText(entry.getKey());
                JsonNode value = entry.getValue();
                if (SECRET_KEYS.contains(entry.getKey()) && value.isTextual()) {
                    // Not numbered: two tokens issued in the same second for the same user are identical.
                    copy.put(key, "<" + entry.getKey() + ">");
                } else if (TIMING_KEYS.contains(entry.getKey()) && value.isNumber()) {
                    // Milliseconds that pass between two requests of the test.
                    copy.put(key, "<ms>");
                } else if ("details".equals(entry.getKey()) && value.isArray()) {
                    // Bean Validation reports violations in no fixed order.
                    List<JsonNode> issues = new ArrayList<>();
                    value.forEach(issue -> issues.add(normalize(issue)));
                    issues.sort(java.util.Comparator.comparing(JsonNode::toString));
                    ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
                    issues.forEach(sorted::add);
                    copy.set(key, sorted);
                } else {
                    copy.set(key, normalize(value));
                }
            });
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> copy.add(normalize(item)));
            return copy;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(normalizeText(node.asText()));
        }
        return node;
    }
}
