package com.fermapp.api.common.json

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fermapp.api.common.util.Times
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Serialisation rules that keep the JSON identical to what the Node.js server produced.
 *
 * - Instants print like `Date.toJSON()`: `2026-11-26T09:00:00.000Z`.
 * - Doubles without a fraction print as integers (`6`, not `6.0`), as JavaScript does.
 * - Dates in requests are read like `z.coerce.date()`: ISO dates, ISO date-times,
 *   date-times without an offset (local time, as in JavaScript) and epoch milliseconds.
 */
object JsonFormats {

    @JvmStatic
    fun module(): SimpleModule = SimpleModule("fermapp-js-compat").apply {
        addSerializer(Instant::class.java, InstantSerializer())
        addDeserializer(Instant::class.java, LenientInstantDeserializer())
        addSerializer(Double::class.javaObjectType, JsNumberSerializer())
        addSerializer(Double::class.javaPrimitiveType!!, JsNumberSerializer())
        addSerializer(DoubleArray::class.java, JsNumberArraySerializer())
    }

    /** Writes a double[] with the JavaScript number format. */
    class JsNumberArraySerializer : JsonSerializer<DoubleArray>() {
        private val element = JsNumberSerializer()

        override fun serialize(value: DoubleArray, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeStartArray()
            for (item in value) {
                element.serialize(item, gen, serializers)
            }
            gen.writeEndArray()
        }
    }

    /** Writes an Instant with millisecond precision and a Z, like JavaScript. */
    class InstantSerializer : JsonSerializer<Instant>() {
        override fun serialize(value: Instant, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeString(Times.iso(value))
        }
    }

    /** Writes 6.0 as 6 and keeps the shortest round-trip form otherwise. */
    class JsNumberSerializer : JsonSerializer<Double>() {
        override fun serialize(value: Double, gen: JsonGenerator, serializers: SerializerProvider) {
            when {
                // JSON.stringify turns NaN and Infinity into null.
                value.isNaN() || value.isInfinite() -> gen.writeNull()
                value == Math.rint(value) && Math.abs(value) < 1e15 -> gen.writeNumber(value.toLong())
                else -> gen.writeNumber(BigDecimal.valueOf(value))
            }
        }
    }

    /** `z.coerce.date()`: accepts every form `new Date(value)` accepts in practice. */
    class LenientInstantDeserializer : JsonDeserializer<Instant>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): Instant? {
            val token = parser.currentToken()
            if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                return Instant.ofEpochMilli(parser.valueAsLong)
            }
            if (token != JsonToken.VALUE_STRING) {
                return context.handleUnexpectedToken(Instant::class.java, parser) as Instant?
            }
            // String.trim() semantics: control characters and spaces only.
            val text = parser.text.trim { it <= ' ' }
            return parse(text) ?: context.handleWeirdStringValue(Instant::class.java, text, "Invalid date") as Instant?
        }

        companion object {
            private val EPOCH_MILLIS = Regex("-?\\d+")

            @JvmStatic
            fun parse(text: String?): Instant? {
                if (text.isNullOrEmpty()) {
                    return null
                }
                return attempt { OffsetDateTime.parse(text).toInstant() }
                    ?: attempt { Instant.parse(text) }
                    // new Date("2026-04-05") is midnight UTC.
                    ?: attempt { LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant() }
                    // new Date("2026-04-05T10:00") is local time.
                    ?: attempt { LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant() }
                    ?: if (EPOCH_MILLIS.matches(text)) Instant.ofEpochMilli(text.toLong()) else null
            }

            private inline fun attempt(parse: () -> Instant): Instant? = try {
                parse()
            } catch (ignored: DateTimeParseException) {
                null
            }
        }
    }
}
