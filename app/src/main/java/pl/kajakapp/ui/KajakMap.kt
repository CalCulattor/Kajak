package pl.kajakapp.ui

import android.content.Context
import android.view.Gravity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.logo.logo
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateBearing
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateOptions
import com.mapbox.maps.plugin.viewport.viewport
import pl.kajakapp.R
import pl.kajakapp.domain.PathPoint

private const val SRC_ROUTE = "kajak-route"
private const val SRC_START = "kajak-start"
private const val SRC_END = "kajak-end"
private const val LAYER_ROUTE = "kajak-route-line"
private const val LAYER_START = "kajak-start-dot"
private const val LAYER_END = "kajak-end-dot"

private const val ROUTE_COLOR = "#0B5C73"
private const val START_COLOR = "#2E7D32"
private const val END_COLOR = "#C62828"

/**
 * Mapa Mapbox ze śladem trasy.
 *
 * @param path ślad do narysowania (może rosnąć na żywo)
 * @param followUser pokazuje pozycję użytkownika i podąża za nią, dopóki użytkownik nie przesunie mapy
 *   (wymaga uprawnienia do lokalizacji – sprawdza to wołający)
 * @param fitPath po wczytaniu dopasowuje widok tak, by cały ślad był widoczny (podgląd zapisanej trasy)
 * @param showEnds rysuje zielony punkt startu i czerwony punkt końca
 */
@Composable
fun KajakMap(
    path: List<PathPoint>,
    modifier: Modifier = Modifier,
    followUser: Boolean = false,
    fitPath: Boolean = false,
    showEnds: Boolean = false,
    /** Przenosi logo i informację o źródłach map na górę (gdy dół ekranu zasłania panel). */
    ornamentsOnTop: Boolean = false,
    /** Zmiana tej wartości ponownie centruje mapę na użytkowniku (gdy włączone [followUser]). */
    recenterKey: Int = 0
) {
    val context = LocalContext.current
    if (!hasMapToken(context)) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Brak tokenu Mapbox. Dodaj MAPBOX_ACCESS_TOKEN do local.properties i zbuduj aplikację ponownie.",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val dark = isSystemInDarkTheme()
    // Zmiana motywu jasny/ciemny wczytuje mapę od nowa z pasującym stylem.
    key(dark) {
        val state = remember { MapState() }
        // Najnowsze parametry – wywołanie po wczytaniu stylu musi widzieć aktualne wartości, nie te z chwili utworzenia.
        state.path = path
        state.followUser = followUser
        state.fitPath = fitPath
        state.showEnds = showEnds
        state.recenterKey = recenterKey
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                MapView(ctx).also { view ->
                    state.view = view
                    view.gestures.updateSettings {
                        rotateEnabled = false
                        pitchEnabled = false
                    }
                    if (ornamentsOnTop) {
                        view.logo.updateSettings { position = Gravity.TOP or Gravity.START }
                        view.attribution.updateSettings { position = Gravity.TOP or Gravity.END }
                    }
                    view.mapboxMap.loadStyle(if (dark) Style.DARK else Style.OUTDOORS) { style ->
                        addRouteLayers(style)
                        state.styleReady = true
                        applyAll(view, state)
                    }
                }
            },
            update = { view -> if (state.styleReady) applyAll(view, state) },
            onRelease = { state.view = null }
        )
    }
}

private class MapState {
    var view: MapView? = null
    var styleReady = false
    var fitted = false
    var following = false
    var lastRecenterKey = 0
    var path: List<PathPoint> = emptyList()
    var followUser = false
    var fitPath = false
    var showEnds = false
    var recenterKey = 0
}

private fun applyAll(view: MapView, state: MapState) {
    applyPath(view, state)
    applyFollow(view, state)
}

private fun hasMapToken(context: Context): Boolean =
    context.getString(R.string.mapbox_access_token).isNotBlank()

private fun addRouteLayers(style: Style) {
    style.addSource(geoJsonSource(SRC_ROUTE) {})
    style.addSource(geoJsonSource(SRC_START) {})
    style.addSource(geoJsonSource(SRC_END) {})
    style.addLayer(
        lineLayer(LAYER_ROUTE, SRC_ROUTE) {
            lineColor(ROUTE_COLOR)
            lineWidth(5.0)
            lineCap(LineCap.ROUND)
            lineJoin(LineJoin.ROUND)
        }
    )
    style.addLayer(
        circleLayer(LAYER_START, SRC_START) {
            circleRadius(7.0)
            circleColor(START_COLOR)
            circleStrokeWidth(2.5)
            circleStrokeColor("#FFFFFF")
        }
    )
    style.addLayer(
        circleLayer(LAYER_END, SRC_END) {
            circleRadius(7.0)
            circleColor(END_COLOR)
            circleStrokeWidth(2.5)
            circleStrokeColor("#FFFFFF")
        }
    )
}

private fun pointFeatures(p: PathPoint?): FeatureCollection =
    if (p == null) {
        FeatureCollection.fromFeatures(ArrayList<Feature>())
    } else {
        FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)))
    }

private fun applyPath(view: MapView, state: MapState) {
    val path = state.path
    val showEnds = state.showEnds
    val style = view.mapboxMap.style ?: return
    val points = path.map { Point.fromLngLat(it.lon, it.lat) }
    style.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.featureCollection(
        if (points.size >= 2) {
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points)))
        } else {
            FeatureCollection.fromFeatures(ArrayList<Feature>())
        }
    )
    style.getSourceAs<GeoJsonSource>(SRC_START)?.featureCollection(
        pointFeatures(if (showEnds) path.firstOrNull() else null)
    )
    style.getSourceAs<GeoJsonSource>(SRC_END)?.featureCollection(
        pointFeatures(if (showEnds && path.size >= 2) path.last() else null)
    )
    if (state.fitPath && !state.fitted && points.isNotEmpty()) {
        state.fitted = true
        // Dopasowanie wymaga rozmiaru widoku, więc czekamy na pomiar.
        view.doOnLayout { view.mapboxMap.setCamera(fitCamera(view, points)) }
    }
}

private fun fitCamera(view: MapView, points: List<Point>): CameraOptions =
    if (points.size == 1) {
        CameraOptions.Builder().center(points[0]).zoom(15.0).build()
    } else {
        view.mapboxMap.cameraForCoordinates(
            points,
            CameraOptions.Builder().build(),
            EdgeInsets(64.0, 64.0, 64.0, 64.0),
            17.0,
            null
        )
    }

private fun applyFollow(view: MapView, state: MapState) {
    val follow = state.followUser
    val recenter = state.recenterKey != state.lastRecenterKey
    state.lastRecenterKey = state.recenterKey
    // Robimy to tylko przy zmianie, żeby kolejne odświeżenia nie odbierały użytkownikowi sterowania mapą.
    if (state.following == follow && !(recenter && follow)) return
    state.following = follow
    view.location.updateSettings {
        enabled = follow
        pulsingEnabled = follow
        if (follow) locationPuck = createDefault2DPuck(withBearing = false)
    }
    val viewport = view.viewport
    if (follow) {
        val target = viewport.makeFollowPuckViewportState(
            FollowPuckViewportStateOptions.Builder()
                .zoom(15.0)
                .pitch(0.0)
                .bearing(FollowPuckViewportStateBearing.Constant(0.0))
                .build()
        )
        viewport.transitionTo(target, viewport.makeImmediateViewportTransition())
    } else {
        viewport.idle()
    }
}
