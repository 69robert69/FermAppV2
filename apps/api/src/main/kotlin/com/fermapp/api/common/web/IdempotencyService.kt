package com.fermapp.api.common.web

import com.fasterxml.jackson.databind.JsonNode
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Times
import com.fermapp.api.persistence.entity.IdempotencyRecord
import com.fermapp.api.persistence.repository.IdempotencyRecordRepository
import java.time.Duration
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

/**
 * Reservations for operations resent from the phone's offline queue. The first request with
 * a key reserves it; a repeat replays the saved response; a reservation older than two minutes
 * without a response means the server stopped mid-operation, so the operation runs again.
 */
@Service
class IdempotencyService(private val records: IdempotencyRecordRepository) {

    /** Either a fresh reservation to complete later, or a saved response to replay. */
    sealed interface Outcome

    @JvmRecord
    data class Reserved(val reservationId: UUID) : Outcome

    @JvmRecord
    data class Replay(val statusCode: Int, val response: JsonNode?) : Outcome

    fun reserve(userId: UUID, key: String, route: String): Outcome {
        if (key.length > 128) {
            throw Errors.badRequest("The idempotency key is too long.")
        }
        try {
            val record = IdempotencyRecord()
            record.userId = userId
            record.key = key
            record.route = route
            record.statusCode = 0
            return Reserved(records.saveAndFlush(record).id)
        } catch (duplicate: DataIntegrityViolationException) {
            // The key was used before: replay, wait or retry below.
        }
        val found = records.findByUserIdAndKey(userId, key).orElse(null)
        if (found != null && found.route != route) {
            throw Errors.conflict("The idempotency key was used for a different operation.")
        }
        if (found == null || found.statusCode == 0) {
            if (found != null && Times.now().toEpochMilli() - found.createdAt!!.toEpochMilli() > STALE_RESERVATION.toMillis()) {
                found.createdAt = Times.now()
                records.save(found)
                return Reserved(found.id)
            }
            throw Errors.conflict("The operation is already being processed.")
        }
        return Replay(found.statusCode, found.response)
    }

    fun complete(reservationId: UUID, statusCode: Int, response: JsonNode?) {
        records.findById(reservationId).ifPresent { record ->
            record.statusCode = statusCode
            record.response = response
            records.save(record)
        }
    }

    fun release(reservationId: UUID) {
        try {
            records.deleteById(reservationId)
        } catch (ignored: RuntimeException) {
            // Already gone: nothing to release.
        }
    }

    private companion object {
        val STALE_RESERVATION: Duration = Duration.ofMinutes(2)
    }
}
