package com.fermapp.api.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper

/**
 * Captures the response of a reserved operation: a success is saved for replays, an error
 * releases the reservation so the operation can be sent again.
 */
class IdempotencyFilter(
    private val idempotency: IdempotencyService,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method == "GET" || request.getHeader("Idempotency-Key").isNullOrEmpty()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val wrapper = ContentCachingResponseWrapper(response)
        try {
            chain.doFilter(request, wrapper)
        } finally {
            val reservation = request.getAttribute(IdempotencyInterceptor.RESERVATION_ATTRIBUTE)
            if (reservation is UUID) {
                val status = wrapper.status
                if (status >= 400) {
                    idempotency.release(reservation)
                } else {
                    val body = wrapper.contentAsByteArray
                    idempotency.complete(reservation, status, if (body.isEmpty()) null else mapper.readTree(body))
                }
            }
            wrapper.copyBodyToResponse()
        }
    }
}
