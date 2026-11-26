package ro.fermapp.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ktor.client.HttpClient
import okio.FileSystem
import okio.Path
import ro.fermapp.domain.Position

/** What the shared app needs from Android and iOS. Each platform provides these in its own source set. */

/** Base address of the FermApp API, for example http://10.0.2.2:4000. */
expect val apiOrigin: String

/** Directory for the offline cache and the outbox. It survives app restarts. */
expect val appDataDir: Path

expect val fileSystem: FileSystem

/** An HTTP client with the platform engine (OkHttp on Android, NSURLSession on iOS). */
expect fun platformHttpClient(): HttpClient

/** Encrypted key-value storage for the session (Android Keystore, iOS Keychain). */
expect object SecureStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

/** Opens a downloaded file (a PDF report, for example) with the system share sheet. */
expect fun shareFile(path: Path, mimeType: String)

/** A polygon drawn on the map. */
data class MapPolygon(
    val id: String,
    val rings: List<List<Position>>,
    val fill: Long,
    val stroke: Long,
    val label: String? = null,
)

/** A point on the map: a machine, a vertex being drawn, a marker. */
data class MapMarker(
    val id: String,
    val position: Position,
    val title: String,
    val color: Long,
    val rotationDeg: Float = 0f,
)

/** A line on the map: a machine path, the outline being drawn. */
data class MapLine(val id: String, val points: List<Position>, val color: Long, val widthPx: Float = 6f)

data class MapCamera(val center: Position, val zoom: Float)

/**
 * The satellite map with parcels, machines and lines. Google Maps on Android, MapKit on iOS.
 * [onTap] receives map taps (used to draw parcels); [onPolygonTap] receives the polygon id.
 */
@Composable
expect fun FieldMap(
    modifier: Modifier,
    camera: MapCamera,
    polygons: List<MapPolygon>,
    markers: List<MapMarker>,
    lines: List<MapLine>,
    onTap: ((Position) -> Unit)?,
    onPolygonTap: ((String) -> Unit)?,
    fitTo: List<Position>?,
)

/** The phone's current position, or null without permission or a fix. */
expect suspend fun currentLocation(): Position?

/** A file the user picked: its name, MIME type and contents. */
class PickedFile(val name: String, val mimeType: String, val bytes: ByteArray)

/**
 * Returns a launcher that opens the system picker for the given MIME types (for example
 * any image, or any file); the result arrives in [onPicked], or nothing when cancelled.
 */
@Composable
expect fun rememberFilePicker(mimeTypes: List<String>, onPicked: (PickedFile) -> Unit): () -> Unit

/** Opens a URL in the browser or the maps app (navigation to a parcel). */
expect fun openUrl(url: String)
