package com.fermapp.api.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AccessInterceptor
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Runs after authentication for authenticated, non-GET routes that carry an Idempotency-Key.
 * It reserves the key or replays the saved response with `Idempotency-Replayed: true`.
 * [IdempotencyFilter] stores the response once the handler has finished.
 */
@Component
class IdempotencyInterceptor(
    private val idempotency: IdempotencyService,
    private val mapper: ObjectMapper,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod || request.method == "GET") {
            return true
        }
        val access = AccessInterceptor.accessOf(handler)
        val auth = AccessInterceptor.current()
        val key = request.getHeader("Idempotency-Key")
        if (access == null || access.value == AccessGroup.PUBLIC || auth == null || key.isNullOrEmpty()) {
            return true
        }
        val route = request.method + " " + request.requestURI
        when (val outcome = idempotency.reserve(auth.userId(), key, route)) {
            is IdempotencyService.Reserved -> {
                request.setAttribute(RESERVATION_ATTRIBUTE, outcome.reservationId)
                return true
            }
            is IdempotencyService.Replay -> {
                response.setHeader("Idempotency-Replayed", "true")
                response.status = outcome.statusCode
                if (outcome.statusCode != 204) {
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.characterEncoding = "UTF-8"
                    mapper.writeValue(response.outputStream, outcome.response)
                }
                return false
            }
        }
    }

    companion object {
        @JvmField
        val RESERVATION_ATTRIBUTE: String = IdempotencyInterceptor::class.java.name + ".reservation"
    }
}
