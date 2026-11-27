package com.fermapp.api.modules.parcels

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fermapp.api.common.error.ApiException
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Js
import com.fermapp.api.domain.geo.Geo
import com.fermapp.api.domain.geo.Geo.Polygon
import com.fermapp.api.domain.geo.Geo.Position
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Serializable
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.geotools.api.data.SimpleFeatureStore
import org.geotools.api.feature.simple.SimpleFeature
import org.geotools.api.feature.type.GeometryType
import org.geotools.api.referencing.operation.MathTransform
import org.geotools.data.DefaultTransaction
import org.geotools.data.collection.ListFeatureCollection
import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.data.shapefile.ShapefileDataStoreFactory
import org.geotools.feature.simple.SimpleFeatureBuilder
import org.geotools.feature.simple.SimpleFeatureTypeBuilder
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.springframework.stereotype.Component
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.locationtech.jts.geom.Polygon as JtsPolygon

/**
 * Parcel import and export (geo-io.ts): GeoJSON, KML, KMZ, Shapefile and APIA LPIS files.
 * Archives are checked before extraction; projected shapefiles (Stereo 70 included) are
 * reprojected to WGS84 from their .prj, as shpjs did.
 */
@Component
class GeoIo(private val mapper: ObjectMapper) {

    @JvmRecord
    data class ImportedParcel(
        val name: String,
        val code: String?,
        val locality: String?,
        val county: String?,
        val notes: String?,
        val geometry: Polygon,
    )

    @JvmRecord
    data class ImportProblem(val index: Int, val name: String, val reason: String)

    @JvmRecord
    data class ImportResult(val parcels: List<ImportedParcel>, val problems: List<ImportProblem>)

    @JvmRecord
    data class ExportParcel(
        val name: String?,
        val code: String?,
        val areaHa: Double,
        val perimeterM: Double,
        val locality: String?,
        val county: String?,
        val notes: String?,
        val geometry: Polygon?,
    )

    @JvmRecord
    @Suppress("ArrayInDataClass")
    data class ExportFile(val content: ByteArray, val contentType: String, val extension: String)

    // ------------------------------------------------------------------ features to parcels

    fun featuresToParcels(features: List<JsonNode>): ImportResult {
        if (features.size > MAX_FEATURES) {
            throw Errors.badRequest("The file exceeds the limit of $MAX_FEATURES parcels.")
        }
        val parcels = mutableListOf<ImportedParcel>()
        val problems = mutableListOf<ImportProblem>()
        features.forEachIndexed { index, feature ->
            val properties: JsonNode = if (feature.path("properties").isObject) feature.get("properties") else mapper.createObjectNode()
            val baseName = pick(properties, NAME_ALIASES) ?: ("Imported parcel " + (index + 1))
            val polygons = try {
                polygonsOf(feature.get("geometry"))
            } catch (error: IllegalArgumentException) {
                problems += ImportProblem(index, baseName, "Invalid geometry: " + error.message + ".")
                return@forEachIndexed
            }
            if (polygons.isEmpty()) {
                problems += ImportProblem(index, baseName, "The element does not contain a polygon.")
                return@forEachIndexed
            }
            polygons.forEachIndexed { part, polygon ->
                val suffix = if (polygons.size > 1) " (" + (part + 1) + ")" else ""
                val reason = Geo.validatePolygon(polygon)
                if (reason != null) {
                    problems += ImportProblem(index, baseName + suffix, reason)
                    return@forEachIndexed
                }
                val code = pick(properties, CODE_ALIASES)
                parcels += ImportedParcel(
                    baseName + suffix,
                    code?.let { it + (if (polygons.size > 1) "-" + (part + 1) else "") },
                    pick(properties, LOCALITY_ALIASES), pick(properties, COUNTY_ALIASES), pick(properties, NOTES_ALIASES),
                    polygon,
                )
            }
        }
        return ImportResult(parcels, problems)
    }

    private fun asFeatures(data: JsonNode): List<JsonNode> {
        val collections = if (data.isArray) data.toList() else listOf(data)
        val features = mutableListOf<JsonNode>()
        for (item in collections) {
            val type = item.path("type").asText("")
            if (type == "FeatureCollection" && item.path("features").isArray) {
                item.get("features").forEach { features.add(it) }
            } else if (type == "Feature") {
                features.add(item)
            } else if (type == "Polygon" || type == "MultiPolygon") {
                val feature = mapper.createObjectNode()
                feature.put("type", "Feature")
                feature.set<JsonNode>("geometry", item)
                feature.set<JsonNode>("properties", mapper.createObjectNode())
                features.add(feature)
            } else {
                throw Errors.badRequest("The file does not contain valid GeoJSON data.")
            }
        }
        return features
    }

    // ------------------------------------------------------------------ KML

    private fun kmlRing(linearRing: Element?): ArrayNode {
        val ring = mapper.createArrayNode()
        val coordinates = linearRing?.let { child(it, "coordinates") } ?: return ring
        for (tuple in javaSplit(javaTrim(coordinates.textContent), Regex("\\s+"))) {
            if (tuple.all { Character.isWhitespace(it) }) {
                continue
            }
            val point = mapper.createArrayNode()
            for (part in javaSplit(tuple, Regex(","))) {
                try {
                    point.add(java.lang.Double.parseDouble(javaTrim(part)))
                } catch (error: NumberFormatException) {
                    point.add(javaTrim(part))
                }
            }
            ring.add(point)
        }
        return ring
    }

    private fun kmlGeometry(element: Element): JsonNode? {
        val name = localNameOf(element)
        if (name == "Polygon") {
            val polygon = mapper.createObjectNode()
            polygon.put("type", "Polygon")
            val coordinates = polygon.putArray("coordinates")
            val outer = child(element, "outerBoundaryIs")
            if (outer != null) {
                coordinates.add(kmlRing(child(outer, "LinearRing")))
            }
            for (inner in children(element, "innerBoundaryIs")) {
                coordinates.add(kmlRing(child(inner, "LinearRing")))
            }
            return polygon
        }
        if (name == "MultiGeometry") {
            val collection = mapper.createObjectNode()
            collection.put("type", "GeometryCollection")
            val geometries = collection.putArray("geometries")
            val nodes = element.childNodes
            for (i in 0 until nodes.length) {
                val childElement = nodes.item(i) as? Element ?: continue
                kmlGeometry(childElement)?.let { geometries.add(it) }
            }
            return collection
        }
        if (name == "Point" || name == "LineString" || name == "LinearRing" || name == "Track") {
            val other = mapper.createObjectNode()
            other.put("type", if (name == "LineString" || name == "LinearRing") "LineString" else "Point")
            other.set<JsonNode>("coordinates", mapper.createArrayNode())
            return other
        }
        return null
    }

    /** KML placemarks to GeoJSON features, like @tmcw/togeojson: name, description and extended data become properties. */
    private fun parseKml(content: ByteArray): List<JsonNode> {
        val document: Document = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            val builder = factory.newDocumentBuilder()
            builder.setErrorHandler(null)
            builder.parse(ByteArrayInputStream(content))
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid KML", error)
        }
        val features = mutableListOf<JsonNode>()
        val placemarks = document.getElementsByTagNameNS("*", "Placemark")
        for (i in 0 until placemarks.length) {
            val placemark = placemarks.item(i) as Element
            val properties = mapper.createObjectNode()
            child(placemark, "name")?.let { properties.put("name", it.textContent) }
            child(placemark, "description")?.let { properties.put("description", it.textContent) }
            val extended = child(placemark, "ExtendedData")
            if (extended != null) {
                for (data in children(extended, "Data")) {
                    val value = child(data, "value")
                    properties.put(data.getAttribute("name"), value?.textContent ?: "")
                }
                for (schemaData in children(extended, "SchemaData")) {
                    for (simple in children(schemaData, "SimpleData")) {
                        properties.put(simple.getAttribute("name"), simple.textContent)
                    }
                }
            }
            var geometry: JsonNode? = null
            val nodes = placemark.childNodes
            var j = 0
            while (j < nodes.length && geometry == null) {
                val element = nodes.item(j)
                if (element is Element) {
                    geometry = kmlGeometry(element)
                }
                j++
            }
            val feature = mapper.createObjectNode()
            feature.put("type", "Feature")
            feature.set<JsonNode>("geometry", geometry)
            feature.set<JsonNode>("properties", properties)
            features.add(feature)
        }
        return features
    }

    // ------------------------------------------------------------------ shapefiles

    private fun ringJson(coordinates: Array<Coordinate>): JsonNode {
        val ring = mapper.createArrayNode()
        for (coordinate in coordinates) {
            val point = mapper.createArrayNode()
            point.add(coordinate.x)
            point.add(coordinate.y)
            ring.add(point)
        }
        return ring
    }

    private fun polygonCoordinates(polygon: JtsPolygon): JsonNode {
        val rings = mapper.createArrayNode()
        rings.add(ringJson(polygon.exteriorRing.coordinates))
        for (i in 0 until polygon.numInteriorRing) {
            rings.add(ringJson(polygon.getInteriorRingN(i).coordinates))
        }
        return rings
    }

    private fun geometryJson(geometry: Geometry?): JsonNode? {
        if (geometry == null) {
            return null
        }
        val node = mapper.createObjectNode()
        when (geometry) {
            is JtsPolygon -> {
                node.put("type", "Polygon")
                node.set<JsonNode>("coordinates", polygonCoordinates(geometry))
            }
            is MultiPolygon -> {
                node.put("type", "MultiPolygon")
                val polygons = node.putArray("coordinates")
                for (i in 0 until geometry.numGeometries) {
                    polygons.add(polygonCoordinates(geometry.getGeometryN(i) as JtsPolygon))
                }
            }
            is GeometryCollection -> {
                node.put("type", "GeometryCollection")
                val geometries = node.putArray("geometries")
                for (i in 0 until geometry.numGeometries) {
                    geometries.add(geometryJson(geometry.getGeometryN(i)))
                }
            }
            else -> {
                node.put("type", geometry.geometryType)
                node.set<JsonNode>("coordinates", mapper.createArrayNode())
            }
        }
        return node
    }

    /** Reads every layer of a zipped shapefile, reprojecting to WGS84 when the .prj says otherwise. */
    private fun parseShapefileZip(zip: ZipFile): List<JsonNode> {
        val directory = Files.createTempDirectory("fermapp-shp")
        try {
            val layers = LinkedHashMap<String, Path>()
            for (entry in zip.entries.toList()) {
                if (entry.isDirectory) {
                    continue
                }
                val fileName = Path.of(entry.name).fileName.toString()
                if (fileName.startsWith(".") || entry.name.contains("__MACOSX")) {
                    continue
                }
                val target = directory.resolve(fileName).normalize()
                if (!target.startsWith(directory)) {
                    continue
                }
                Files.write(target, read(zip, entry))
                if (fileName.lowercase().endsWith(".shp")) {
                    layers[fileName.substring(0, fileName.length - 4)] = target
                }
            }
            if (layers.isEmpty()) {
                throw IllegalArgumentException("No shapefile in the archive")
            }
            val features = mutableListOf<JsonNode>()
            for ((base, shp) in layers) {
                features.addAll(readLayer(directory, base, shp))
            }
            return features
        } finally {
            deleteTree(directory)
        }
    }

    private fun readLayer(directory: Path, base: String, shp: Path): List<JsonNode> {
        val store = ShapefileDataStore(shp.toUri().toURL())
        try {
            var charset: Charset = StandardCharsets.UTF_8
            val cpg = sibling(directory, base, "cpg")
            if (cpg != null) {
                charset = try {
                    Charset.forName(javaTrim(Files.readString(cpg, StandardCharsets.US_ASCII)))
                } catch (ignored: RuntimeException) {
                    StandardCharsets.UTF_8
                }
            }
            store.charset = charset
            var transform: MathTransform? = null
            val prj = sibling(directory, base, "prj")
            if (prj != null) {
                val source = CRS.parseWKT(Files.readString(prj, StandardCharsets.ISO_8859_1))
                if (!CRS.equalsIgnoreMetadata(source, DefaultGeographicCRS.WGS84)) {
                    transform = CRS.findMathTransform(source, DefaultGeographicCRS.WGS84, true)
                }
            }
            val features = mutableListOf<JsonNode>()
            store.featureSource.features.features().use { iterator ->
                while (iterator.hasNext()) {
                    val feature = iterator.next()
                    var geometry = feature.defaultGeometry as Geometry?
                    if (geometry != null && transform != null) {
                        geometry = JTS.transform(geometry, transform)
                    }
                    val properties = mapper.createObjectNode()
                    for (descriptor in feature.featureType.attributeDescriptors) {
                        if (descriptor.type is GeometryType) {
                            continue
                        }
                        putProperty(properties, descriptor.localName, feature.getAttribute(descriptor.localName))
                    }
                    val node = mapper.createObjectNode()
                    node.put("type", "Feature")
                    node.set<JsonNode>("geometry", geometryJson(geometry))
                    node.set<JsonNode>("properties", properties)
                    features.add(node)
                }
            }
            return features
        } finally {
            store.dispose()
        }
    }

    // ------------------------------------------------------------------ import entry point

    fun parseGeoFile(format: String, buffer: ByteArray): ImportResult {
        val features: List<JsonNode> = try {
            when (format) {
                "geojson" -> asFeatures(mapper.readTree(buffer))
                "kml" -> parseKml(buffer)
                "kmz" -> openArchive(buffer).use { zip ->
                    val entry = zip.entries.toList().firstOrNull { it.name.lowercase().endsWith(".kml") }
                        ?: throw Errors.badRequest("The KMZ archive does not contain a KML file.")
                    parseKml(read(zip, entry))
                }
                // LPIS files can come as GeoJSON or as a Shapefile archive (including Stereo 70, via .prj).
                "shp", "lpis" -> if (buffer.isNotEmpty() && buffer[0] == 0x7b.toByte()) {
                    asFeatures(mapper.readTree(buffer))
                } else {
                    openArchive(buffer).use { parseShapefileZip(it) }
                }
                else -> throw Errors.badRequest("Unsupported format.")
            }
        } catch (error: ApiException) {
            throw error
        } catch (error: Exception) {
            throw Errors.badRequest("The " + format.uppercase() + " file cannot be read.")
        }
        return featuresToParcels(features)
    }

    // ------------------------------------------------------------------ export

    fun toFeatureCollection(parcels: List<ExportParcel>): JsonNode {
        val collection = mapper.createObjectNode()
        collection.put("type", "FeatureCollection")
        val features = collection.putArray("features")
        for (parcel in parcels) {
            val feature = features.addObject()
            feature.put("type", "Feature")
            feature.set<JsonNode>("geometry", mapper.valueToTree<JsonNode>(parcel.geometry))
            val properties = feature.putObject("properties")
            properties.put("name", parcel.name)
            properties.put("code", parcel.code)
            properties.set<JsonNode>("area_ha", mapper.valueToTree<JsonNode>(parcel.areaHa))
            properties.set<JsonNode>("perimeter", mapper.valueToTree<JsonNode>(parcel.perimeterM))
            properties.put("locality", parcel.locality ?: "")
            properties.put("county", parcel.county ?: "")
            properties.put("notes", parcel.notes ?: "")
        }
        return collection
    }

    fun toKml(parcels: List<ExportParcel>): String {
        val placemarks = StringBuilder()
        for (parcel in parcels) {
            val rings = parcel.geometry!!.coordinates
            val inner = StringBuilder()
            for (i in 1 until rings.size) {
                inner.append("<innerBoundaryIs><LinearRing><coordinates>").append(kmlRingText(rings[i]))
                    .append("</coordinates></LinearRing></innerBoundaryIs>")
            }
            placemarks.append("<Placemark><name>").append(xml(parcel.name!!)).append("</name><description>")
                .append(xml(parcel.code + ", " + Js.num(parcel.areaHa) + " ha")).append("</description><ExtendedData>")
                .append("<Data name=\"code\"><value>").append(xml(parcel.code!!)).append("</value></Data>")
                .append("<Data name=\"locality\"><value>").append(xml(parcel.locality ?: "")).append("</value></Data>")
                .append("<Data name=\"county\"><value>").append(xml(parcel.county ?: "")).append("</value></Data>")
                .append("</ExtendedData><Polygon><outerBoundaryIs><LinearRing><coordinates>")
                .append(kmlRingText(rings[0])).append("</coordinates></LinearRing></outerBoundaryIs>").append(inner)
                .append("</Polygon></Placemark>")
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>FermApp</name>" +
            placemarks + "</Document></kml>"
    }

    private class ShpField(val name: String, val type: Class<*>)

    /** Writes a polygon shapefile named "parcels" (.shp, .shx, .dbf, .prj) and zips it, like shp-write. */
    private fun toShapefile(fields: List<ShpField>, geometries: List<Polygon?>, values: List<List<Any?>>): ByteArray {
        val directory = Files.createTempDirectory("fermapp-export")
        try {
            val typeBuilder = SimpleFeatureTypeBuilder()
            typeBuilder.setName("parcels")
            typeBuilder.crs = DefaultGeographicCRS.WGS84
            typeBuilder.add("the_geom", JtsPolygon::class.java)
            for (field in fields) {
                if (field.type == String::class.java) {
                    typeBuilder.length(254)
                }
                typeBuilder.add(field.name, field.type)
            }
            val type = typeBuilder.buildFeatureType()

            val shp = directory.resolve("parcels.shp")
            val params = HashMap<String, Serializable>()
            params[ShapefileDataStoreFactory.URLP.key] = shp.toUri().toURL()
            params[ShapefileDataStoreFactory.CREATE_SPATIAL_INDEX.key] = java.lang.Boolean.FALSE
            val store = ShapefileDataStoreFactory().createNewDataStore(params) as ShapefileDataStore
            try {
                store.charset = StandardCharsets.UTF_8
                store.createSchema(type)
                val factory = GeometryFactory()
                val features = mutableListOf<SimpleFeature>()
                val builder = SimpleFeatureBuilder(type)
                geometries.forEachIndexed { i, geometry ->
                    builder.add(toJts(factory, geometry!!))
                    for (value in values[i]) {
                        builder.add(value)
                    }
                    features += builder.buildFeature(null)
                }
                if (features.isNotEmpty()) {
                    DefaultTransaction("export").use { transaction ->
                        val featureStore = store.getFeatureSource(store.typeNames[0]) as SimpleFeatureStore
                        featureStore.transaction = transaction
                        featureStore.addFeatures(ListFeatureCollection(type, features))
                        transaction.commit()
                    }
                }
            } finally {
                store.dispose()
            }
            val files = LinkedHashMap<String, ByteArray>()
            files["parcels.shp"] = Files.readAllBytes(shp)
            files["parcels.shx"] = Files.readAllBytes(directory.resolve("parcels.shx"))
            files["parcels.dbf"] = Files.readAllBytes(directory.resolve("parcels.dbf"))
            files["parcels.prj"] = WGS84_PRJ.toByteArray(StandardCharsets.US_ASCII)
            return zip(files)
        } finally {
            deleteTree(directory)
        }
    }

    fun exportParcels(format: String, parcels: List<ExportParcel>): ExportFile {
        try {
            return when (format) {
                "geojson" -> ExportFile(mapper.writeValueAsBytes(toFeatureCollection(parcels)), "application/geo+json", "geojson")
                "kml" -> ExportFile(toKml(parcels).toByteArray(StandardCharsets.UTF_8), "application/vnd.google-earth.kml+xml", "kml")
                "kmz" -> ExportFile(
                    zip(mapOf("doc.kml" to toKml(parcels).toByteArray(StandardCharsets.UTF_8))),
                    "application/vnd.google-earth.kmz", "kmz",
                )
                "shp" -> ExportFile(
                    toShapefile(
                        listOf(
                            ShpField("name", String::class.java), ShpField("code", String::class.java),
                            ShpField("area_ha", BOXED_DOUBLE), ShpField("perimeter", BOXED_DOUBLE),
                            ShpField("locality", String::class.java), ShpField("county", String::class.java),
                            ShpField("notes", String::class.java),
                        ),
                        parcels.map { it.geometry },
                        parcels.map { listOf(it.name, it.code, it.areaHa, it.perimeterM, it.locality ?: "", it.county ?: "", it.notes ?: "") },
                    ),
                    "application/zip", "shp.zip",
                )
                "lpis" -> ExportFile(
                    toShapefile(
                        listOf(
                            ShpField("NR_PARCELA", String::class.java), ShpField("BLOC_FIZIC", String::class.java),
                            ShpField("SUPRAFATA", BOXED_DOUBLE), ShpField("UAT", String::class.java),
                            ShpField("JUDET", String::class.java),
                        ),
                        parcels.map { it.geometry },
                        parcels.map { listOf(cut(it.name, 50), cut(it.code, 50), it.areaHa, cut(it.locality, 50), cut(it.county, 50)) },
                    ),
                    "application/zip", "lpis.zip",
                )
                else -> throw Errors.badRequest("Unsupported format.")
            }
        } catch (error: ApiException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("Export failed", error)
        }
    }

    companion object {
        @JvmField
        val IMPORT_FORMATS: List<String> = listOf("geojson", "kml", "kmz", "shp", "lpis")

        private const val MAX_ARCHIVE_ENTRIES = 50
        private const val MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024
        private const val MAX_FEATURES = 2000

        /** The .prj that shp-write wrote next to every exported shapefile. */
        private const val WGS84_PRJ = "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137,298.257223563]]," +
            "PRIMEM[\"Greenwich\",0],UNIT[\"Degree\",0.017453292519943295]]"

        /** The shapefile schema needs java.lang.Double; Double::class.java would be the primitive type. */
        private val BOXED_DOUBLE: Class<*> = java.lang.Double::class.java

        /** Common field names from GIS exports and APIA/LPIS files. */
        private val NAME_ALIASES = listOf("name", "nume", "denumire", "parcela", "nume_parcela", "nr_parcela", "parcel", "title")
        private val CODE_ALIASES = listOf("code", "cod", "cod_parcela", "bloc_fizic", "blocfizic", "bf", "nr_bloc", "id_parcela")
        private val LOCALITY_ALIASES = listOf("locality", "localitate", "uat", "comuna", "siruta_nume")
        private val COUNTY_ALIASES = listOf("county", "judet", "județ", "jud")
        private val NOTES_ALIASES = listOf("notes", "observatii", "observații", "description", "descriere")

        init {
            // Longitude first, as in GeoJSON.
            System.setProperty("org.geotools.referencing.forceXY", "true")
        }

        /** String.trim() semantics: control characters and spaces only. */
        private fun javaTrim(value: String): String = value.trim { it <= ' ' }

        /** String.split(regex) semantics: trailing empty strings are dropped. */
        private fun javaSplit(value: String, regex: Regex): List<String> {
            if (value.isEmpty()) {
                return listOf("")
            }
            return value.split(regex).dropLastWhile { it.isEmpty() }
        }

        private fun asText(value: JsonNode): String = when {
            value.isNumber -> Js.num(value.asDouble())
            value.isTextual -> value.asText()
            value.isObject -> "[object Object]"
            else -> value.toString()
        }

        private fun pick(properties: JsonNode, aliases: List<String>): String? {
            val lower = HashMap<String, JsonNode>()
            properties.fields().forEach { lower.putIfAbsent(it.key.lowercase(), it.value) }
            for (alias in aliases) {
                val value = lower[alias]
                if (value != null && !value.isNull && !value.isMissingNode) {
                    val text = javaTrim(asText(value))
                    if (text.isNotEmpty()) {
                        return if (text.length > 200) text.substring(0, 200) else text
                    }
                }
            }
            return null
        }

        /** Drops altitude and checks the structure of each point. */
        private fun toPositions(ring: JsonNode?): List<Position> {
            if (ring == null || !ring.isArray) {
                throw IllegalArgumentException("invalid ring")
            }
            return ring.map { point ->
                if (!point.isArray || !point.path(0).isNumber || !point.path(1).isNumber) {
                    throw IllegalArgumentException("invalid coordinates")
                }
                Position(point.get(0).asDouble(), point.get(1).asDouble())
            }
        }

        private fun rings(polygon: JsonNode?): List<List<Position>> {
            if (polygon == null || !polygon.isArray) {
                throw IllegalArgumentException("invalid ring")
            }
            return polygon.map { toPositions(it) }
        }

        private fun polygonsOf(geometry: JsonNode?): List<Polygon> {
            if (geometry == null || geometry.isNull || geometry.isMissingNode) {
                return listOf()
            }
            val coordinates = geometry.get("coordinates")
            return when (geometry.path("type").asText()) {
                "Polygon" -> listOf(Polygon.of(rings(coordinates)))
                "MultiPolygon" -> {
                    if (coordinates == null || !coordinates.isArray) {
                        throw IllegalArgumentException("invalid ring")
                    }
                    coordinates.map { Polygon.of(rings(it)) }
                }
                "GeometryCollection" -> geometry.path("geometries").flatMap { polygonsOf(it) }
                else -> listOf()
            }
        }

        // -------------------------------------------------------------- archives

        /** Checks the archive before extracting it, as protection against oversized archives. */
        private fun openArchive(buffer: ByteArray): ZipFile {
            val zip = try {
                ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(buffer)).get()
            } catch (error: java.io.IOException) {
                throw Errors.badRequest("The archive cannot be read.")
            }
            val entries = zip.entries.toList()
            if (entries.size > MAX_ARCHIVE_ENTRIES) {
                throw Errors.badRequest("The archive contains too many files.")
            }
            val total = entries.sumOf { max(0L, it.size) }
            if (total > MAX_UNCOMPRESSED_BYTES) {
                throw Errors.badRequest("The archive is too large after extraction.")
            }
            return zip
        }

        private fun read(zip: ZipFile, entry: ZipArchiveEntry): ByteArray =
            zip.getInputStream(entry).use { it.readNBytes(min(MAX_UNCOMPRESSED_BYTES, (Int.MAX_VALUE - 8).toLong()).toInt()) }

        private fun deleteTree(directory: Path) {
            Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach { it.toFile().delete() } }
        }

        // -------------------------------------------------------------- KML

        private fun children(parent: Element, localName: String): List<Element> {
            val result = mutableListOf<Element>()
            val nodes = parent.childNodes
            for (i in 0 until nodes.length) {
                val element = nodes.item(i)
                if (element is Element && localName == localNameOf(element)) {
                    result += element
                }
            }
            return result
        }

        private fun localNameOf(node: Node): String = node.localName ?: node.nodeName.replaceFirst(Regex("^.*:"), "")

        private fun child(parent: Element, localName: String): Element? = children(parent, localName).firstOrNull()

        // -------------------------------------------------------------- shapefiles

        private fun putProperty(properties: ObjectNode, name: String, value: Any?) {
            when (value) {
                null -> properties.putNull(name)
                is Number -> properties.put(name, value.toDouble())
                is Boolean -> properties.put(name, value)
                else -> properties.put(name, value.toString())
            }
        }

        private fun sibling(directory: Path, base: String, extension: String): Path? =
            listOf("$base.$extension", base + "." + extension.uppercase())
                .map { directory.resolve(it) }
                .firstOrNull { Files.exists(it) }

        // -------------------------------------------------------------- export

        private fun xml(value: String): String =
            value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

        private fun kmlRingText(points: List<Position>): String {
            val text = StringBuilder()
            points.forEachIndexed { i, point ->
                if (i > 0) {
                    text.append(' ')
                }
                text.append(Js.num(point.lng)).append(',').append(Js.num(point.lat)).append(",0")
            }
            return text.toString()
        }

        private fun zip(files: Map<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.setMethod(ZipOutputStream.DEFLATED)
                for ((name, content) in files) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }

        private fun toJts(factory: GeometryFactory, polygon: Polygon): JtsPolygon {
            val rings = polygon.coordinates.map { Geo.closeRing(it) }
            val shell = factory.createLinearRing(coordinates(rings[0]))
            val holes = Array(rings.size - 1) { i -> factory.createLinearRing(coordinates(rings[i + 1])) }
            return factory.createPolygon(shell, holes)
        }

        private fun coordinates(ring: List<Position>): Array<Coordinate> = ring.map { Coordinate(it.lng, it.lat) }.toTypedArray()

        private fun cut(value: String?, max: Int): String {
            val text = value ?: ""
            return if (text.length > max) text.substring(0, max) else text
        }
    }
}
