package com.fermapp.api.modules.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.persistence.repository.IotDeviceRepository;
import com.fermapp.api.realtime.RealtimeEvent;
import com.fermapp.api.realtime.RealtimeHub;
import com.fermapp.api.realtime.TelemetryIngestor;
import com.fermapp.api.security.TokenHashing;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The common entry point for REST, MQTT and WebSocket telemetry; resent readings are ignored (iot.service.ts). */
@Service
public class IotService implements TelemetryIngestor {

    /** A sensor reading; {@code recordedAt} defaults to the moment it is received. */
    public record ReadingInput(@NotNull @Size(min = 1, max = 60) String metric, @NotNull Double value,
                               @Size(max = 20) String unit, Instant recordedAt) {
        public ReadingInput {
            metric = Text.trim(metric);
            unit = Text.trim(unit);
        }
    }

    public record Telemetry(@NotNull @Size(min = 1, max = 500) List<@NotNull @Valid ReadingInput> readings) {
    }

    public record GeneratedKey(String key, String keyHash) {
    }

    private final IotDeviceRepository devices;
    private final JdbcTemplate jdbc;
    private final RealtimeHub realtime;
    private final Validator validator;
    private final ObjectMapper mapper;

    public IotService(IotDeviceRepository devices, JdbcTemplate jdbc, RealtimeHub realtime, Validator validator, ObjectMapper mapper) {
        this.devices = devices;
        this.jdbc = jdbc;
        this.realtime = realtime;
        this.validator = validator;
        this.mapper = mapper;
    }

    public static GeneratedKey generateDeviceKey() {
        String key = "fad_" + TokenHashing.randomBase64Url(32);
        return new GeneratedKey(key, TokenHashing.sha256(key));
    }

    @Override
    public DeviceIdentity authenticate(String deviceKey) {
        if (deviceKey == null || deviceKey.isEmpty()) {
            return null;
        }
        return devices.findByKeyHash(TokenHashing.sha256(deviceKey))
                .map(device -> new DeviceIdentity(device.getId(), device.getFarmId()))
                .orElse(null);
    }

    /** Parses and validates a payload received over MQTT or WebSocket; null when it is invalid. */
    public Telemetry parse(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return null;
        }
        try {
            Telemetry telemetry = mapper.convertValue(payload, Telemetry.class);
            boolean finite = telemetry.readings() != null && telemetry.readings().stream()
                    .allMatch(reading -> reading == null || reading.value() == null || Double.isFinite(reading.value()));
            return finite && validator.validate(telemetry).isEmpty() ? telemetry : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    @Override
    public OptionalInt ingest(DeviceIdentity device, JsonNode payload) {
        Telemetry telemetry = parse(payload);
        return telemetry == null ? OptionalInt.empty() : OptionalInt.of(ingest(device, telemetry));
    }

    public int ingest(DeviceIdentity device, Telemetry telemetry) {
        Instant now = Times.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Object[]> batch = new ArrayList<>();
        for (ReadingInput reading : telemetry.readings()) {
            Instant recordedAt = reading.recordedAt() != null ? reading.recordedAt() : now;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceId", device.id());
            row.put("metric", reading.metric());
            row.put("value", reading.value());
            if (reading.unit() != null) {
                row.put("unit", reading.unit());
            }
            row.put("recordedAt", recordedAt);
            rows.add(row);
            batch.add(new Object[] {device.id(), reading.metric(), reading.value(), reading.unit(), Timestamp.from(recordedAt)});
        }
        int[] results = jdbc.batchUpdate(
                "INSERT INTO \"IotReading\" (\"deviceId\", \"metric\", \"value\", \"unit\", \"recordedAt\") VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (\"deviceId\", \"metric\", \"recordedAt\") DO NOTHING",
                batch);
        int count = 0;
        for (int result : results) {
            count += Math.max(0, result);
        }
        devices.markSeen(device.id(), now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceId", device.id());
        payload.put("readings", rows);
        realtime.emitToFarm(device.farmId(), RealtimeEvent.IOT_READING, payload);
        return count;
    }
}
