package com.fermapp.api.modules.parcels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fermapp.api.common.error.ApiException;
import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.domain.geo.Geo;
import com.fermapp.api.domain.geo.Geo.Polygon;
import com.fermapp.api.domain.geo.Geo.Position;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parcel import and export (geo-io.ts): GeoJSON, KML, KMZ, Shapefile and APIA LPIS files.
 * Archives are checked before extraction; projected shapefiles (Stereo 70 included) are
 * reprojected to WGS84 from their .prj, as shpjs did.
 */
@Component
public class GeoIo {

    public static final List<String> IMPORT_FORMATS = List.of("geojson", "kml", "kmz", "shp", "lpis");

    private static final int MAX_ARCHIVE_ENTRIES = 50;
    private static final long MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024;
    private static final int MAX_FEATURES = 2000;
    /** The .prj that shp-write wrote next to every exported shapefile. */
    private static final String WGS84_PRJ = "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137,298.257223563]],"
            + "PRIMEM[\"Greenwich\",0],UNIT[\"Degree\",0.017453292519943295]]";

    /** Common field names from GIS exports and APIA/LPIS files. */
    private static final List<String> NAME_ALIASES = List.of("name", "nume", "denumire", "parcela", "nume_parcela", "nr_parcela", "parcel", "title");
    private static final List<String> CODE_ALIASES = List.of("code", "cod", "cod_parcela", "bloc_fizic", "blocfizic", "bf", "nr_bloc", "id_parcela");
    private static final List<String> LOCALITY_ALIASES = List.of("locality", "localitate", "uat", "comuna", "siruta_nume");
    private static final List<String> COUNTY_ALIASES = List.of("county", "judet", "județ", "jud");
    private static final List<String> NOTES_ALIASES = List.of("notes", "observatii", "observații", "description", "descriere");

    public record ImportedParcel(String name, String code, String locality, String county, String notes, Polygon geometry) {
    }

    public record ImportProblem(int index, String name, String reason) {
    }

    public record ImportResult(List<ImportedParcel> parcels, List<ImportProblem> problems) {
    }

    public record ExportParcel(String name, String code, double areaHa, double perimeterM, String locality, String county,
                               String notes, Polygon geometry) {
    }

    public record ExportFile(byte[] content, String contentType, String extension) {
    }

    private final ObjectMapper mapper;

    static {
        // Longitude first, as in GeoJSON.
        System.setProperty("org.geotools.referencing.forceXY", "true");
    }

    public GeoIo(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ features to parcels

    private static String asText(JsonNode value) {
        if (value.isNumber()) {
            return Js.num(value.asDouble());
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isObject()) {
            return "[object Object]";
        }
        return value.toString();
    }

    private static String pick(JsonNode properties, List<String> aliases) {
        Map<String, JsonNode> lower = new HashMap<>();
        properties.fields().forEachRemaining(entry -> lower.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()));
        for (String alias : aliases) {
            JsonNode value = lower.get(alias);
            if (value != null && !value.isNull() && !value.isMissingNode()) {
                String text = asText(value).trim();
                if (!text.isEmpty()) {
                    return text.length() > 200 ? text.substring(0, 200) : text;
                }
            }
        }
        return null;
    }

    /** Drops altitude and checks the structure of each point. */
    private static List<Position> toPositions(JsonNode ring) {
        if (ring == null || !ring.isArray()) {
            throw new IllegalArgumentException("invalid ring");
        }
        List<Position> positions = new ArrayList<>();
        for (JsonNode point : ring) {
            if (!point.isArray() || !point.path(0).isNumber() || !point.path(1).isNumber()) {
                throw new IllegalArgumentException("invalid coordinates");
            }
            positions.add(new Position(point.get(0).asDouble(), point.get(1).asDouble()));
        }
        return positions;
    }

    private static List<List<Position>> rings(JsonNode polygon) {
        if (polygon == null || !polygon.isArray()) {
            throw new IllegalArgumentException("invalid ring");
        }
        List<List<Position>> rings = new ArrayList<>();
        for (JsonNode ring : polygon) {
            rings.add(toPositions(ring));
        }
        return rings;
    }

    private static List<Polygon> polygonsOf(JsonNode geometry) {
        if (geometry == null || geometry.isNull() || geometry.isMissingNode()) {
            return List.of();
        }
        String type = geometry.path("type").asText();
        JsonNode coordinates = geometry.get("coordinates");
        switch (type) {
            case "Polygon":
                return List.of(Polygon.of(rings(coordinates)));
            case "MultiPolygon": {
                if (coordinates == null || !coordinates.isArray()) {
                    throw new IllegalArgumentException("invalid ring");
                }
                List<Polygon> polygons = new ArrayList<>();
                for (JsonNode polygon : coordinates) {
                    polygons.add(Polygon.of(rings(polygon)));
                }
                return polygons;
            }
            case "GeometryCollection": {
                List<Polygon> polygons = new ArrayList<>();
                for (JsonNode child : geometry.path("geometries")) {
                    polygons.addAll(polygonsOf(child));
                }
                return polygons;
            }
            default:
                return List.of();
        }
    }

    public ImportResult featuresToParcels(List<JsonNode> features) {
        if (features.size() > MAX_FEATURES) {
            throw Errors.badRequest("The file exceeds the limit of " + MAX_FEATURES + " parcels.");
        }
        List<ImportedParcel> parcels = new ArrayList<>();
        List<ImportProblem> problems = new ArrayList<>();
        for (int index = 0; index < features.size(); index++) {
            JsonNode feature = features.get(index);
            JsonNode properties = feature.path("properties").isObject() ? feature.get("properties") : mapper.createObjectNode();
            String picked = pick(properties, NAME_ALIASES);
            String baseName = picked != null ? picked : "Imported parcel " + (index + 1);
            List<Polygon> polygons;
            try {
                polygons = polygonsOf(feature.get("geometry"));
            } catch (IllegalArgumentException error) {
                problems.add(new ImportProblem(index, baseName, "Invalid geometry: " + error.getMessage() + "."));
                continue;
            }
            if (polygons.isEmpty()) {
                problems.add(new ImportProblem(index, baseName, "The element does not contain a polygon."));
                continue;
            }
            for (int part = 0; part < polygons.size(); part++) {
                String suffix = polygons.size() > 1 ? " (" + (part + 1) + ")" : "";
                String reason = Geo.validatePolygon(polygons.get(part));
                if (reason != null) {
                    problems.add(new ImportProblem(index, baseName + suffix, reason));
                    continue;
                }
                String code = pick(properties, CODE_ALIASES);
                parcels.add(new ImportedParcel(baseName + suffix,
                        code != null ? code + (polygons.size() > 1 ? "-" + (part + 1) : "") : null,
                        pick(properties, LOCALITY_ALIASES), pick(properties, COUNTY_ALIASES), pick(properties, NOTES_ALIASES),
                        polygons.get(part)));
            }
        }
        return new ImportResult(parcels, problems);
    }

    private List<JsonNode> asFeatures(JsonNode data) {
        List<JsonNode> collections = new ArrayList<>();
        if (data.isArray()) {
            data.forEach(collections::add);
        } else {
            collections.add(data);
        }
        List<JsonNode> features = new ArrayList<>();
        for (JsonNode item : collections) {
            String type = item.path("type").asText("");
            if ("FeatureCollection".equals(type) && item.path("features").isArray()) {
                item.get("features").forEach(features::add);
            } else if ("Feature".equals(type)) {
                features.add(item);
            } else if ("Polygon".equals(type) || "MultiPolygon".equals(type)) {
                ObjectNode feature = mapper.createObjectNode();
                feature.put("type", "Feature");
                feature.set("geometry", item);
                feature.set("properties", mapper.createObjectNode());
                features.add(feature);
            } else {
                throw Errors.badRequest("The file does not contain valid GeoJSON data.");
            }
        }
        return features;
    }

    // ------------------------------------------------------------------ archives

    /** Checks the archive before extracting it, as protection against oversized archives. */
    private static ZipFile openArchive(byte[] buffer) {
        ZipFile zip;
        try {
            zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(buffer)).get();
        } catch (IOException error) {
            throw Errors.badRequest("The archive cannot be read.");
        }
        List<ZipArchiveEntry> entries = Collections.list(zip.getEntries());
        if (entries.size() > MAX_ARCHIVE_ENTRIES) {
            throw Errors.badRequest("The archive contains too many files.");
        }
        long total = entries.stream().mapToLong(entry -> Math.max(0, entry.getSize())).sum();
        if (total > MAX_UNCOMPRESSED_BYTES) {
            throw Errors.badRequest("The archive is too large after extraction.");
        }
        return zip;
    }

    private static byte[] read(ZipFile zip, ZipArchiveEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readNBytes((int) Math.min(MAX_UNCOMPRESSED_BYTES, Integer.MAX_VALUE - 8));
        }
    }

    // ------------------------------------------------------------------ KML

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && localName.equals(localNameOf(element))) {
                result.add(element);
            }
        }
        return result;
    }

    private static String localNameOf(Node node) {
        return node.getLocalName() != null ? node.getLocalName() : node.getNodeName().replaceFirst("^.*:", "");
    }

    private static Element child(Element parent, String localName) {
        List<Element> found = children(parent, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    private ArrayNode kmlRing(Element linearRing) {
        ArrayNode ring = mapper.createArrayNode();
        Element coordinates = linearRing == null ? null : child(linearRing, "coordinates");
        if (coordinates == null) {
            return ring;
        }
        for (String tuple : coordinates.getTextContent().trim().split("\\s+")) {
            if (tuple.isBlank()) {
                continue;
            }
            String[] parts = tuple.split(",");
            ArrayNode point = mapper.createArrayNode();
            for (String part : parts) {
                try {
                    point.add(Double.parseDouble(part.trim()));
                } catch (NumberFormatException error) {
                    point.add(part.trim());
                }
            }
            ring.add(point);
        }
        return ring;
    }

    private JsonNode kmlGeometry(Element element) {
        String name = localNameOf(element);
        if ("Polygon".equals(name)) {
            ObjectNode polygon = mapper.createObjectNode();
            polygon.put("type", "Polygon");
            ArrayNode coordinates = polygon.putArray("coordinates");
            Element outer = child(element, "outerBoundaryIs");
            if (outer != null) {
                coordinates.add(kmlRing(child(outer, "LinearRing")));
            }
            for (Element inner : children(element, "innerBoundaryIs")) {
                coordinates.add(kmlRing(child(inner, "LinearRing")));
            }
            return polygon;
        }
        if ("MultiGeometry".equals(name)) {
            ObjectNode collection = mapper.createObjectNode();
            collection.put("type", "GeometryCollection");
            ArrayNode geometries = collection.putArray("geometries");
            NodeList nodes = element.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i) instanceof Element childElement) {
                    JsonNode geometry = kmlGeometry(childElement);
                    if (geometry != null) {
                        geometries.add(geometry);
                    }
                }
            }
            return collection;
        }
        if ("Point".equals(name) || "LineString".equals(name) || "LinearRing".equals(name) || "Track".equals(name)) {
            ObjectNode other = mapper.createObjectNode();
            other.put("type", "LineString".equals(name) || "LinearRing".equals(name) ? "LineString" : "Point");
            other.set("coordinates", mapper.createArrayNode());
            return other;
        }
        return null;
    }

    /** KML placemarks to GeoJSON features, like @tmcw/togeojson: name, description and extended data become properties. */
    private List<JsonNode> parseKml(byte[] content) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            document = builder.parse(new ByteArrayInputStream(content));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid KML", error);
        }
        List<JsonNode> features = new ArrayList<>();
        NodeList placemarks = document.getElementsByTagNameNS("*", "Placemark");
        for (int i = 0; i < placemarks.getLength(); i++) {
            Element placemark = (Element) placemarks.item(i);
            ObjectNode properties = mapper.createObjectNode();
            Element name = child(placemark, "name");
            if (name != null) {
                properties.put("name", name.getTextContent());
            }
            Element description = child(placemark, "description");
            if (description != null) {
                properties.put("description", description.getTextContent());
            }
            Element extended = child(placemark, "ExtendedData");
            if (extended != null) {
                for (Element data : children(extended, "Data")) {
                    Element value = child(data, "value");
                    properties.put(data.getAttribute("name"), value == null ? "" : value.getTextContent());
                }
                for (Element schemaData : children(extended, "SchemaData")) {
                    for (Element simple : children(schemaData, "SimpleData")) {
                        properties.put(simple.getAttribute("name"), simple.getTextContent());
                    }
                }
            }
            JsonNode geometry = null;
            NodeList nodes = placemark.getChildNodes();
            for (int j = 0; j < nodes.getLength() && geometry == null; j++) {
                if (nodes.item(j) instanceof Element element) {
                    geometry = kmlGeometry(element);
                }
            }
            ObjectNode feature = mapper.createObjectNode();
            feature.put("type", "Feature");
            feature.set("geometry", geometry);
            feature.set("properties", properties);
            features.add(feature);
        }
        return features;
    }

    // ------------------------------------------------------------------ shapefiles

    private JsonNode ringJson(Coordinate[] coordinates) {
        ArrayNode ring = mapper.createArrayNode();
        for (Coordinate coordinate : coordinates) {
            ArrayNode point = mapper.createArrayNode();
            point.add(coordinate.x);
            point.add(coordinate.y);
            ring.add(point);
        }
        return ring;
    }

    private JsonNode polygonCoordinates(org.locationtech.jts.geom.Polygon polygon) {
        ArrayNode rings = mapper.createArrayNode();
        rings.add(ringJson(polygon.getExteriorRing().getCoordinates()));
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            rings.add(ringJson(polygon.getInteriorRingN(i).getCoordinates()));
        }
        return rings;
    }

    private JsonNode geometryJson(Geometry geometry) {
        if (geometry == null) {
            return null;
        }
        ObjectNode node = mapper.createObjectNode();
        if (geometry instanceof org.locationtech.jts.geom.Polygon polygon) {
            node.put("type", "Polygon");
            node.set("coordinates", polygonCoordinates(polygon));
        } else if (geometry instanceof MultiPolygon multi) {
            node.put("type", "MultiPolygon");
            ArrayNode polygons = node.putArray("coordinates");
            for (int i = 0; i < multi.getNumGeometries(); i++) {
                polygons.add(polygonCoordinates((org.locationtech.jts.geom.Polygon) multi.getGeometryN(i)));
            }
        } else if (geometry instanceof GeometryCollection collection) {
            node.put("type", "GeometryCollection");
            ArrayNode geometries = node.putArray("geometries");
            for (int i = 0; i < collection.getNumGeometries(); i++) {
                geometries.add(geometryJson(collection.getGeometryN(i)));
            }
        } else {
            node.put("type", geometry.getGeometryType());
            node.set("coordinates", mapper.createArrayNode());
        }
        return node;
    }

    private void putProperty(ObjectNode properties, String name, Object value) {
        if (value == null) {
            properties.putNull(name);
        } else if (value instanceof Number number) {
            properties.put(name, number.doubleValue());
        } else if (value instanceof Boolean bool) {
            properties.put(name, bool);
        } else {
            properties.put(name, value.toString());
        }
    }

    /** Reads every layer of a zipped shapefile, reprojecting to WGS84 when the .prj says otherwise. */
    private List<JsonNode> parseShapefileZip(ZipFile zip) throws Exception {
        Path directory = Files.createTempDirectory("fermapp-shp");
        try {
            Map<String, Path> layers = new LinkedHashMap<>();
            for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                String fileName = Path.of(entry.getName()).getFileName().toString();
                if (fileName.startsWith(".") || entry.getName().contains("__MACOSX")) {
                    continue;
                }
                Path target = directory.resolve(fileName).normalize();
                if (!target.startsWith(directory)) {
                    continue;
                }
                Files.write(target, read(zip, entry));
                if (fileName.toLowerCase(Locale.ROOT).endsWith(".shp")) {
                    layers.put(fileName.substring(0, fileName.length() - 4), target);
                }
            }
            if (layers.isEmpty()) {
                throw new IllegalArgumentException("No shapefile in the archive");
            }
            List<JsonNode> features = new ArrayList<>();
            for (Map.Entry<String, Path> layer : layers.entrySet()) {
                features.addAll(readLayer(directory, layer.getKey(), layer.getValue()));
            }
            return features;
        } finally {
            try (Stream<Path> files = Files.walk(directory)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static Path sibling(Path directory, String base, String extension) {
        for (String candidate : new String[] {base + "." + extension, base + "." + extension.toUpperCase(Locale.ROOT)}) {
            Path path = directory.resolve(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }

    private List<JsonNode> readLayer(Path directory, String base, Path shp) throws Exception {
        ShapefileDataStore store = new ShapefileDataStore(shp.toUri().toURL());
        try {
            Path cpg = sibling(directory, base, "cpg");
            Charset charset = StandardCharsets.UTF_8;
            if (cpg != null) {
                try {
                    charset = Charset.forName(Files.readString(cpg, StandardCharsets.US_ASCII).trim());
                } catch (RuntimeException ignored) {
                    charset = StandardCharsets.UTF_8;
                }
            }
            store.setCharset(charset);
            MathTransform transform = null;
            Path prj = sibling(directory, base, "prj");
            if (prj != null) {
                CoordinateReferenceSystem source = CRS.parseWKT(Files.readString(prj, StandardCharsets.ISO_8859_1));
                if (!CRS.equalsIgnoreMetadata(source, DefaultGeographicCRS.WGS84)) {
                    transform = CRS.findMathTransform(source, DefaultGeographicCRS.WGS84, true);
                }
            }
            SimpleFeatureSource source = store.getFeatureSource();
            List<JsonNode> features = new ArrayList<>();
            try (SimpleFeatureIterator iterator = source.getFeatures().features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    Geometry geometry = (Geometry) feature.getDefaultGeometry();
                    if (geometry != null && transform != null) {
                        geometry = JTS.transform(geometry, transform);
                    }
                    ObjectNode properties = mapper.createObjectNode();
                    for (AttributeDescriptor descriptor : feature.getFeatureType().getAttributeDescriptors()) {
                        if (descriptor.getType() instanceof org.geotools.api.feature.type.GeometryType) {
                            continue;
                        }
                        putProperty(properties, descriptor.getLocalName(), feature.getAttribute(descriptor.getLocalName()));
                    }
                    ObjectNode node = mapper.createObjectNode();
                    node.put("type", "Feature");
                    node.set("geometry", geometryJson(geometry));
                    node.set("properties", properties);
                    features.add(node);
                }
            }
            return features;
        } finally {
            store.dispose();
        }
    }

    // ------------------------------------------------------------------ import entry point

    public ImportResult parseGeoFile(String format, byte[] buffer) {
        List<JsonNode> features;
        try {
            switch (format) {
                case "geojson" -> features = asFeatures(mapper.readTree(buffer));
                case "kml" -> features = parseKml(buffer);
                case "kmz" -> {
                    try (ZipFile zip = openArchive(buffer)) {
                        ZipArchiveEntry entry = Collections.list(zip.getEntries()).stream()
                                .filter(file -> file.getName().toLowerCase(Locale.ROOT).endsWith(".kml"))
                                .findFirst()
                                .orElseThrow(() -> Errors.badRequest("The KMZ archive does not contain a KML file."));
                        features = parseKml(read(zip, entry));
                    }
                }
                case "shp", "lpis" -> {
                    // LPIS files can come as GeoJSON or as a Shapefile archive (including Stereo 70, via .prj).
                    if (buffer.length > 0 && buffer[0] == 0x7b) {
                        features = asFeatures(mapper.readTree(buffer));
                    } else {
                        try (ZipFile zip = openArchive(buffer)) {
                            features = parseShapefileZip(zip);
                        }
                    }
                }
                default -> throw Errors.badRequest("Unsupported format.");
            }
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            throw Errors.badRequest("The " + format.toUpperCase(Locale.ROOT) + " file cannot be read.");
        }
        return featuresToParcels(features);
    }

    // ------------------------------------------------------------------ export

    private JsonNode geometryNode(Polygon polygon) {
        return mapper.valueToTree(polygon);
    }

    public JsonNode toFeatureCollection(List<ExportParcel> parcels) {
        ObjectNode collection = mapper.createObjectNode();
        collection.put("type", "FeatureCollection");
        ArrayNode features = collection.putArray("features");
        for (ExportParcel parcel : parcels) {
            ObjectNode feature = features.addObject();
            feature.put("type", "Feature");
            feature.set("geometry", geometryNode(parcel.geometry()));
            ObjectNode properties = feature.putObject("properties");
            properties.put("name", parcel.name());
            properties.put("code", parcel.code());
            properties.set("area_ha", mapper.valueToTree(parcel.areaHa()));
            properties.set("perimeter", mapper.valueToTree(parcel.perimeterM()));
            properties.put("locality", parcel.locality() == null ? "" : parcel.locality());
            properties.put("county", parcel.county() == null ? "" : parcel.county());
            properties.put("notes", parcel.notes() == null ? "" : parcel.notes());
        }
        return collection;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String kmlRingText(List<Position> points) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                text.append(' ');
            }
            text.append(Js.num(points.get(i).lng())).append(',').append(Js.num(points.get(i).lat())).append(",0");
        }
        return text.toString();
    }

    public String toKml(List<ExportParcel> parcels) {
        StringBuilder placemarks = new StringBuilder();
        for (ExportParcel parcel : parcels) {
            List<List<Position>> rings = parcel.geometry().coordinates();
            StringBuilder inner = new StringBuilder();
            for (int i = 1; i < rings.size(); i++) {
                inner.append("<innerBoundaryIs><LinearRing><coordinates>").append(kmlRingText(rings.get(i)))
                        .append("</coordinates></LinearRing></innerBoundaryIs>");
            }
            placemarks.append("<Placemark><name>").append(xml(parcel.name())).append("</name><description>")
                    .append(xml(parcel.code() + ", " + Js.num(parcel.areaHa()) + " ha")).append("</description><ExtendedData>")
                    .append("<Data name=\"code\"><value>").append(xml(parcel.code())).append("</value></Data>")
                    .append("<Data name=\"locality\"><value>").append(xml(parcel.locality() == null ? "" : parcel.locality())).append("</value></Data>")
                    .append("<Data name=\"county\"><value>").append(xml(parcel.county() == null ? "" : parcel.county())).append("</value></Data>")
                    .append("</ExtendedData><Polygon><outerBoundaryIs><LinearRing><coordinates>")
                    .append(kmlRingText(rings.get(0))).append("</coordinates></LinearRing></outerBoundaryIs>").append(inner)
                    .append("</Polygon></Placemark>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>FermApp</name>"
                + placemarks + "</Document></kml>";
    }

    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setMethod(ZipOutputStream.DEFLATED);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private record ShpField(String name, Class<?> type) {
    }

    /** Writes a polygon shapefile named "parcels" (.shp, .shx, .dbf, .prj) and zips it, like shp-write. */
    private byte[] toShapefile(List<ShpField> fields, List<Polygon> geometries, List<List<Object>> values) throws Exception {
        Path directory = Files.createTempDirectory("fermapp-export");
        try {
            SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
            typeBuilder.setName("parcels");
            typeBuilder.setCRS(DefaultGeographicCRS.WGS84);
            typeBuilder.add("the_geom", org.locationtech.jts.geom.Polygon.class);
            for (ShpField field : fields) {
                if (field.type() == String.class) {
                    typeBuilder.length(254);
                }
                typeBuilder.add(field.name(), field.type());
            }
            SimpleFeatureType type = typeBuilder.buildFeatureType();

            Path shp = directory.resolve("parcels.shp");
            Map<String, Serializable> params = new HashMap<>();
            params.put(ShapefileDataStoreFactory.URLP.key, shp.toUri().toURL());
            params.put(ShapefileDataStoreFactory.CREATE_SPATIAL_INDEX.key, Boolean.FALSE);
            ShapefileDataStore store = (ShapefileDataStore) new ShapefileDataStoreFactory().createNewDataStore(params);
            try {
                store.setCharset(StandardCharsets.UTF_8);
                store.createSchema(type);
                GeometryFactory factory = new GeometryFactory();
                List<SimpleFeature> features = new ArrayList<>();
                SimpleFeatureBuilder builder = new SimpleFeatureBuilder(type);
                for (int i = 0; i < geometries.size(); i++) {
                    builder.add(toJts(factory, geometries.get(i)));
                    for (Object value : values.get(i)) {
                        builder.add(value);
                    }
                    features.add(builder.buildFeature(null));
                }
                if (!features.isEmpty()) {
                    try (Transaction transaction = new DefaultTransaction("export")) {
                        SimpleFeatureStore featureStore = (SimpleFeatureStore) store.getFeatureSource(store.getTypeNames()[0]);
                        featureStore.setTransaction(transaction);
                        featureStore.addFeatures(new ListFeatureCollection(type, features));
                        transaction.commit();
                    }
                }
            } finally {
                store.dispose();
            }
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("parcels.shp", Files.readAllBytes(shp));
            files.put("parcels.shx", Files.readAllBytes(directory.resolve("parcels.shx")));
            files.put("parcels.dbf", Files.readAllBytes(directory.resolve("parcels.dbf")));
            files.put("parcels.prj", WGS84_PRJ.getBytes(StandardCharsets.US_ASCII));
            return zip(files);
        } finally {
            try (Stream<Path> files = Files.walk(directory)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static org.locationtech.jts.geom.Polygon toJts(GeometryFactory factory, Polygon polygon) {
        List<List<Position>> rings = polygon.coordinates().stream().map(Geo::closeRing).toList();
        LinearRing shell = factory.createLinearRing(coordinates(rings.get(0)));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = factory.createLinearRing(coordinates(rings.get(i)));
        }
        return factory.createPolygon(shell, holes);
    }

    private static Coordinate[] coordinates(List<Position> ring) {
        return ring.stream().map(point -> new Coordinate(point.lng(), point.lat())).toArray(Coordinate[]::new);
    }

    private static String cut(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() > max ? text.substring(0, max) : text;
    }

    public ExportFile exportParcels(String format, List<ExportParcel> parcels) {
        try {
            return switch (format) {
                case "geojson" -> new ExportFile(mapper.writeValueAsBytes(toFeatureCollection(parcels)), "application/geo+json", "geojson");
                case "kml" -> new ExportFile(toKml(parcels).getBytes(StandardCharsets.UTF_8), "application/vnd.google-earth.kml+xml", "kml");
                case "kmz" -> new ExportFile(zip(Map.of("doc.kml", toKml(parcels).getBytes(StandardCharsets.UTF_8))),
                        "application/vnd.google-earth.kmz", "kmz");
                case "shp" -> new ExportFile(toShapefile(
                        List.of(new ShpField("name", String.class), new ShpField("code", String.class),
                                new ShpField("area_ha", Double.class), new ShpField("perimeter", Double.class),
                                new ShpField("locality", String.class), new ShpField("county", String.class),
                                new ShpField("notes", String.class)),
                        parcels.stream().map(ExportParcel::geometry).toList(),
                        parcels.stream().map(p -> List.<Object>of(p.name(), p.code(), p.areaHa(), p.perimeterM(),
                                p.locality() == null ? "" : p.locality(), p.county() == null ? "" : p.county(),
                                p.notes() == null ? "" : p.notes())).toList()),
                        "application/zip", "shp.zip");
                case "lpis" -> new ExportFile(toShapefile(
                        List.of(new ShpField("NR_PARCELA", String.class), new ShpField("BLOC_FIZIC", String.class),
                                new ShpField("SUPRAFATA", Double.class), new ShpField("UAT", String.class),
                                new ShpField("JUDET", String.class)),
                        parcels.stream().map(ExportParcel::geometry).toList(),
                        parcels.stream().map(p -> List.<Object>of(cut(p.name(), 50), cut(p.code(), 50), p.areaHa(),
                                cut(p.locality(), 50), cut(p.county(), 50))).toList()),
                        "application/zip", "lpis.zip");
                default -> throw Errors.badRequest("Unsupported format.");
            };
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Export failed", error);
        }
    }
}
