package com.fermapp.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.common.json.JsonFormats;
import com.fermapp.api.domain.geo.Geo.Polygon;
import com.fermapp.api.domain.geo.Geo.Position;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The JSON stays identical to what the Node.js server produced. */
class JsonFormatsTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(JsonFormats.module());

    @Test
    void instantsHaveMillisecondsAndZ() throws Exception {
        assertThat(mapper.writeValueAsString(Instant.parse("2026-11-26T09:00:00Z"))).isEqualTo("\"2026-11-26T09:00:00.000Z\"");
    }

    @Test
    void integralDoublesPrintAsIntegers() throws Exception {
        assertThat(mapper.writeValueAsString(Map.of("a", 6.0))).isEqualTo("{\"a\":6}");
        assertThat(mapper.writeValueAsString(Map.of("a", 2.5))).isEqualTo("{\"a\":2.5}");
        assertThat(mapper.writeValueAsString(new double[] {350, 7.5})).isEqualTo("[350,7.5]");
    }

    @Test
    void datesAreReadLikeZodCoerce() throws Exception {
        assertThat(mapper.readValue("\"2026-04-05\"", Instant.class)).isEqualTo(Instant.parse("2026-04-05T00:00:00Z"));
        assertThat(mapper.readValue("\"2026-04-05T10:00:00+03:00\"", Instant.class)).isEqualTo(Instant.parse("2026-04-05T07:00:00Z"));
        assertThat(mapper.readValue("1700000000000", Instant.class)).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
    }

    @Test
    void polygonsAreGeoJson() throws Exception {
        Polygon polygon = Polygon.of(List.of(List.of(new Position(26, 44.7), new Position(26.01, 44.7))));
        String json = mapper.writeValueAsString(polygon);
        assertThat(json).isEqualTo("{\"type\":\"Polygon\",\"coordinates\":[[[26,44.7],[26.01,44.7]]]}");
        assertThat(mapper.readValue(json, Polygon.class)).isEqualTo(polygon);
    }
}
