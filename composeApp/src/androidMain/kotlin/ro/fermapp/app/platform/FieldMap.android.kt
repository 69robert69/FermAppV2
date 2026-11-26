package ro.fermapp.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import ro.fermapp.domain.Position

private fun Position.latLng() = LatLng(lat, lng)

/** Hue (0-360) of an ARGB colour, for the default marker icon. */
private fun hueOf(argb: Long): Float {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    if (max == min) return 0f
    val h = when (max) {
        r -> (g - b) / (max - min)
        g -> 2f + (b - r) / (max - min)
        else -> 4f + (r - g) / (max - min)
    }
    return ((h * 60f) + 360f) % 360f
}

@Composable
actual fun FieldMap(
    modifier: Modifier,
    camera: MapCamera,
    polygons: List<MapPolygon>,
    markers: List<MapMarker>,
    lines: List<MapLine>,
    onTap: ((Position) -> Unit)?,
    onPolygonTap: ((String) -> Unit)?,
    fitTo: List<Position>?,
) {
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(camera.center.latLng(), camera.zoom)
    }
    LaunchedEffect(fitTo) {
        val points = fitTo ?: return@LaunchedEffect
        if (points.isEmpty()) return@LaunchedEffect
        val bounds = LatLngBounds.builder().apply { points.forEach { include(it.latLng()) } }.build()
        runCatching { cameraState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 120)) }
    }
    val properties = remember { MapProperties(mapType = MapType.HYBRID, isMyLocationEnabled = false) }
    val settings = remember { MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, compassEnabled = true) }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraState,
        properties = properties,
        uiSettings = settings,
        onMapClick = { onTap?.invoke(Position(it.longitude, it.latitude)) },
    ) {
        polygons.forEach { polygon ->
            val outer = polygon.rings.firstOrNull() ?: return@forEach
            Polygon(
                points = outer.map { it.latLng() },
                holes = polygon.rings.drop(1).map { ring -> ring.map { it.latLng() } },
                fillColor = Color(polygon.fill.toInt()),
                strokeColor = Color(polygon.stroke.toInt()),
                strokeWidth = 4f,
                clickable = onPolygonTap != null,
                onClick = { onPolygonTap?.invoke(polygon.id) },
            )
        }
        lines.forEach { line ->
            Polyline(points = line.points.map { it.latLng() }, color = Color(line.color.toInt()), width = line.widthPx)
        }
        markers.forEach { marker ->
            Marker(
                state = MarkerState(marker.position.latLng()),
                title = marker.title,
                rotation = marker.rotationDeg,
                icon = BitmapDescriptorFactory.defaultMarker(hueOf(marker.color)),
            )
        }
    }
}
