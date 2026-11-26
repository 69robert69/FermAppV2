package com.fermapp.api.common.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.persistence.entity.IdempotencyRecord;
import com.fermapp.api.persistence.repository.IdempotencyRecordRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Reservations for operations resent from the phone's offline queue. The first request with
 * a key reserves it; a repeat replays the saved response; a reservation older than two minutes
 * without a response means the server stopped mid-operation, so the operation runs again.
 */
@Service
public class IdempotencyService {

    private static final Duration STALE_RESERVATION = Duration.ofMinutes(2);

    private final IdempotencyRecordRepository records;

    public IdempotencyService(IdempotencyRecordRepository records) {
        this.records = records;
    }

    /** Either a fresh reservation to complete later, or a saved response to replay. */
    public sealed interface Outcome permits Reserved, Replay {
    }

    public record Reserved(UUID reservationId) implements Outcome {
    }

    public record Replay(int statusCode, JsonNode response) implements Outcome {
    }

    public Outcome reserve(UUID userId, String key, String route) {
        if (key.length() > 128) {
            throw Errors.badRequest("The idempotency key is too long.");
        }
        try {
            IdempotencyRecord record = new IdempotencyRecord();
            record.setUserId(userId);
            record.setKey(key);
            record.setRoute(route);
            record.setStatusCode(0);
            return new Reserved(records.saveAndFlush(record).getId());
        } catch (DataIntegrityViolationException duplicate) {
            // The key was used before: replay, wait or retry below.
        }
        Optional<IdempotencyRecord> found = records.findByUserIdAndKey(userId, key);
        if (found.isPresent() && !found.get().getRoute().equals(route)) {
            throw Errors.conflict("The idempotency key was used for a different operation.");
        }
        if (found.isEmpty() || found.get().getStatusCode() == 0) {
            if (found.isPresent()
                    && Times.now().toEpochMilli() - found.get().getCreatedAt().toEpochMilli() > STALE_RESERVATION.toMillis()) {
                IdempotencyRecord stale = found.get();
                stale.setCreatedAt(Times.now());
                records.save(stale);
                return new Reserved(stale.getId());
            }
            throw Errors.conflict("The operation is already being processed.");
        }
        return new Replay(found.get().getStatusCode(), found.get().getResponse());
    }

    public void complete(UUID reservationId, int statusCode, JsonNode response) {
        records.findById(reservationId).ifPresent(record -> {
            record.setStatusCode(statusCode);
            record.setResponse(response);
            records.save(record);
        });
    }

    public void release(UUID reservationId) {
        try {
            records.deleteById(reservationId);
        } catch (RuntimeException ignored) {
            // Already gone: nothing to release.
        }
    }
}
