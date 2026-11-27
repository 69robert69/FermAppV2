package com.fermapp.api.integrations

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.MessageCreateParams
import com.fermapp.api.config.AppProperties
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The agronomy assistant model, through the official Anthropic Java SDK. Same request as the
 * TypeScript server: streaming with the final message assembled, adaptive thinking, medium
 * effort, a cached system prompt and server-side refusal fallbacks. Any failure returns null so
 * the caller answers from the parcel data instead.
 */
@Component
class AnthropicAssistantClient(properties: AppProperties) {

    /** One earlier turn of the conversation. */
    @JvmRecord
    data class Turn(val fromUser: Boolean, val content: String)

    private val client: AnthropicClient? =
        if (properties.hasAnthropic()) AnthropicOkHttpClient.builder().apiKey(properties.anthropicApiKey()).build() else null
    private val model: String = properties.anthropicModel()

    fun isConfigured(): Boolean = client != null

    /** Returns the answer text, or null when the model is not configured, refuses or does not respond. */
    fun ask(systemPrompt: String, history: List<Turn>, userContent: String): String? {
        val client = client ?: return null
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .addBeta(FALLBACK_BETA)
            .fallbacksDefault()
            .thinking(BetaThinkingConfigAdaptive.builder().build())
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
            .systemOfBetaTextBlockParams(
                listOf(
                    BetaTextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(BetaCacheControlEphemeral.builder().build())
                        .build(),
                ),
            )
        for (turn in history) {
            if (turn.fromUser) params.addUserMessage(turn.content) else params.addAssistantMessage(turn.content)
        }
        params.addUserMessage(userContent)

        return try {
            client.beta().messages().createStreaming(params.build()).use { stream ->
                val accumulator = BetaMessageAccumulator.create()
                stream.stream().forEach { accumulator.accumulate(it) }
                val response = accumulator.message()
                if (response.stopReason().map { it.asString() == "refusal" }.orElse(false)) {
                    return null
                }
                response.content()
                    .flatMap { block -> block.text().map { listOf(it.text()) }.orElse(listOf()) }
                    .joinToString("")
                    .trim { it <= ' ' }
                    .ifEmpty { null }
            }
        } catch (error: AnthropicServiceException) {
            log.warn("AI assistant unavailable: status {}", error.statusCode())
            null
        } catch (error: RuntimeException) {
            log.warn("AI assistant unavailable: {}", error.message)
            null
        }
    }

    @PreDestroy
    fun close() {
        client?.close()
    }

    private companion object {
        val log = LoggerFactory.getLogger(AnthropicAssistantClient::class.java)
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}
