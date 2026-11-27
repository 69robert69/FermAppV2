package com.fermapp.api.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fermapp.api.common.error.ErrorBody
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

/** JSON bodies are limited to 2 MB, like `express.json({ limit: '2mb' })`: larger ones get 413 TOO_LARGE. */
class BodySizeLimitFilter(private val mapper: ObjectMapper) : OncePerRequestFilter() {

    /** Thrown when a chunked body grows past the limit while it is being read. */
    class PayloadTooLargeException : IOException("The request body is too large.")

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val type = request.contentType
        return type == null || !type.lowercase().contains("json")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.contentLengthLong > MAX_JSON_BYTES) {
            response.status = 413
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            mapper.writeValue(response.outputStream, ErrorBody.of("TOO_LARGE", "The request body is too large."))
            return
        }
        chain.doFilter(LimitedRequest(request), response)
    }

    private class LimitedRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
        private var stream: ServletInputStream? = null

        override fun getInputStream(): ServletInputStream =
            stream ?: CountingStream(super.getInputStream()).also { stream = it }
    }

    /** Counts the bytes read and fails once the body is larger than the limit. */
    private class CountingStream(private val original: ServletInputStream) : ServletInputStream() {
        private var read = 0L

        private fun count(bytes: Long) {
            read += bytes
            if (read > MAX_JSON_BYTES) {
                throw PayloadTooLargeException()
            }
        }

        override fun read(): Int {
            val value = original.read()
            if (value >= 0) {
                count(1)
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = original.read(buffer, offset, length)
            if (count > 0) {
                count(count.toLong())
            }
            return count
        }

        override fun isFinished(): Boolean = original.isFinished

        override fun isReady(): Boolean = original.isReady

        override fun setReadListener(listener: ReadListener) = original.setReadListener(listener)
    }

    companion object {
        const val MAX_JSON_BYTES = 2L * 1024 * 1024
    }
}
