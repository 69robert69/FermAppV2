package com.fermapp.api.common.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fermapp.api.common.util.Times;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Serialisation rules that keep the JSON identical to what the Node.js server produced.
 *
 * <ul>
 *   <li>Instants print like {@code Date.toJSON()}: {@code 2026-11-26T09:00:00.000Z}.</li>
 *   <li>Doubles without a fraction print as integers ({@code 6}, not {@code 6.0}), as JavaScript does.</li>
 *   <li>Dates in requests are read like {@code z.coerce.date()}: ISO dates, ISO date-times,
 *       date-times without an offset (local time, as in JavaScript) and epoch milliseconds.</li>
 * </ul>
 */
public final class JsonFormats {

    private JsonFormats() {
    }

    public static SimpleModule module() {
        SimpleModule module = new SimpleModule("fermapp-js-compat");
        module.addSerializer(Instant.class, new InstantSerializer());
        module.addDeserializer(Instant.class, new LenientInstantDeserializer());
        module.addSerializer(Double.class, new JsNumberSerializer());
        module.addSerializer(double.class, new JsNumberSerializer());
        module.addSerializer(double[].class, new JsNumberArraySerializer());
        return module;
    }

    /** Writes a double[] with the JavaScript number format. */
    public static final class JsNumberArraySerializer extends JsonSerializer<double[]> {
        private final JsNumberSerializer element = new JsNumberSerializer();

        @Override
        public void serialize(double[] value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartArray();
            for (double item : value) {
                element.serialize(item, gen, serializers);
            }
            gen.writeEndArray();
        }
    }

    /** Writes an Instant with millisecond precision and a Z, like JavaScript. */
    public static final class InstantSerializer extends JsonSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(Times.iso(value));
        }
    }

    /** Writes 6.0 as 6 and keeps the shortest round-trip form otherwise. */
    public static final class JsNumberSerializer extends JsonSerializer<Double> {
        @Override
        public void serialize(Double value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            double number = value;
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                // JSON.stringify turns NaN and Infinity into null.
                gen.writeNull();
            } else if (number == Math.rint(number) && Math.abs(number) < 1e15) {
                gen.writeNumber((long) number);
            } else {
                gen.writeNumber(BigDecimal.valueOf(number));
            }
        }
    }

    /** {@code z.coerce.date()}: accepts every form {@code new Date(value)} accepts in practice. */
    public static final class LenientInstantDeserializer extends JsonDeserializer<Instant> {
        @Override
        public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT || parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
                return Instant.ofEpochMilli(parser.getValueAsLong());
            }
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (Instant) context.handleUnexpectedToken(Instant.class, parser);
            }
            String text = parser.getText().trim();
            Instant parsed = parse(text);
            if (parsed == null) {
                return (Instant) context.handleWeirdStringValue(Instant.class, text, "Invalid date");
            }
            return parsed;
        }

        public static Instant parse(String text) {
            if (text == null || text.isEmpty()) {
                return null;
            }
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException ignored) {
                // Try the next format.
            }
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ignored) {
                // Try the next format.
            }
            try {
                // new Date("2026-04-05") is midnight UTC.
                return LocalDate.parse(text).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ignored) {
                // Try the next format.
            }
            try {
                // new Date("2026-04-05T10:00") is local time.
                return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException ignored) {
                // Try the next format.
            }
            if (text.matches("-?\\d+")) {
                return Instant.ofEpochMilli(Long.parseLong(text));
            }
            return null;
        }
    }
}
