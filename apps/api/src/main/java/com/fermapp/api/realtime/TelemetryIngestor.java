package com.fermapp.api.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.OptionalInt;

/** The IoT entry point the WebSocket namespace /iot uses; implemented by the IoT service. */
public interface TelemetryIngestor {

    /** Authenticates a device key; returns the device id, or null for an unknown key. */
    DeviceIdentity authenticate(String deviceKey);

    /**
     * Validates and stores a telemetry payload.
     *
     * @return the number of accepted readings, or empty when the payload is invalid
     */
    OptionalInt ingest(DeviceIdentity device, JsonNode payload);

    record DeviceIdentity(java.util.UUID id, java.util.UUID farmId) {
    }
}
