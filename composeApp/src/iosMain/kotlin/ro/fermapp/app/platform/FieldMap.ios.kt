package ro.fermapp.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLLocationCoordinate2D
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSError
import platform.MapKit.MKAnnotationProtocol
import platform.MapKit.MKCoordinateRegionMakeWithDistance
import platform.MapKit.MKMapRectNull
import platform.MapKit.MKMapRectUnion
import platform.MapKit.MKMapTypeHybrid
import platform.MapKit.MKMapView
import platform.MapKit.MKMapViewDelegateProtocol
import platform.MapKit.MKOverlayProtocol
import platform.MapKit.MKOverlayRenderer
import platform.MapKit.MKPointAnnotation
import platform.MapKit.MKPolygon
import platform.MapKit.MKPolygonRenderer
import platform.MapKit.MKPolyline
import platform.MapKit.MKPolylineRenderer
import platform.UIKit.UIColor
import platform.UIKit.UIEdgeInsetsMake
import platform.UIKit.UITapGestureRecognizer
import platform.darwin.NSObject
import ro.fermapp.domain.Position
import kotlin.coroutines.resume
import kotlin.math.pow

private fun uiColor(argb: Long): UIColor = UIColor(
    red = ((argb shr 16) and 0xFF) / 255.0,
    green = ((argb shr 8) and 0xFF) / 255.0,
    blue = (argb and 0xFF) / 255.0,
    alpha = ((argb shr 24) and 0xFF) / 255.0,
)

/** Styles of the overlays currently on the map, looked up by the delegate. */
@OptIn(ExperimentalForeignApi::class)
private class MapDelegate : NSObject(), MKMapViewDelegateProtocol {
    val fills = mutableMapOf<MKOverlayProtocol, Pair<Long, Long>>()
    val lineColors = mutableMapOf<MKOverlayProtocol, Pair<Long, Float>>()
    val polygonIds = mutableMapOf<MKOverlayProtocol, String>()

    override fun mapView(mapView: MKMapView, rendererForOverlay: MKOverlayProtocol): MKOverlayRenderer {
        return when (rendererForOverlay) {
            is MKPolygon -> MKPolygonRenderer(rendererForOverlay).apply {
                val (fill, stroke) = fills[rendererForOverlay] ?: (0x5500FF00L to 0xFF00FF00L)
                fillColor = uiColor(fill)
                strokeColor = uiColor(stroke)
                lineWidth = 2.0
            }
            is MKPolyline -> MKPolylineRenderer(rendererForOverlay).apply {
                val (color, width) = lineColors[rendererForOverlay] ?: (0xFFE2B23DL to 6f)
                strokeColor = uiColor(color)
                lineWidth = width / 2.0
            }
            else -> MKOverlayRenderer(rendererForOverlay)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun coordinates(points: List<Position>, block: (kotlinx.cinterop.CPointer<CLLocationCoordinate2D>, Int) -> Unit) = memScoped {
    val array = allocArray<CLLocationCoordinate2D>(points.size)
    points.forEachIndexed { index, point ->
        array[index].latitude = point.lat
        array[index].longitude = point.lng
    }
    block(array, points.size)
}

/** Handles taps: inside a polygon selects it, elsewhere passes the coordinate on. */
@OptIn(ExperimentalForeignApi::class)
private class TapTarget(val map: MKMapView, val delegate: MapDelegate) : NSObject() {
    var onTap: ((Position) -> Unit)? = null
    var onPolygonTap: ((String) -> Unit)? = null

    @kotlinx.cinterop.ObjCAction
    fun handle(recognizer: UITapGestureRecognizer) {
        val point = recognizer.locationInView(map)
        val coordinate = map.convertPoint(point, toCoordinateFromView = map)
        val position = coordinate.useContents { Position(longitude, latitude) }
        val hit = delegate.polygonIds.entries.firstOrNull { (overlay, _) ->
            val polygon = overlay as MKPolygon
            val renderer = MKPolygonRenderer(polygon)
            val mapPoint = platform.MapKit.MKMapPointForCoordinate(coordinate)
            val inRenderer = renderer.pointForMapPoint(mapPoint)
            renderer.path?.let { platform.CoreGraphics.CGPathContainsPoint(it, null, inRenderer, false) } ?: false
        }
        if (hit != null && onPolygonTap != null) onPolygonTap?.invoke(hit.value) else onTap?.invoke(position)
    }
}

@OptIn(ExperimentalForeignApi::class)
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
    val delegate = remember { MapDelegate() }
    val map = remember {
        MKMapView().apply {
            mapType = MKMapTypeHybrid
            this.delegate = delegate
            // Zoom level to a region size: about 40,000 km / 2^zoom across.
            val meters = 40_000_000.0 / 2.0.pow(camera.zoom.toDouble())
            setRegion(MKCoordinateRegionMakeWithDistance(CLLocationCoordinate2DMake(camera.center.lat, camera.center.lng), meters, meters), animated = false)
        }
    }
    val tapTarget = remember {
        TapTarget(map, delegate).also { target ->
            map.addGestureRecognizer(UITapGestureRecognizer(target = target, action = platform.objc.sel_registerName("handle:")))
        }
    }
    tapTarget.onTap = onTap
    tapTarget.onPolygonTap = onPolygonTap

    UIKitView(
        factory = { map },
        modifier = modifier,
        update = { view ->
            view.removeOverlays(view.overlays)
            view.removeAnnotations(view.annotations)
            delegate.fills.clear()
            delegate.lineColors.clear()
            delegate.polygonIds.clear()
            polygons.forEach { polygon ->
                val outer = polygon.rings.firstOrNull() ?: return@forEach
                coordinates(outer) { array, count ->
                    val shape = MKPolygon.polygonWithCoordinates(array, count.toULong())
                    delegate.fills[shape] = polygon.fill to polygon.stroke
                    delegate.polygonIds[shape] = polygon.id
                    view.addOverlay(shape)
                }
            }
            lines.forEach { line ->
                coordinates(line.points) { array, count ->
                    val shape = MKPolyline.polylineWithCoordinates(array, count.toULong())
                    delegate.lineColors[shape] = line.color to line.widthPx
                    view.addOverlay(shape)
                }
            }
            markers.forEach { marker ->
                val annotation = MKPointAnnotation()
                annotation.setCoordinate(CLLocationCoordinate2DMake(marker.position.lat, marker.position.lng))
                annotation.setTitle(marker.title)
                view.addAnnotation(annotation as MKAnnotationProtocol)
            }
            val points = fitTo
            if (!points.isNullOrEmpty()) {
                var rect: CValue<platform.MapKit.MKMapRect> = MKMapRectNull.readValue()
                points.forEach { point ->
                    val mapPoint = platform.MapKit.MKMapPointForCoordinate(CLLocationCoordinate2DMake(point.lat, point.lng))
                    val pointRect = mapPoint.useContents { platform.MapKit.MKMapRectMake(x, y, 1.0, 1.0) }
                    rect = MKMapRectUnion(rect, pointRect)
                }
                view.setVisibleMapRect(rect, edgePadding = UIEdgeInsetsMake(60.0, 40.0, 60.0, 40.0), animated = true)
            }
        },
    )
}

/** One-shot location fix through CoreLocation. */
@OptIn(ExperimentalForeignApi::class)
internal object IosLocation {
    private val manager = CLLocationManager()

    private class Listener(val done: (Position?) -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? platform.CoreLocation.CLLocation
            done(location?.coordinate?.useContents { Position(longitude, latitude) })
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            done(null)
        }
    }

    private var listener: Listener? = null

    suspend fun current(): Position? = suspendCancellableCoroutine { continuation ->
        var finished = false
        listener = Listener { position ->
            if (!finished) {
                finished = true
                continuation.resume(position)
            }
        }
        manager.delegate = listener
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.requestWhenInUseAuthorization()
        manager.requestLocation()
    }
}
