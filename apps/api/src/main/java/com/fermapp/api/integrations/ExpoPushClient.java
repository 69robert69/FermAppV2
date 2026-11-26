package com.fermapp.api.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.config.AppProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Sends push notifications through the Expo Push service, in batches of 100. */
@Component
public class ExpoPushClient {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushClient.class);
    private static final String EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send";
    private static final int EXPO_BATCH = 100;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final AppProperties properties;

    public ExpoPushClient(ObjectMapper mapper, AppProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * Sends the message to every token and returns the tokens Expo reported as
     * DeviceNotRegistered, so the caller can forget them.
     */
    public List<String> send(List<String> tokens, String title, String body, Map<String, String> data) {
        List<String> stale = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i += EXPO_BATCH) {
            List<String> batch = tokens.subList(i, Math.min(tokens.size(), i + EXPO_BATCH));
            try {
                List<Map<String, Object>> messages = new ArrayList<>();
                for (String to : batch) {
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("to", to);
                    message.put("title", title);
                    message.put("body", body);
                    if (data != null) {
                        message.put("data", data);
                    }
                    message.put("sound", "default");
                    message.put("priority", "high");
                    messages.add(message);
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(EXPO_PUSH_URL))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(messages)));
                if (properties.hasExpoToken()) {
                    request.header("Authorization", "Bearer " + properties.expoAccessToken());
                }
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("Expo Push rejected the batch: {}", response.statusCode());
                    continue;
                }
                JsonNode result = mapper.readTree(response.body()).path("data");
                for (int index = 0; index < batch.size(); index++) {
                    if ("DeviceNotRegistered".equals(result.path(index).path("details").path("error").asText(null))) {
                        stale.add(batch.get(index));
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return stale;
            } catch (Exception error) {
                log.warn("Push delivery failed: {}", error.getMessage());
            }
        }
        return stale;
    }
}
