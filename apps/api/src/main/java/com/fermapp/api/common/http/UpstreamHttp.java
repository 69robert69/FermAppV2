package com.fermapp.api.common.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.common.error.Errors;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Calls to external services with a timeout and retries (lib/http.ts). Retryable statuses and
 * network errors are retried with exponential back-off; after the last attempt the caller
 * gets a clear 502 UPSTREAM_FAILED.
 */
@Component
public class UpstreamHttp {

    private static final Logger log = LoggerFactory.getLogger(UpstreamHttp.class);
    private static final Set<Integer> RETRYABLE = Set.of(408, 425, 429, 500, 502, 503, 504);

    /** A request to an external service. {@code body} is null for GET. */
    public record UpstreamRequest(String service, String method, String url, Map<String, String> headers, String body,
                                  Duration timeout, int retries) {

        public static UpstreamRequest get(String service, String url) {
            return new UpstreamRequest(service, "GET", url, Map.of(), null, Duration.ofSeconds(15), 2);
        }

        public UpstreamRequest timeout(Duration value) {
            return new UpstreamRequest(service, method, url, headers, body, value, retries);
        }

        public static UpstreamRequest post(String service, String url, Map<String, String> headers, String body) {
            return new UpstreamRequest(service, "POST", url, headers, body, Duration.ofSeconds(15), 2);
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper;

    public UpstreamHttp(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] fetchBytes(UpstreamRequest request) {
        String lastProblem = "unknown";
        for (int attempt = 0; attempt <= request.retries(); attempt++) {
            if (attempt > 0) {
                sleep(400L * (1L << (attempt - 1)));
            }
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url())).timeout(request.timeout());
                request.headers().forEach(builder::header);
                builder.method(request.method(), request.body() == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(request.body()));
                HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response.body();
                }
                lastProblem = "HTTP " + response.statusCode();
                if (!RETRYABLE.contains(response.statusCode())) {
                    break;
                }
            } catch (IOException error) {
                lastProblem = error.getMessage();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                lastProblem = "interrupted";
                break;
            }
        }
        log.warn("{}: call failed: {}", request.service(), lastProblem);
        throw Errors.upstreamFailed(request.service());
    }

    public JsonNode fetchJson(UpstreamRequest request) {
        byte[] body = fetchBytes(request);
        try {
            return mapper.readTree(body);
        } catch (IOException error) {
            log.warn("{}: invalid JSON: {}", request.service(), error.getMessage());
            throw Errors.upstreamFailed(request.service());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
