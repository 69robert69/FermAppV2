package com.fermapp.api.common;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.Snapshots;
import com.fermapp.api.common.cache.CacheService;
import com.fermapp.api.common.error.ApiException;
import com.fermapp.api.common.error.ErrorBody;
import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.http.UpstreamHttp;
import com.fermapp.api.common.http.UpstreamHttp.UpstreamRequest;
import com.fermapp.api.common.json.JsonFormats;
import com.fermapp.api.common.storage.FileStorage;
import com.fermapp.api.common.util.Concurrency;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.common.web.BodySizeLimitFilter;
import com.fermapp.api.common.web.IdempotencyFilter;
import com.fermapp.api.common.web.IdempotencyInterceptor;
import com.fermapp.api.common.web.IdempotencyService;
import com.fermapp.api.common.web.RateLimitPolicy;
import com.fermapp.api.common.web.RateLimiter;
import com.fermapp.api.config.AppProperties;
import com.fermapp.api.config.JacksonConfig;
import com.fermapp.api.persistence.entity.IdempotencyRecord;
import com.fermapp.api.persistence.repository.IdempotencyRecordRepository;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Pins the behaviour of the shared infrastructure (common/*): helpers, JSON formats, errors,
 * responses, rate limiting, body size limit, idempotency, cache, storage and the upstream HTTP
 * client. Recorded from the Java implementation, so the Kotlin port must reproduce it exactly.
 */
@SpringBootTest(classes = {JacksonAutoConfiguration.class, JacksonConfig.class})
class CommonSnapshotTest {

    private static TimeZone previousZone;

    @Autowired
    private ObjectMapper mapper;

    @TempDir
    Path uploads;

    @BeforeAll
    static void fixZone() {
        previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Bucharest"));
    }

    @AfterAll
    static void restoreZone() {
        TimeZone.setDefault(previousZone);
    }

    static AppProperties properties(String environment, String uploadDir, String redisUrl) {
        return new AppProperties(environment, "*", redisUrl, "x".repeat(32), 900, 30, "owm-key", "sentinel-id",
                "sentinel-secret", "https://token.example/oauth", "https://sh.example", null, "claude-opus-5-5", null, null,
                null, null, uploadDir, false, false, 600);
    }

    private static Map<String, Object> error(Throwable error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("class", error.getClass().getSimpleName());
        out.put("message", error.getMessage());
        if (error instanceof ApiException api) {
            out.put("status", api.status().value());
            out.put("code", api.code());
            out.put("details", api.details());
        }
        return out;
    }

    private interface Action {
        Object run() throws Exception;
    }

    private static Object attempt(Action action) {
        try {
            return action.run();
        } catch (Throwable error) {
            return error(error);
        }
    }

    @Test
    void infrastructureMatchesTheSnapshot() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("js", js());
        out.put("times", times());
        out.put("text", text());
        out.put("concurrency", concurrency());
        out.put("json", json());
        out.put("errors", errors());
        out.put("responses", responses());
        out.put("rateLimits", rateLimits());
        out.put("bodySize", bodySize());
        out.put("idempotency", idempotency());
        out.put("cache", cache());
        out.put("storage", storage());
        out.put("upstream", upstream());
        Snapshots.assertMatches(mapper, "common", out);
    }

    private Map<String, Object> js() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("round", List.of(Js.round(2.5), Js.round(-2.5), Js.round(1.4999), Js.round(1.005, 2), Js.round(123.456, -1)));
        out.put("round2", List.of(Js.round2(1.005), Js.round2(-0.125), Js.round2(2.675)));
        out.put("round1", List.of(Js.round1(0.05), Js.round1(-0.05), Js.round1(9.95)));
        out.put("toFixed", List.of(Js.toFixed(1.005, 2), Js.toFixed(2.5, 0), Js.toFixed(-1.5, 0), Js.toFixed(0.1, 20),
                Js.toFixed(1e21, 2), Js.toFixed(-0.0, 1)));
        out.put("num", Arrays.asList(Js.num(6.0), Js.num(2.5), Js.num(1e21), Js.num(1e20), Js.num(Double.NaN),
                Js.num(-0.0), Js.num(0.1 + 0.2), Js.num(1e-7), Js.num((Number) null),
                Js.num(Integer.valueOf(5)), Js.num(Long.valueOf(-12)), Js.num(Double.valueOf(3.25))));
        // String(Infinity) is "Infinity" in JavaScript; the Java helper throws instead.
        out.put("numInfinity", attempt(() -> Js.num(Double.POSITIVE_INFINITY)));
        out.put("clamp", List.of(Js.clamp(5, 0, 1), Js.clamp(-5, 0, 1), Js.clamp(0.5, 0, 1)));
        out.put("finite", List.of(Js.finite(null), Js.finite(Double.NaN), Js.finite(Double.NEGATIVE_INFINITY), Js.finite(1.0)));
        return out;
    }

    private Map<String, Object> times() {
        Instant at = Instant.parse("2026-12-31T22:30:00.123456Z");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dayMs", Times.DAY_MS);
        out.put("iso", Arrays.asList(Times.iso(at), Times.iso(null), Times.iso(Instant.EPOCH)));
        out.put("isoDay", Arrays.asList(Times.isoDay(at), Times.isoDay(null)));
        out.put("localYear", List.of(Times.localYear(at), Times.utcYear(at)));
        out.put("utc", List.of(Times.utc(2026, 2, 30, 9), Times.utc(2026, 13, 1, 0), Times.utc(2026, 1, 0, 25)));
        out.put("utcMidnight", List.of(Times.utcMidnight("2026-04-05"), Times.utcMidnight("2026-04-05T10:00:00Z")));
        out.put("atUtcMidnight", Arrays.asList(Times.atUtcMidnight(LocalDate.of(2026, 3, 1)), Times.atUtcMidnight(null)));
        out.put("utcDate", Times.utcDate(at).toString());
        out.put("plusDays", List.of(Times.plusDays(at, 1.5), Times.plusDays(at, -0.25), Times.plusDays(at, 1e-9)));
        out.put("nowIsMillis", Times.now().getNano() % 1_000_000 == 0);
        out.put("currentLocalYear", Times.currentLocalYear() == Times.localYear(Instant.now()));
        return out;
    }

    private Map<String, Object> text() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trim", Arrays.asList(Text.trim(null), Text.trim("  a b \t\n"), Text.trim(" x "),
                Text.trim(" y "), Text.trim("\u0001z\u0001")));
        out.put("slice", Arrays.asList(Text.slice(null, 3), Text.slice("abcdef", 3), Text.slice("ab", 3)));
        out.put("isBlank", List.of(Text.isBlank(null), Text.isBlank(""), Text.isBlank(" ")));
        out.put("padNumber", List.of(Text.padNumber(7, 3), Text.padNumber(12345, 3), Text.padNumber(-5, 4)));
        out.put("containsPattern", List.of(Text.containsPattern("50%_A\\b"), Text.containsPattern(""),
                Text.containsPattern("Ştefan"), Text.containsPattern(null)));
        return out;
    }

    private Map<String, Object> concurrency() {
        Map<String, Object> out = new LinkedHashMap<>();
        Concurrency.Pair<Integer, String> pair = Concurrency.both(() -> 1, () -> "x");
        out.put("both", List.of(pair.first(), pair.second()));
        Set<Integer> seen = new ConcurrentSkipListSet<>();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(i);
        }
        Concurrency.mapLimit(items, 3, item -> {
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(5);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            seen.add(item);
            running.decrementAndGet();
        });
        out.put("mapLimitSeen", new ArrayList<>(seen));
        out.put("mapLimitBounded", peak.get() <= 3);
        out.put("mapLimitZeroLimit", attempt(() -> {
            List<Integer> done = new ArrayList<>();
            Concurrency.mapLimit(List.of(1, 2), 0, done::add);
            return new TreeSet<>(done);
        }));
        out.put("bothFailsRuntime", attempt(() -> Concurrency.both(() -> {
            throw Errors.conflict("first failed");
        }, () -> 2)));
        out.put("bothFailsError", attempt(() -> Concurrency.both(() -> 1, () -> {
            throw new AssertionError("boom");
        })));
        return out;
    }

    private record WithInstant(Instant at) {
    }

    private Map<String, Object> json() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        ObjectMapper plain = new ObjectMapper().registerModule(JsonFormats.module());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("instant", Instant.parse("2026-11-26T09:00:00.5Z"));
        values.put("six", 6.0);
        values.put("big", 1e15);
        values.put("bigger", 1e16);
        values.put("tenth", 0.1);
        values.put("nan", Double.NaN);
        values.put("infinity", Double.NEGATIVE_INFINITY);
        values.put("negativeZero", -0.0);
        values.put("tiny", 1e-7);
        values.put("array", new double[] {1.0, 2.5, Double.NaN, 1e300});
        values.put("integer", 7);
        out.put("plainWrite", plain.writeValueAsString(values));
        out.put("springWrite", mapper.writeValueAsString(values));
        List<Object> reads = new ArrayList<>();
        for (String input : List.of("\"2026-04-05\"", "\" 2026-04-05T10:00 \"", "\"2026-04-05T10:00:00+03:00\"",
                "\"2026-04-05T10:00:00.123Z\"", "\"1700000000000\"", "1700000000000", "1700000000000.9", "\"-5\"",
                "\"tomorrow\"", "\"\"", "true", "null")) {
            reads.add(attempt(() -> {
                WithInstant parsed = mapper.readValue("{\"at\":" + input + "}", WithInstant.class);
                return parsed.at() == null ? "null" : Times.iso(parsed.at());
            }));
        }
        out.put("instantReads", reads);
        out.put("lenientParse", Arrays.asList(JsonFormats.LenientInstantDeserializer.parse(null),
                JsonFormats.LenientInstantDeserializer.parse(""), JsonFormats.LenientInstantDeserializer.parse("2026-02-30"),
                JsonFormats.LenientInstantDeserializer.parse("12")));
        return out;
    }

    private List<Object> errors() {
        List<Object> out = new ArrayList<>();
        for (ApiException error : List.of(Errors.badRequest("Bad."), Errors.badRequest("Bad.", List.of("x")),
                Errors.unauthorized(), Errors.unauthorized("Expired."), Errors.forbidden(), Errors.forbidden("No."),
                Errors.notFound("Parcel"), Errors.conflict("Busy."), Errors.notConfigured("Weather"),
                Errors.upstreamFailed("NASA"), Errors.recordNotFound(), new ApiException(org.springframework.http.HttpStatus.I_AM_A_TEAPOT, "TEA", "Tea."))) {
            Map<String, Object> item = error(error);
            item.put("isRuntime", error instanceof RuntimeException);
            out.add(item);
        }
        out.add(ErrorBody.of("CODE", "Message."));
        out.add(ErrorBody.of("CODE", "Message.", List.of(new ErrorBody.FieldIssue("a.b", "Required"),
                new ErrorBody.FieldIssue(null, null))));
        out.add(new ErrorBody(new ErrorBody.Error("C", null, null)));
        return out;
    }

    private Map<String, Object> entity(ResponseEntity<?> response) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", response.getStatusCode().value());
        Map<String, Object> headers = new LinkedHashMap<>();
        response.getHeaders().forEach((name, value) -> headers.put(name, value));
        out.put("headers", headers);
        Object body = response.getBody();
        out.put("body", body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : body);
        return out;
    }

    private List<Object> responses() {
        List<Object> out = new ArrayList<>();
        out.add(entity(ApiResponse.ok(Map.of("a", 1))));
        out.add(entity(ApiResponse.ok(null)));
        out.add(entity(ApiResponse.created(List.of(1, 2))));
        out.add(entity(ApiResponse.accepted("queued")));
        out.add(entity(ApiResponse.noContent()));
        out.add(entity(ApiResponse.file("pdf".getBytes(StandardCharsets.UTF_8), "application/pdf", "raport ăîș 2026.pdf", false)));
        out.add(entity(ApiResponse.file(new byte[0], "image/png", "photo-1", true)));
        out.add(new ApiResponse<>(List.of("x")));
        return out;
    }

    private static Map<String, Object> response(MockHttpServletResponse response) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", response.getStatus());
        Map<String, Object> headers = new LinkedHashMap<>();
        for (String name : List.of("RateLimit-Policy", "RateLimit", "Retry-After", "Content-Type")) {
            headers.put(name, response.getHeader(name));
        }
        out.put("headers", headers);
        out.put("body", response.getContentAsString(StandardCharsets.UTF_8));
        return out;
    }

    private Map<String, Object> rateLimits() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> policies = new ArrayList<>();
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            MockHttpServletRequest withKey = new MockHttpServletRequest();
            withKey.setRemoteAddr("10.0.0.7");
            withKey.addHeader("x-device-key", "d".repeat(70));
            MockHttpServletRequest withoutKey = new MockHttpServletRequest();
            withoutKey.setRemoteAddr("10.0.0.8");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", policy.name());
            item.put("window", policy.window().toSeconds());
            item.put("limit", policy.limit(false));
            item.put("testLimit", policy.limit(true));
            item.put("message", policy.message());
            item.put("jsonBody", policy.jsonBody());
            item.put("keys", List.of(policy.keyOf(withKey), policy.keyOf(withoutKey)));
            policies.add(item);
        }
        out.put("policies", policies);

        RateLimiter limiter = new RateLimiter(properties("development", uploads.toString(), null), mapper);
        RateLimiter testLimiter = new RateLimiter(properties("test", uploads.toString(), null), mapper);
        for (RateLimitPolicy policy : List.of(RateLimitPolicy.CREDENTIALS, RateLimitPolicy.INGEST)) {
            List<Object> calls = new ArrayList<>();
            int limit = policy.limit(false);
            for (int i = 1; i <= limit + 1; i++) {
                MockHttpServletRequest request = new MockHttpServletRequest();
                request.setRemoteAddr("10.0.0.9");
                MockHttpServletResponse servlet = new MockHttpServletResponse();
                boolean allowed = limiter.allow(policy, request, servlet);
                if (i == 1 || i >= limit) {
                    Map<String, Object> call = response(servlet);
                    call.put("call", i);
                    call.put("allowed", allowed);
                    calls.add(call);
                }
            }
            out.put(policy.name(), calls);
        }
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse servlet = new MockHttpServletResponse();
        out.put("testEnvironment", List.of(testLimiter.allow(RateLimitPolicy.AI, request, servlet), response(servlet)));
        return out;
    }

    private Map<String, Object> bodySize() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        BodySizeLimitFilter filter = new BodySizeLimitFilter(mapper);
        out.put("maxBytes", BodySizeLimitFilter.MAX_JSON_BYTES);
        out.put("exceptionMessage", new BodySizeLimitFilter.PayloadTooLargeException().getMessage());
        out.put("isIOException", new BodySizeLimitFilter.PayloadTooLargeException() instanceof IOException);

        MockHttpServletRequest declaredTooLarge = new MockHttpServletRequest("POST", "/api/v1/x");
        declaredTooLarge.setContentType("application/json");
        declaredTooLarge.setContent(new byte[(int) BodySizeLimitFilter.MAX_JSON_BYTES + 1]);
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        MockFilterChain untouched = new MockFilterChain();
        filter.doFilter(declaredTooLarge, rejected, untouched);
        Map<String, Object> declared = response(rejected);
        declared.put("chainCalled", untouched.getRequest() != null);
        out.put("declaredTooLarge", declared);

        for (String type : List.of("text/plain", "APPLICATION/JSON; charset=utf-8")) {
            MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/api/v1/x") {
                @Override
                public long getContentLengthLong() {
                    return -1;
                }
            };
            chunked.setContentType(type);
            chunked.setContent(new byte[(int) BodySizeLimitFilter.MAX_JSON_BYTES + 10]);
            List<Object> reads = new ArrayList<>();
            filter.doFilter(chunked, new MockHttpServletResponse(), (req, res) -> {
                HttpServletRequest http = (HttpServletRequest) req;
                reads.add(http.getClass() == chunked.getClass() ? "original request" : "wrapped request");
                reads.add(attempt(() -> {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int count;
                    var stream = http.getInputStream();
                    while ((count = stream.read(buffer, 0, buffer.length)) > 0) {
                        total += count;
                    }
                    return total;
                }));
                reads.add(attempt(() -> http.getInputStream().read()));
                reads.add(http.getInputStream() == http.getInputStream());
            });
            out.put("streamed " + type, reads);
        }

        MockHttpServletRequest small = new MockHttpServletRequest("POST", "/api/v1/x");
        small.setContentType("application/json");
        small.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        List<Object> smallReads = new ArrayList<>();
        filter.doFilter(small, new MockHttpServletResponse(), (req, res) -> {
            var stream = ((HttpServletRequest) req).getInputStream();
            smallReads.add(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            smallReads.add(stream.isFinished());
            smallReads.add(stream.isReady());
        });
        out.put("small", smallReads);
        return out;
    }

    private static IdempotencyRecord record(UUID id, String route, int status, JsonNode response, Instant createdAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setId(id);
        record.setUserId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        record.setKey("key-1");
        record.setRoute(route);
        record.setStatusCode(status);
        record.setResponse(response);
        record.setCreatedAt(createdAt);
        return record;
    }

    private static Object outcome(IdempotencyService.Outcome outcome) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("class", outcome.getClass().getSimpleName());
        if (outcome instanceof IdempotencyService.Reserved reserved) {
            out.put("reservationId", reserved.reservationId());
        } else if (outcome instanceof IdempotencyService.Replay replay) {
            out.put("statusCode", replay.statusCode());
            out.put("response", replay.response());
        }
        return out;
    }

    private Map<String, Object> idempotency() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        UUID user = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        String route = "POST /api/v1/work/start";
        JsonNode saved = mapper.readTree("{\"data\":{\"id\":\"w1\"}}");

        IdempotencyRecordRepository fresh = mock(IdempotencyRecordRepository.class);
        when(fresh.saveAndFlush(any())).thenAnswer(invocation -> {
            IdempotencyRecord created = invocation.getArgument(0);
            Map<String, Object> seen = new LinkedHashMap<>();
            seen.put("userId", created.getUserId());
            seen.put("key", created.getKey());
            seen.put("route", created.getRoute());
            seen.put("statusCode", created.getStatusCode());
            seen.put("createdAt", created.getCreatedAt());
            out.put("savedRecord", seen);
            created.setId(id);
            return created;
        });
        out.put("fresh", outcome(new IdempotencyService(fresh).reserve(user, "key-1", route)));
        out.put("tooLong", attempt(() -> new IdempotencyService(fresh).reserve(user, "k".repeat(129), route)));
        out.put("longestAllowed", attempt(() -> outcome(new IdempotencyService(fresh).reserve(user, "k".repeat(128), route))));

        Map<String, IdempotencyRecord> existing = new LinkedHashMap<>();
        existing.put("inProgress", record(id, route, 0, null, Times.now()));
        existing.put("otherRoute", record(id, "POST /api/v1/other", 201, saved, Times.now()));
        existing.put("replay", record(id, route, 201, saved, Times.now()));
        existing.put("replayNoContent", record(id, route, 204, null, Times.now()));
        existing.put("stale", record(id, route, 0, null, Times.now().minus(Duration.ofMinutes(3))));
        existing.put("missing", null);
        for (Map.Entry<String, IdempotencyRecord> entry : existing.entrySet()) {
            IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
            when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
            when(repository.findByUserIdAndKey(user, "key-1")).thenReturn(Optional.ofNullable(entry.getValue()));
            Instant before = entry.getValue() == null ? null : entry.getValue().getCreatedAt();
            Object result = attempt(() -> outcome(new IdempotencyService(repository).reserve(user, "key-1", route)));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("result", result);
            item.put("refreshed", entry.getValue() != null && !entry.getValue().getCreatedAt().equals(before));
            out.put(entry.getKey(), item);
        }

        IdempotencyRecordRepository completing = mock(IdempotencyRecordRepository.class);
        IdempotencyRecord pending = record(id, route, 0, null, Times.now());
        when(completing.findById(id)).thenReturn(Optional.of(pending));
        IdempotencyService service = new IdempotencyService(completing);
        service.complete(id, 201, saved);
        service.complete(UUID.randomUUID(), 500, null);
        verify(completing).save(pending);
        out.put("completed", List.of(pending.getStatusCode(), pending.getResponse()));
        doThrow(new IllegalStateException("gone")).when(completing).deleteById(id);
        out.put("releaseIgnoresErrors", attempt(() -> {
            service.release(id);
            return "released";
        }));

        IdempotencyService filterService = mock(IdempotencyService.class);
        IdempotencyFilter filter = new IdempotencyFilter(filterService, mapper);
        List<Object> filtered = new ArrayList<>();
        for (Object[] scenario : new Object[][] {{"POST", "k", 201, "{\"data\":1}", true}, {"POST", "k", 409, "{}", true},
                {"POST", "k", 204, "", true}, {"GET", "k", 200, "{}", true}, {"POST", "", 200, "{}", true},
                {"POST", "k", 200, "{}", false}}) {
            MockHttpServletRequest request = new MockHttpServletRequest((String) scenario[0], "/api/v1/x");
            if (!((String) scenario[1]).isEmpty()) {
                request.addHeader("Idempotency-Key", scenario[1]);
            }
            if ((boolean) scenario[4]) {
                request.setAttribute(IdempotencyInterceptor.RESERVATION_ATTRIBUTE, id);
            }
            MockHttpServletResponse servlet = new MockHttpServletResponse();
            filter.doFilter(request, servlet, (req, res) -> {
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus((int) scenario[2]);
                res.getOutputStream().write(((String) scenario[3]).getBytes(StandardCharsets.UTF_8));
            });
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scenario", Arrays.asList(scenario));
            item.put("status", servlet.getStatus());
            item.put("body", servlet.getContentAsString());
            filtered.add(item);
        }
        verify(filterService).complete(eq(id), eq(201), eq(mapper.readTree("{\"data\":1}")));
        verify(filterService).complete(eq(id), eq(204), eq(null));
        verify(filterService).release(id);
        verify(filterService, never()).complete(eq(id), eq(409), any());
        out.put("filter", filtered);
        out.put("reservationAttribute", IdempotencyInterceptor.RESERVATION_ATTRIBUTE);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cache() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        ObjectProvider<StringRedisTemplate> noRedis = mock(ObjectProvider.class);
        CacheService cache = new CacheService(properties("development", uploads.toString(), null), noRedis, mapper);
        verify(noRedis, never()).getIfAvailable();
        AtomicInteger loads = new AtomicInteger();
        List<Object> first = cache.cached("k1", 60, new TypeReference<List<Map<String, Object>>>() { }, () -> {
            loads.incrementAndGet();
            // An ordered map: Map.of iterates in a different order in every JVM.
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("at", Instant.parse("2026-01-01T00:00:00Z"));
            value.put("v", 1.5);
            return List.of(value);
        }).stream().map(Object.class::cast).toList();
        List<Map<String, Object>> second = cache.cached("k1", 60, new TypeReference<List<Map<String, Object>>>() { }, () -> {
            loads.incrementAndGet();
            return List.of();
        });
        out.put("typeReference", List.of(first, second, loads.get()));
        out.put("classType", List.of(cache.cached("k2", 60, String.class, () -> "token"),
                cache.cached("k2", 60, String.class, () -> "other")));
        out.put("expired", List.of(cache.cached("k3", 0, Integer.class, () -> 1), attempt(() -> {
            Thread.sleep(5);
            return cache.cached("k3", 0, Integer.class, () -> 2);
        })));
        out.put("loaderFails", attempt(() -> cache.cached("k4", 60, String.class, () -> {
            throw Errors.upstreamFailed("Weather");
        })));
        out.put("afterFailure", cache.cached("k4", 60, String.class, () -> "recovered"));
        out.put("nullValue", attempt(() -> cache.cached("k5", 60, String.class, () -> null)));
        out.put("nullValueAgain", attempt(() -> cache.cached("k5", 60, String.class, () -> "now set")));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sharedLoads = new AtomicInteger();
        Thread slow = Thread.ofVirtual().start(() -> cache.cached("k6", 60, String.class, () -> {
            sharedLoads.incrementAndGet();
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "shared";
        }));
        started.await(5, TimeUnit.SECONDS);
        List<String> joined = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread waiter = Thread.ofVirtual().start(() -> joined.add(cache.cached("k6", 60, String.class, () -> {
            sharedLoads.incrementAndGet();
            return "second load";
        })));
        Thread.sleep(50);
        release.countDown();
        slow.join(5000);
        waiter.join(5000);
        out.put("sharedLoad", List.of(joined, sharedLoads.get()));

        out.put("locks", List.of(cache.acquireLock("job", 60), cache.acquireLock("job", 60), cache.acquireLock("other", 60)));
        out.put("lockKeyIsCacheKey", cache.cached("lock:job", 60, String.class, () -> "not loaded"));

        CacheService bounded = new CacheService(properties("development", uploads.toString(), null), noRedis, mapper);
        for (int i = 0; i <= 5000; i++) {
            int value = i;
            bounded.cached("b" + i, 60, Integer.class, () -> value);
        }
        out.put("bounded", List.of(bounded.cached("b0", 60, Integer.class, () -> -1),
                bounded.cached("b1", 60, Integer.class, () -> -1), bounded.cached("b5000", 60, Integer.class, () -> -1)));

        ObjectProvider<StringRedisTemplate> brokenRedis = mock(ObjectProvider.class);
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(brokenRedis.getIfAvailable()).thenReturn(template);
        when(template.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        CacheService fallback = new CacheService(properties("development", uploads.toString(), "redis://localhost:1"), brokenRedis, mapper);
        out.put("redisDown", List.of(fallback.cached("r", 60, String.class, () -> "a"), fallback.cached("r", 60, String.class, () -> "b"),
                fallback.acquireLock("r", 60), fallback.acquireLock("r", 60)));
        verify(template, org.mockito.Mockito.times(1)).opsForValue();
        return out;
    }

    private Map<String, Object> storage() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00};
        byte[] webp = "RIFF0000WEBPxx".getBytes(StandardCharsets.ISO_8859_1);
        byte[] heic = "0000ftypheicxxx".getBytes(StandardCharsets.ISO_8859_1);
        byte[] avif = "0000ftypavifxxx".getBytes(StandardCharsets.ISO_8859_1);
        List<Object> types = new ArrayList<>();
        for (byte[] buffer : List.of(jpeg, png, webp, heic, avif, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff},
                Arrays.copyOf(png, 8), new byte[0])) {
            types.add(FileStorage.detectImageType(buffer));
        }
        out.put("detect", types);

        FileStorage storage = new FileStorage(properties("development", uploads.toString(), null));
        String key = storage.save("photos/farm-1", "jpg", jpeg);
        out.put("keyShape", key.matches("photos/farm-1/[0-9a-f-]{36}\\.jpg"));
        out.put("onDisk", Files.exists(uploads.resolve(key)));
        out.put("load", Arrays.equals(storage.load(key), jpeg));
        storage.remove(key);
        // The message holds the temporary folder, so only the exception type is recorded.
        out.put("afterRemove", attempt(() -> storage.load(key)) instanceof Map<?, ?> failure ? failure.get("class") : "loaded");
        out.put("traversalLoad", attempt(() -> storage.load("../outside.txt")));
        out.put("traversalSave", attempt(() -> storage.save("..", "txt", new byte[0])));
        out.put("traversalRemove", attempt(() -> {
            storage.remove("../../etc/passwd");
            storage.remove("missing/file.jpg");
            return "ignored";
        }));
        return out;
    }

    private Map<String, Object> upstream() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, AtomicInteger> hits = new java.util.concurrent.ConcurrentHashMap<>();
        List<Object> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int hit = hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            Map<String, Object> seen = new LinkedHashMap<>();
            seen.put("method", exchange.getRequestMethod());
            seen.put("uri", exchange.getRequestURI().toString());
            seen.put("contentType", exchange.getRequestHeaders().getFirst("Content-Type"));
            seen.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
            seen.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            received.add(seen);
            int status;
            String body;
            switch (path) {
                case "/ok" -> { status = 200; body = "{\"a\":[1,2.5]}"; }
                case "/flaky" -> { status = hit < 3 ? 503 : 200; body = "{\"attempt\":" + hit + "}"; }
                case "/down" -> { status = 502; body = "bad gateway"; }
                case "/missing" -> { status = 404; body = "{}"; }
                case "/notjson" -> { status = 200; body = "<html>"; }
                case "/redirect" -> {
                    exchange.getResponseHeaders().add("Location", "/ok");
                    status = 302;
                    body = "";
                }
                case "/slow" -> {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    status = 200;
                    body = "{}";
                }
                default -> { status = 201; body = "{\"echo\":true}"; }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            UpstreamHttp http = new UpstreamHttp(mapper);
            UpstreamRequest get = UpstreamRequest.get("Svc", base + "/ok");
            out.put("defaults", List.of(get.service(), get.method(), get.url().replace(base, ""), get.headers(),
                    String.valueOf(get.body()), get.timeout().toString(), get.retries()));
            UpstreamRequest shorter = get.timeout(Duration.ofSeconds(1));
            out.put("timeoutCopy", List.of(shorter.timeout().toString(), get.timeout().toString(), shorter.url().equals(get.url())));
            out.put("ok", http.fetchJson(get));
            out.put("flaky", attempt(() -> http.fetchJson(UpstreamRequest.get("Svc", base + "/flaky"))));
            out.put("flakyHits", hits.get("/flaky").get());
            out.put("down", attempt(() -> http.fetchJson(UpstreamRequest.get("Down", base + "/down"))));
            out.put("downHits", hits.get("/down").get());
            out.put("missing", attempt(() -> http.fetchJson(UpstreamRequest.get("Gone", base + "/missing"))));
            out.put("missingHits", hits.get("/missing").get());
            out.put("notJson", attempt(() -> http.fetchJson(UpstreamRequest.get("Html", base + "/notjson"))));
            out.put("redirect", attempt(() -> http.fetchJson(UpstreamRequest.get("Moved", base + "/redirect"))));
            out.put("timeout", attempt(() -> http.fetchBytes(new UpstreamRequest("Slow", "GET", base + "/slow", Map.of(), null,
                    Duration.ofMillis(300), 0))));
            out.put("post", new String(http.fetchBytes(UpstreamRequest.post("Post", base + "/post",
                    Map.of("Content-Type", "application/x-www-form-urlencoded", "Authorization", "Bearer t"), "a=1&b=%20")),
                    StandardCharsets.UTF_8));
            out.put("connectionRefused", attempt(() -> http.fetchBytes(new UpstreamRequest("Nowhere", "GET",
                    "http://127.0.0.1:1/x", Map.of(), null, Duration.ofSeconds(2), 0))));
            out.put("received", received.stream().filter(item -> !((Map<?, ?>) item).get("uri").equals("/slow")).toList());
        } finally {
            server.stop(0);
        }
        return out;
    }
}
