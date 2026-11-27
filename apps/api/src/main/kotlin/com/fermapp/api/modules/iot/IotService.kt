package com.fermapp.api.modules.iot

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fermapp.api.common.util.Text
import com.fermapp.api.common.util.Times
import com.fermapp.api.persistence.repository.IotDeviceRepository
import com.fermapp.api.realtime.RealtimeEvent
import com.fermapp.api.realtime.RealtimeHub
import com.fermapp.api.realtime.TelemetryIngestor
import com.fermapp.api.realtime.TelemetryIngestor.DeviceIdentity
import com.fermapp.api.security.TokenHashing
import jakarta.validation.Valid
import jakarta.validation.Validator
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.sql.Timestamp
import java.time.Instant
import java.util.OptionalInt
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/** The common entry point for REST, MQTT and WebSocket telemetry; resent readings are ignored (iot.service.ts). */
@Service
class IotService(
    private val devices: IotDeviceRepository,
    private val jdbc: JdbcTemplate,
    private val realtime: RealtimeHub,
    private val validator: Validator,
    private val mapper: ObjectMapper,
) : TelemetryIngestor {

    /** A sensor reading; [recordedAt] defaults to the moment it is received. */
    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("metric", "value", "unit", "recordedAt")
    class ReadingInput(metric: String?, @field:NotNull val value: Double?, unit: String?, val recordedAt: Instant?) {
        @field:NotNull @field:Size(min = 1, max = 60)
        val metric: String? = Text.trim(metric)

        @field:Size(max = 20)
        val unit: String? = Text.trim(unit)
    }

    class Telemetry(@field:NotNull @field:Size(min = 1, max = 500) val readings: List<@NotNull @Valid ReadingInput>?)

    @JvmRecord
    data class GeneratedKey(val key: String, val keyHash: String)

    override fun authenticate(deviceKey: String?): DeviceIdentity? {
        if (deviceKey.isNullOrEmpty()) {
            return null
        }
        return devices.findByKeyHash(TokenHashing.sha256(deviceKey)).map { DeviceIdentity(it.id, it.farmId) }.orElse(null)
    }

    /** Parses and validates a payload received over MQTT or WebSocket; null when it is invalid. */
    fun parse(payload: JsonNode?): Telemetry? {
        if (payload == null || !payload.isObject) {
            return null
        }
        return try {
            val telemetry = mapper.convertValue(payload, Telemetry::class.java)
            val readings = telemetry.readings
            val finite = readings != null && readings.all { reading -> reading?.value == null || reading.value.isFinite() }
            if (finite && validator.validate(telemetry).isEmpty()) telemetry else null
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    override fun ingest(device: DeviceIdentity, payload: JsonNode?): OptionalInt {
        val telemetry = parse(payload)
        return if (telemetry == null) OptionalInt.empty() else OptionalInt.of(ingest(device, telemetry))
    }

    fun ingest(device: DeviceIdentity, telemetry: Telemetry): Int {
        val now = Times.now()
        val rows = mutableListOf<Map<String, Any?>>()
        val batch = mutableListOf<Array<Any?>>()
        for (reading in telemetry.readings.orEmpty()) {
            val recordedAt = reading.recordedAt ?: now
            val row = linkedMapOf<String, Any?>("deviceId" to device.id(), "metric" to reading.metric, "value" to reading.value)
            if (reading.unit != null) {
                row["unit"] = reading.unit
            }
            row["recordedAt"] = recordedAt
            rows += row
            batch += arrayOf(device.id(), reading.metric, reading.value, reading.unit, Timestamp.from(recordedAt))
        }
        val results = jdbc.batchUpdate(
            "INSERT INTO \"IotReading\" (\"deviceId\", \"metric\", \"value\", \"unit\", \"recordedAt\") VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (\"deviceId\", \"metric\", \"recordedAt\") DO NOTHING",
            batch,
        )
        val count = results.sumOf { Math.max(0, it) }
        devices.markSeen(device.id(), now)
        realtime.emitToFarm(device.farmId(), RealtimeEvent.IOT_READING, linkedMapOf("deviceId" to device.id(), "readings" to rows))
        return count
    }

    companion object {
        @JvmStatic
        fun generateDeviceKey(): GeneratedKey {
            val key = "fad_" + TokenHashing.randomBase64Url(32)
            return GeneratedKey(key, TokenHashing.sha256(key))
        }
    }
}
