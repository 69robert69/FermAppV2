package com.fermapp.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON snapshots under src/test/resources/snapshots. A missing snapshot is recorded; run with
 * {@code -Dsnapshot.update=true} to record again after an intended change. On a mismatch the
 * actual output is written to target/NAME-actual.json, to compare with any diff tool.
 */
public final class Snapshots {

    private Snapshots() {
    }

    public static void assertMatches(ObjectMapper mapper, String name, Object value) throws IOException {
        // The default indenter uses the platform line separator (CRLF on Windows); snapshots always use LF.
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n"));
        String json = mapper.writer(printer).writeValueAsString(value) + "\n";
        Path snapshot = Path.of("src/test/resources/snapshots/" + name + ".json");
        if (Boolean.getBoolean("snapshot.update") || !Files.exists(snapshot)) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, json, StandardCharsets.UTF_8);
        }
        String expected = Files.readString(snapshot, StandardCharsets.UTF_8);
        if (!json.equals(expected)) {
            Files.writeString(Path.of("target/" + name + "-actual.json"), json, StandardCharsets.UTF_8);
        }
        assertThat(json).isEqualTo(expected);
    }

    /** Objects as sorted maps, so that JSON built from Map.of (random order per JVM) compares reliably. */
    public static Object canonical(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            node.forEach(item -> items.add(canonical(item)));
            return items;
        }
        return node;
    }
}
