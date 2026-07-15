package com.stadiamaps.ferrostar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.stadiamaps.ferrostar.composeui.config.NavigationViewComponentBuilder
import com.stadiamaps.ferrostar.composeui.config.VisualNavigationViewConfig
import com.stadiamaps.ferrostar.composeui.config.withCustomOverlayView
import com.stadiamaps.ferrostar.composeui.config.withSpeedLimitStyle
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.composeui.views.components.speedlimit.SignageStyle
import com.stadiamaps.ferrostar.core.annotation.fetchRoadSegments
import com.stadiamaps.ferrostar.maplibreui.NavigationMapClickResult
import com.stadiamaps.ferrostar.maplibreui.routeline.BorderedPolyline
import com.stadiamaps.ferrostar.maplibreui.routeline.RouteOverlayBuilder
import com.stadiamaps.ferrostar.maplibreui.runtime.rememberNavigationMapState
import com.stadiamaps.ferrostar.maplibreui.views.DynamicallyOrientingNavigationView
import com.stadiamaps.ferrostar.ui.ColoredRouteOverlay
import com.stadiamaps.ferrostar.ui.DestinationSelectionBottomSheet
import com.stadiamaps.ferrostar.ui.DestinationSelectionCameraEffect
import com.stadiamaps.ferrostar.ui.NotNavigatingOverlay
import com.stadiamaps.ferrostar.ui.RegionSelectionBottomSheet
import com.stadiamaps.ferrostar.ui.RouteAlertDialog
import com.stadiamaps.ferrostar.core.valhalla.Config as ValhallaConfig
import com.stadiamaps.ferrostar.support.CachedTile
import com.stadiamaps.ferrostar.support.scanCachedTiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.GeographicCoordinate

@Composable
fun DemoNavigationScene(viewModel: DemoNavigationViewModel = AppModule.viewModel) {
  // Keeps the screen on at consistent brightness while this Composable is in the view hierarchy.
  KeepScreenOnDisposableEffect()

  val context = LocalContext.current
  val focusManager = LocalFocusManager.current

  // Get location permissions.
  // NOTE: This is NOT a robust suggestion for how to get permissions in a production app.
  // This is simply minimal sample code in as few lines as possible.
  val allPermissions =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
      } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
      }

  val permissionsLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
          permissions ->
        when {
          permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
            viewModel.setLocationPermissions(true)
          }
          permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
            // TODO: Probably alert the user that this is unusable for navigation
          }
          // TODO: Foreground service permissions; we should block access until approved on API 34+
          else -> {
            // TODO
          }
        }
      }

  LaunchedEffect(Unit) {
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    ) {
      viewModel.setLocationPermissions(true)
    } else {
      permissionsLauncher.launch(allPermissions)
    }
  }
  val sceneState by viewModel.sceneState.collectAsState()
  val navigationMapState = rememberNavigationMapState()
  var destinationPreviewTopPaddingPx by remember { mutableIntStateOf(0) }
  var dismissSearchTrigger by remember { mutableIntStateOf(0) }
  var tiles by remember { mutableStateOf<List<CachedTile>>(emptyList()) }
  // Rescans the tile directory when the region is finished downloading and updates the overlay
  val isRegionDownloadActive = sceneState.regionDownload != null
  LaunchedEffect(isRegionDownloadActive) {
    tiles =
        withContext(Dispatchers.IO) {
          scanCachedTiles(File(context.filesDir, ValhallaConfig.TILE_DIR))
        }
  }
  val routeOverlayBuilder = if (viewModel.hasValidConnectionStatus(context)) {
    RouteOverlayBuilder { uiState ->
      // Color each edge by its Valhalla tile hierarchy level (blue = highway, orange = arterial,
      // green = local), using road_class fetched per-edge via /trace_attributes.
      val geometry = uiState.routeGeometry
      val segments by
      produceState(initialValue = emptyList(), geometry) {
        value =
            geometry?.let {
              fetchRoadSegments(
                  httpClient = AppModule.httpClient,
                  traceURL = AppModule.valhallaBaseUrl + AppModule.valhallaTraceAttributesEndpoint,
                  geometry = it,
                  profile = AppModule.ROUTE_PROFILE,
              )
            } ?: emptyList()
      }
      if (segments.isNotEmpty()) {
        ColoredRouteOverlay(segments = segments)
      } else {
        geometry?.let { BorderedPolyline(points = it) }
      }
    }
  } else RouteOverlayBuilder { uiState ->
    uiState.routeGeometry?.let { BorderedPolyline(points = it) }
  }
  DestinationSelectionCameraEffect(
      selectedDestination = sceneState.selectedDestination,
      destinationSheetHeightPx = sceneState.destinationSheetHeightPx,
      topOverlayBottomPx = destinationPreviewTopPaddingPx,
      navigationMapState = navigationMapState,
  )
  DynamicallyOrientingNavigationView(
      modifier = Modifier.fillMaxSize(),
      baseStyle = BaseStyle.Uri(AppModule.mapStyleUrl),
      navigationMapState = navigationMapState,
      viewModel = viewModel,
      config = VisualNavigationViewConfig.Default().withSpeedLimitStyle(SignageStyle.MUTCD),
      routeOverlayBuilder = routeOverlayBuilder,
      views =
          NavigationViewComponentBuilder.Default()
              .withCustomOverlayView(
                  customOverlayView = { modifier ->
                    NotNavigatingOverlay(
                        modifier = modifier,
                        viewModel = viewModel,
                        navigationMapState = navigationMapState,
                        onTopOverlayBottomChanged = { destinationPreviewTopPaddingPx = it },
                        dismissSearchTrigger = dismissSearchTrigger,
                    )
                  },
              ),
      onTapExit = { viewModel.stopNavigation() },
      onMapClick = { position, _ ->
        if (sceneState.regionSelection.isActive) {
          // In region-selection mode, taps place the bounding-box corners. Consume the event so
          // the tap doesn't also drop a destination pin or pan-select anything underneath.
          viewModel.addRegionCorner(position)
          NavigationMapClickResult.Consume
        } else {
          // Tapping the map (i.e. outside the search bar) collapses the autocomplete search
          // dropdown and dismisses the keyboard. Pass the event through so normal map interaction
          // is unaffected.
          dismissSearchTrigger++
          focusManager.clearFocus()
          NavigationMapClickResult.Pass
        }
      },
      onMapLongClick = { position, screenPosition ->
        Log.d(
            "DemoNavigationScene",
            "Long press at lat=${position.lat}, lng=${position.lng}, screen=$screenPosition",
        )
        viewModel.selectDestination(position)
        NavigationMapClickResult.Consume
      },
      mapOptions =
          MapOptions(
              ornamentOptions =
                  OrnamentOptions(
                      isCompassEnabled = false,
                      isScaleBarEnabled = false,
                  ),
          ),
  ) {
    DemoDroppedPinOverlay(sceneState.droppedPin)
    DemoRegionSelectionOverlay(sceneState.regionSelection)
    DemoTileCacheOverlay(sceneState.showTileCacheOverlay, tiles)
  }

  if (sceneState.regionSelection.isSheetVisible) {
    sceneState.regionSelection.bounds?.let { bounds ->
      RegionSelectionBottomSheet(
          bounds = bounds,
          onDownload = { viewModel.downloadSelectedRegion(context) },
          onCancel = { viewModel.clearRegionSelection() },
          downloadProgress = sceneState.regionDownload,
      )
    }
  }

  if (sceneState.isDestinationSheetVisible) {
    sceneState.selectedDestination?.let { destination ->
      DestinationSelectionBottomSheet(
          destination = destination,
          onClose = { viewModel.clearSelectedDestination() },
          onStartNavigation = { viewModel.startSelectedDestinationNavigation() },
          onSimulateNavigation = { viewModel.startSimulatedNavigationForSelectedDestination() },
          onSelect =  { tileLevel: Int -> viewModel.setTileHierarchyLevelNavigation(tileLevel) },
          selectedOption = viewModel.sceneState.value.tileLevelNavigation,
          onSheetHeightChanged = viewModel::setDestinationSheetHeight,
      )
    }
  }

  if (sceneState.isNoRouteFoundDialogVisible) {
    RouteAlertDialog(
        onDismiss = { viewModel.dismissNoRouteFoundDialog() }
    )
  }
}

@Composable
@MaplibreComposable
private fun DemoDroppedPinOverlay(droppedPin: GeographicCoordinate?) {
  val pinFeatureCollection = droppedPinFeatureCollectionOrNull(droppedPin) ?: return
  val pointSource = rememberGeoJsonSource(GeoJsonData.Features(pinFeatureCollection))

  CircleLayer(
      id = "demo-dropped-pin",
      source = pointSource,
      color = const(Color.Red),
      radius = const(10.dp),
      strokeColor = const(Color.White),
      strokeWidth = const(2.dp),
  )
}

/**
 * Renders the in-progress offline-region selection: a translucent filled rectangle for the chosen
 * area (once both corners are placed) plus a dot at each tapped corner. Renders nothing when no
 * corner has been placed yet.
 */
@Composable
@MaplibreComposable
private fun DemoRegionSelectionOverlay(selection: RegionSelection) {
  selection.bounds?.let { bounds ->
    val boxSource =
        rememberGeoJsonSource(GeoJsonData.Features(FeatureCollection(regionBoxFeature(bounds))))
    FillLayer(
        id = "demo-region-box-fill",
        source = boxSource,
        color = const(Color(0x333583DD)),
        outlineColor = const(Color(0xFF3583DD)),
    )
  }

  val corners = listOfNotNull(selection.firstCorner, selection.secondCorner)
  if (corners.isEmpty()) return

  val cornerSource =
      rememberGeoJsonSource(
          GeoJsonData.Features(
              FeatureCollection(
                  corners.map {
                    Feature(
                        geometry = Point(longitude = it.lng, latitude = it.lat),
                        properties = buildJsonObject {},
                    )
                  }
              )
          )
      )
  CircleLayer(
      id = "demo-region-corners",
      source = cornerSource,
      color = const(Color(0xFF3583DD)),
      radius = const(8.dp),
      strokeColor = const(Color.White),
      strokeWidth = const(2.dp),
  )
}

/**
 * Renders a colored overlay of the Valhalla routing tiles currently cached on disk in `tile_dir`,
 * one fill layer per tile hierarchy level (0 = highway, 1 = arterial, 2 = local) so the levels are
 * visually distinguishable. Tiles are scanned from disk each time the overlay is shown.
 */
@Composable
@MaplibreComposable
fun DemoTileCacheOverlay(displayOverlay: Boolean, tiles: List<CachedTile>) {
  if (displayOverlay) {
    val levelFillColors =
        mapOf(0 to Color(0x40FD8178), 1 to Color(0x3EFFED8C), 2 to Color(0x3E008DF1))
    val levelOutlineColors =
        mapOf(0 to Color(0x40FD8178), 1 to Color(0x3EFFD830), 2 to Color(0x3E008DF1))

    levelFillColors.keys.forEach { level ->
      val levelTiles = tiles.filter { it.level == level }
      val source =
          rememberGeoJsonSource(
              GeoJsonData.Features(FeatureCollection(levelTiles.map { regionBoxFeature(it.bounds) }))
          )
      FillLayer(
          id = "demo-tile-cache-level-$level",
          source = source,
          color = const(levelFillColors.getValue(level)),
          outlineColor = const(levelOutlineColors.getValue(level)),
      )
    }
  }
}

/**
 * Builds a closed rectangle [Feature] (a GeoJSON ring) spanning the given bounds.
 *
 * GeoJSON rings are ordered [lng, lat] and must be closed (first position == last).
 */
private fun regionBoxFeature(bounds: RegionBoundingBox) =
    Feature(
        geometry =
            Polygon(
                listOf(
                    Position(longitude = bounds.minLon, latitude = bounds.minLat),
                    Position(longitude = bounds.maxLon, latitude = bounds.minLat),
                    Position(longitude = bounds.maxLon, latitude = bounds.maxLat),
                    Position(longitude = bounds.minLon, latitude = bounds.maxLat),
                    Position(longitude = bounds.minLon, latitude = bounds.minLat),
                )
            ),
        properties = buildJsonObject {},
    )

internal fun droppedPinFeatureCollectionOrNull(pin: GeographicCoordinate?) = pin?.let {
  droppedPinFeatureCollection(it)
}

internal fun droppedPinFeatureCollection(pin: GeographicCoordinate) =
    FeatureCollection(
        Feature(
            geometry = Point(longitude = pin.lng, latitude = pin.lat),
            properties = buildJsonObject {},
        ),
    )
