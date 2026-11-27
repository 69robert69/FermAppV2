package com.fermapp.api.integrations

import com.fasterxml.jackson.databind.ObjectMapper
import com.fermapp.api.config.AppProperties
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Sends push notifications through the Expo Push service, in batches of 100. */
@Component
class ExpoPushClient(private val mapper: ObjectMapper, private val properties: AppProperties) {

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /**
     * Sends the message to every token and returns the tokens Expo reported as
     * DeviceNotRegistered, so the caller can forget them.
     */
    fun send(tokens: List<String>, title: String?, body: String?, data: Map<String, String>?): List<String> {
        val stale = mutableListOf<String>()
        for (batch in tokens.chunked(EXPO_BATCH)) {
            try {
                val messages = batch.map { to ->
                    linkedMapOf<String, Any?>("to" to to, "title" to title, "body" to body).apply {
                        if (data != null) {
                            put("data", data)
                        }
                        put("sound", "default")
                        put("priority", "high")
                    }
                }
                val request = HttpRequest.newBuilder(URI.create(EXPO_PUSH_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(messages)))
                if (properties.hasExpoToken()) {
                    request.header("Authorization", "Bearer " + properties.expoAccessToken())
                }
                val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    log.warn("Expo Push rejected the batch: {}", response.statusCode())
                    continue
                }
                val result = mapper.readTree(response.body()).path("data")
                batch.forEachIndexed { index, token ->
                    if (result.path(index).path("details").path("error").asText(null) == "DeviceNotRegistered") {
                        stale += token
                    }
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return stale
            } catch (error: Exception) {
                log.warn("Push delivery failed: {}", error.message)
            }
        }
        return stale
    }

    private companion object {
        val log = LoggerFactory.getLogger(ExpoPushClient::class.java)
        const val EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send"
        const val EXPO_BATCH = 100
    }
}
