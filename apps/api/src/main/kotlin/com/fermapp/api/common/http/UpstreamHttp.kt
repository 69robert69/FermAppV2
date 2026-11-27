package com.fermapp.api.common.http

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fermapp.api.common.error.Errors
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Calls to external services with a timeout and retries (lib/http.ts). Retryable statuses and
 * network errors are retried with exponential back-off; after the last attempt the caller
 * gets a clear 502 UPSTREAM_FAILED.
 */
@Component
class UpstreamHttp(private val mapper: ObjectMapper) {

    /** A request to an external service. [body] is null for GET. */
    @JvmRecord
    data class UpstreamRequest(
        val service: String,
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?,
        val timeout: Duration,
        val retries: Int,
    ) {
        fun timeout(value: Duration): UpstreamRequest = copy(timeout = value)

        companion object {
            @JvmStatic
            fun get(service: String, url: String) = UpstreamRequest(service, "GET", url, mapOf(), null, Duration.ofSeconds(15), 2)

            @JvmStatic
            fun post(service: String, url: String, headers: Map<String, String>, body: String?) =
                UpstreamRequest(service, "POST", url, headers, body, Duration.ofSeconds(15), 2)
        }
    }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun fetchBytes(request: UpstreamRequest): ByteArray {
        var lastProblem: String? = "unknown"
        for (attempt in 0..request.retries) {
            if (attempt > 0) {
                sleep(400L * (1L shl (attempt - 1)))
            }
            try {
                val builder = HttpRequest.newBuilder(URI.create(request.url)).timeout(request.timeout)
                request.headers.forEach { (name, value) -> builder.header(name, value) }
                builder.method(
                    request.method,
                    request.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody(),
                )
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() in 200..299) {
                    return response.body()
                }
                lastProblem = "HTTP " + response.statusCode()
                if (response.statusCode() !in RETRYABLE) {
                    break
                }
            } catch (error: IOException) {
                lastProblem = error.message
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                lastProblem = "interrupted"
                break
            }
        }
        log.warn("{}: call failed: {}", request.service, lastProblem)
        throw Errors.upstreamFailed(request.service)
    }

    fun fetchJson(request: UpstreamRequest): JsonNode {
        val body = fetchBytes(request)
        try {
            return mapper.readTree(body)
        } catch (error: IOException) {
            log.warn("{}: invalid JSON: {}", request.service, error.message)
            throw Errors.upstreamFailed(request.service)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(UpstreamHttp::class.java)
        val RETRYABLE = setOf(408, 425, 429, 500, 502, 503, 504)

        fun sleep(millis: Long) {
            try {
                Thread.sleep(millis)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
