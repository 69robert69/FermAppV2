package com.fermapp.api.common.web

import java.nio.charset.StandardCharsets
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.util.UriUtils

/**
 * Successful responses have the form `{ "data": ... }`, as in the TypeScript router.
 * These helpers fix the status codes: 200 for reads and actions, 201 for creations,
 * 202 for accepted telemetry and 204 when there is nothing to return.
 */
@JvmRecord
data class ApiResponse<T>(val data: T) {

    companion object {
        @JvmStatic
        fun <T> ok(data: T): ResponseEntity<ApiResponse<T>> = ResponseEntity.ok(ApiResponse(data))

        @JvmStatic
        fun <T> created(data: T): ResponseEntity<ApiResponse<T>> = ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse(data))

        @JvmStatic
        fun <T> accepted(data: T): ResponseEntity<ApiResponse<T>> = ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse(data))

        @JvmStatic
        fun noContent(): ResponseEntity<Void> = ResponseEntity.noContent().build()

        /**
         * A file download, with the filename encoded as in the original
         * `Content-Disposition: attachment; filename*=UTF-8''...`.
         */
        @JvmStatic
        fun file(content: ByteArray, contentType: String, filename: String, inline: Boolean): ResponseEntity<ByteArray> {
            val disposition = (if (inline) "inline" else "attachment") + "; filename*=UTF-8''" +
                UriUtils.encode(filename, StandardCharsets.UTF_8)
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType(contentType))
                .body(content)
        }
    }
}
