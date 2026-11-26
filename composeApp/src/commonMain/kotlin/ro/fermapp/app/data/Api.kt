package ro.fermapp.app.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer
import okio.Path
import ro.fermapp.app.platform.apiOrigin
import ro.fermapp.app.platform.appDataDir
import ro.fermapp.app.platform.fileSystem
import ro.fermapp.app.platform.platformHttpClient
import kotlin.random.Random

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

class ApiError(val status: Int, val code: String, override val message: String, val details: List<ApiErrorDetail>? = null) : Exception(message)

/** The user-facing reason an action failed, as lib/hooks.ts errorMessage. */
fun errorMessage(error: Throwable): String = when (error) {
    is ApiError -> error.details?.firstOrNull()?.message ?: error.message
    else -> "An unexpected problem occurred. Try again."
}

/** Result of a change saved locally until the connection returns. */
sealed interface SendResult<out T> {
    data class Done<T>(val value: T) : SendResult<T>
    data object Queued : SendResult<Nothing>
}

fun newOperationId(): String = "xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx".map { if (it == 'x') Random.nextInt(16).toString(16)[0] else it }.joinToString("")

/**
 * The REST client of apps/mobile/src/api/client.ts: bearer token with a single
 * shared refresh, offline reads from the last saved answer, and writes queued
 * in the outbox when there is no connection.
 */
object Api {
    val apiUrl: String get() = "${apiOrigin.trimEnd('/')}/api/v1"

    val http: HttpClient by lazy {
        platformHttpClient().config {
            install(HttpTimeout) {
                requestTimeoutMillis = 45_000
                connectTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }

    private val refreshLock = Mutex()
    private var refreshing: CompletableDeferred<String?>? = null

    /** Renews the session only once, even if several requests expire at the same time. */
    suspend fun refreshAccessToken(): String? {
        val waiting = refreshLock.withLock {
            refreshing?.let { return@withLock it to false }
            CompletableDeferred<String?>().also { refreshing = it } to true
        }
        val (deferred, owner) = waiting
        if (!owner) return deferred.await()
        val token = runCatching {
            val refreshToken = Auth.current.session?.refreshToken ?: return@runCatching null
            val response = http.request("$apiUrl/auth/refresh") {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(AppJson.encodeToString(RefreshBody.serializer(), RefreshBody(refreshToken)))
            }
            if (response.status.value == 401) {
                Auth.signOut()
                return@runCatching null
            }
            if (response.status.value !in 200..299) return@runCatching null
            val tokens = AppJson.decodeFromJsonElement(Tokens.serializer(), envelope(response).data ?: return@runCatching null)
            Auth.updateTokens(tokens.accessToken, tokens.refreshToken)
            tokens.accessToken
        }.getOrNull()
        refreshLock.withLock { refreshing = null }
        deferred.complete(token)
        return token
    }

    @kotlinx.serialization.Serializable
    private data class RefreshBody(val refreshToken: String)

    private suspend fun request(path: String, build: io.ktor.client.request.HttpRequestBuilder.() -> Unit, canRefresh: Boolean = true): HttpResponse {
        val token = Auth.current.session?.accessToken
        val response = http.request("$apiUrl$path") {
            header(HttpHeaders.Accept, "application/json")
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            build()
        }
        if (response.status.value == 401 && canRefresh && token != null) {
            val renewed = refreshAccessToken()
            if (renewed != null) return request(path, build, false)
        }
        return response
    }

    private suspend fun envelope(response: HttpResponse): ApiEnvelope {
        val text = response.bodyAsText()
        return if (text.isBlank()) ApiEnvelope() else runCatching { AppJson.decodeFromString(ApiEnvelope.serializer(), text) }.getOrDefault(ApiEnvelope())
    }

    private suspend fun unwrap(response: HttpResponse): JsonElement {
        if (response.status.value == 204) return JsonNull
        val payload = envelope(response)
        if (response.status.value !in 200..299) {
            throw ApiError(
                response.status.value,
                payload.error?.code ?: "UNKNOWN",
                payload.error?.message ?: "The server could not process the request.",
                payload.error?.details,
            )
        }
        return payload.data ?: JsonNull
    }

    private fun toQuery(query: Map<String, Any?>): String {
        val params = query.filterValues { it != null }
        return if (params.isEmpty()) "" else "?" + params.entries.joinToString("&") { (k, v) -> "$k=${v.toString().encodeURLParameter()}" }
    }

    /** Reads from the server, and without a connection returns the last saved version. */
    suspend fun getJson(path: String, query: Map<String, Any?> = emptyMap()): JsonElement {
        val url = path + toQuery(query)
        val scope = "${Auth.current.farmId ?: "anon"}:$url"
        try {
            val data = unwrap(request(url, {}))
            LocalStore.writeCache(scope, data)
            Sync.markOnline(true)
            return data
        } catch (error: ApiError) {
            throw error
        } catch (error: Exception) {
            Sync.markOnline(false)
            LocalStore.readCache(scope)?.let { return it }
            throw ApiError(0, "OFFLINE", "There is no connection, and this data was not saved on the phone.")
        }
    }

    suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, query: Map<String, Any?> = emptyMap()): T =
        AppJson.decodeFromJsonElement(strategy, getJson(path, query))

    suspend inline fun <reified T> get(path: String, query: Map<String, Any?> = emptyMap()): T = get(path, serializer<T>(), query)

    /**
     * Sends a change. With [offline] allowed, a missing connection keeps it in the outbox,
     * and the operation id doubles as the Idempotency-Key so a resend is applied once.
     */
    suspend fun sendJson(
        method: HttpMethod,
        path: String,
        body: JsonElement? = null,
        offline: Boolean = true,
        operationId: String = newOperationId(),
        idempotent: Boolean = true,
    ): SendResult<JsonElement> {
        try {
            val response = request(path, {
                this.method = method
                contentType(ContentType.Application.Json)
                if (idempotent) header("Idempotency-Key", operationId)
                if (body != null) setBody(AppJson.encodeToString(JsonElement.serializer(), body))
            })
            val data = unwrap(response)
            Sync.markOnline(true)
            return SendResult.Done(data)
        } catch (error: ApiError) {
            throw error
        } catch (error: Exception) {
            Sync.markOnline(false)
            if (!offline) throw ApiError(0, "OFFLINE", "This action needs an internet connection.")
            LocalStore.enqueue(operationId, method.value, path, body)
            Sync.refreshCounts()
            return SendResult.Queued
        }
    }

    suspend fun <B, T> send(
        method: HttpMethod,
        path: String,
        body: B?,
        bodySerializer: KSerializer<B>,
        result: DeserializationStrategy<T>,
        offline: Boolean = true,
    ): SendResult<T> {
        val json = body?.let { AppJson.encodeToJsonElement(bodySerializer, it) }
        return when (val sent = sendJson(method, path, json, offline)) {
            is SendResult.Done -> SendResult.Done(AppJson.decodeFromJsonElement(result, sent.value))
            SendResult.Queued -> SendResult.Queued
        }
    }

    /** Sends a JSON object built in place; the answer is ignored by most screens. */
    suspend fun post(path: String, body: JsonElement? = null, offline: Boolean = true) = sendJson(HttpMethod.Post, path, body, offline)
    suspend fun patch(path: String, body: JsonElement? = null, offline: Boolean = true) = sendJson(HttpMethod.Patch, path, body, offline)
    suspend fun delete(path: String, offline: Boolean = true) = sendJson(HttpMethod.Delete, path, null, offline)

    /** Uploads a file (a KML, a photo) as multipart form data. */
    suspend fun upload(path: String, fileName: String, bytes: ByteArray, mimeType: String, fields: Map<String, String> = emptyMap()): JsonElement {
        try {
            val token = Auth.current.session?.accessToken
            val response = http.submitFormWithBinaryData(
                url = "$apiUrl$path",
                formData = formData {
                    fields.forEach { (name, value) -> append(name, value) }
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, mimeType)
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        },
                    )
                },
            ) {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }
            return unwrap(response)
        } catch (error: ApiError) {
            throw error
        } catch (error: Exception) {
            throw ApiError(0, "OFFLINE", "Uploading needs an internet connection.")
        }
    }

    /** Downloads a file generated by the server (a PDF or CSV report) and returns its local path. */
    suspend fun download(path: String, fileName: String): Path {
        val destination = appDataDir / "downloads" / fileName
        fileSystem.createDirectories(destination.parent!!)
        suspend fun attempt(token: String?): Boolean = http.prepareGet("$apiUrl$path") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        }.execute { response ->
            if (response.status.value !in 200..299) return@execute false
            val bytes = response.bodyAsChannel().readRemaining().readByteArray()
            fileSystem.write(destination) { write(bytes) }
            true
        }
        try {
            if (attempt(Auth.current.session?.accessToken)) return destination
            val token = refreshAccessToken() ?: throw ApiError(0, "OFFLINE", "The file could not be downloaded. Check your connection.")
            if (attempt(token)) return destination
        } catch (error: ApiError) {
            throw error
        } catch (_: Exception) {
        }
        throw ApiError(0, "OFFLINE", "The file could not be downloaded. Check your connection.")
    }
}
