package com.fermapp.api.integrations;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fermapp.api.config.AppProperties;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The agronomy assistant model, through the official Anthropic Java SDK. Same request as the
 * TypeScript server: streaming with the final message assembled, adaptive thinking, medium
 * effort, a cached system prompt and server-side refusal fallbacks. Any failure returns null so
 * the caller answers from the parcel data instead.
 */
@Component
public class AnthropicAssistantClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAssistantClient.class);
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    /** One earlier turn of the conversation. */
    public record Turn(boolean fromUser, String content) {
    }

    private final AnthropicClient client;
    private final String model;

    public AnthropicAssistantClient(AppProperties properties) {
        this.client = properties.hasAnthropic()
                ? AnthropicOkHttpClient.builder().apiKey(properties.anthropicApiKey()).build()
                : null;
        this.model = properties.anthropicModel();
    }

    public boolean isConfigured() {
        return client != null;
    }

    /** Returns the answer text, or null when the model is not configured, refuses or does not respond. */
    public String ask(String systemPrompt, List<Turn> history, String userContent) {
        if (client == null) {
            return null;
        }
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .addBeta(FALLBACK_BETA)
                .fallbacksDefault()
                .thinking(BetaThinkingConfigAdaptive.builder().build())
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(BetaCacheControlEphemeral.builder().build())
                        .build()));
        for (Turn turn : history) {
            if (turn.fromUser()) {
                params.addUserMessage(turn.content());
            } else {
                params.addAssistantMessage(turn.content());
            }
        }
        params.addUserMessage(userContent);

        try (StreamResponse<BetaRawMessageStreamEvent> stream = client.beta().messages().createStreaming(params.build())) {
            BetaMessageAccumulator accumulator = BetaMessageAccumulator.create();
            stream.stream().forEach(accumulator::accumulate);
            BetaMessage response = accumulator.message();
            if (response.stopReason().map(reason -> "refusal".equals(reason.asString())).orElse(false)) {
                return null;
            }
            String text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(block -> block.text())
                    .collect(Collectors.joining())
                    .trim();
            return text.isEmpty() ? null : text;
        } catch (AnthropicServiceException error) {
            log.warn("AI assistant unavailable: status {}", error.statusCode());
            return null;
        } catch (RuntimeException error) {
            log.warn("AI assistant unavailable: {}", error.getMessage());
            return null;
        }
    }

    @PreDestroy
    void close() {
        if (client != null) {
            client.close();
        }
    }
}
