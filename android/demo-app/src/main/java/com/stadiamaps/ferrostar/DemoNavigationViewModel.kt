package com.stadiamaps.ferrostar

import android.Manifest
import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.viewModelScope
import com.stadiamaps.ferrostar.core.DefaultNavigationViewModel
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.annotation.AnnotationPublisher
import com.stadiamaps.ferrostar.core.annotation.valhalla.valhallaExtendedOSRMAnnotationPublisher
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.toUserLocation
import com.stadiamaps.ferrostar.core.valhalla.Config
import com.stadiamaps.ferrostar.support.RegionDownloadProgress
import com.stadiamaps.ferrostar.support.initialSimulatedLocation
import com.stadiamaps.ferrostar.support.prefetchRegionTiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.TripState
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind

data class DestinationSelection(
    val coordinate: GeographicCoordinate,
    val label: String? = null,
    val origin: DestinationSelectionOrigin = DestinationSelectionOrigin.MapLongPress,
)

enum class DestinationSelectionOrigin {
  MapLongPress,
  SearchResult,
}

data class DemoNavigationSceneState(
    val droppedPin: GeographicCoordinate? = null,
    val selectedDestination: DestinationSelection? = null,
    val isDestinationSheetVisible: Boolean = false,
    val destinationSheetHeightPx: Int = 0,
    val tileLevelNavigation: Int = -1,
    val isNoRouteFoundDialogVisible: Boolean = false,
    val regionSelection: RegionSelection = RegionSelection(),
    val showTileCacheOverlay: Boolean = false,
    val regionDownload: RegionDownloadProgress? = null,
)

/**
 * Tracks the user's in-progress drawing of an offline-region bounding box.
 *
 * The user enters [isActive] selection mode, then taps two opposite corners on the map. Once both
 * corners are placed, [isSheetVisible] becomes true to confirm the download. The corners are stored
 * in tap order; geographic min/max are derived on demand via [bounds] so the box is correct
 * regardless of which corner was tapped first.
 */
data class RegionSelection(
    val isActive: Boolean = false,
    val firstCorner: GeographicCoordinate? = null,
    val secondCorner: GeographicCoordinate? = null,
    val isSheetVisible: Boolean = false,
) {
  /** True once both corners are placed and the box can be previewed/downloaded. */
  val isComplete: Boolean
    get() = firstCorner != null && secondCorner != null

  /** The normalized bounding box (south-west and north-east corners), or null until complete. */
  val bounds: RegionBoundingBox?
    get() {
      val a = firstCorner ?: return null
      val b = secondCorner ?: return null
      return RegionBoundingBox(
          minLat = minOf(a.lat, b.lat),
          minLon = minOf(a.lng, b.lng),
          maxLat = maxOf(a.lat, b.lat),
          maxLon = maxOf(a.lng, b.lng),
      )
    }
}

/** A normalized geographic bounding box with south-west (min) and north-east (max) corners. */
data class RegionBoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DemoNavigationViewModel(
    // This is a simple example, but these would typically be dependency injected
    initialCore: FerrostarCore = AppModule.getFerrostarCore(),
    val locationProvider: NavigationLocationProvider = AppModule.locationProvider,
    annotationPublisher: AnnotationPublisher<*> = valhallaExtendedOSRMAnnotationPublisher(),
) : DefaultNavigationViewModel(initialCore, annotationPublisher) {

  private val _hasLocationPermission = MutableStateFlow(false)

  private val _simulated = MutableStateFlow(false)
  val simulated = _simulated.asStateFlow()

  private val locationStateFlow = MutableStateFlow<UserLocation?>(null)
  val location = locationStateFlow.asStateFlow()

  private val _sceneState = MutableStateFlow(DemoNavigationSceneState())
  val sceneState = _sceneState.asStateFlow()

  private val _ferrostarCore = MutableStateFlow(initialCore)
  val ferrostarCore = _ferrostarCore.asStateFlow()
  override val navigationUiState: StateFlow<NavigationUiState> =
      _ferrostarCore
          .flatMapLatest { core ->
            combine(
                core.state.map { coreState ->
                  NavigationUiState.fromFerrostar(coreState, isMuted = null)
                },
                locationStateFlow,
            ) { uiState, location ->
              if (uiState.isNavigating()) uiState else uiState.copy(location = location)
            }
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(),
              initialValue = NavigationUiState.empty(),
          )

  init {
    viewModelScope.launch {
      _hasLocationPermission
          .flatMapLatest { hasPermission ->
            if (!hasPermission) {
              flowOf(initialSimulatedLocation)
            } else {
              locationProvider.locationUpdates(5000L).map { it.toUserLocation() }
            }
          }
          .collect { locationStateFlow.emit(it) }
    }

    viewModelScope.launch {
      navigationUiState
          .map { it.tripState is TripState.Complete }
          .distinctUntilChanged()
          .collect { hasArrived -> if (hasArrived && simulated.value) stopNavigation() }
    }
  }

  fun setLocationPermissions(permitted: Boolean) {
    _hasLocationPermission.value = permitted
  }

  fun toggleSimulation() {
    _simulated.value = !_simulated.value
    if (!_simulated.value) {
      locationProvider.disableSimulation()
    }
  }

  fun enableAutoDriveSimulation() {
    _simulated.value = true
  }

  fun selectDestination(
      coordinate: GeographicCoordinate,
      label: String? = null,
      origin: DestinationSelectionOrigin = DestinationSelectionOrigin.MapLongPress,
  ) {
    _sceneState.value =
        _sceneState.value.copy(
            droppedPin = coordinate,
            selectedDestination =
                DestinationSelection(coordinate = coordinate, label = label, origin = origin),
            isDestinationSheetVisible = true,
        )
  }

  fun selectDestination(
      location: Location,
      label: String? = null,
      origin: DestinationSelectionOrigin = DestinationSelectionOrigin.MapLongPress,
  ) {
    selectDestination(
        coordinate = GeographicCoordinate(location.latitude, location.longitude),
        label = label,
        origin = origin,
    )
  }

  fun clearSelectedDestination() {
    _sceneState.value =
        _sceneState.value.copy(
            droppedPin = null,
            selectedDestination = null,
            isDestinationSheetVisible = false,
            destinationSheetHeightPx = 0,
        )
  }

  fun hideDestinationSheet() {
    _sceneState.value =
        _sceneState.value.copy(
            isDestinationSheetVisible = false,
            destinationSheetHeightPx = 0,
        )
  }

  fun dismissNoRouteFoundDialog() {
    _sceneState.update { it.copy(isNoRouteFoundDialogVisible = false) }
  }

  /**
   * Toggles offline-region selection mode. Entering always starts from a clean slate (any
   * previously drawn box is discarded); exiting clears the in-progress selection.
   */
  fun toggleRegionSelectionMode() {
    _sceneState.update { current ->
      if (current.regionSelection.isActive) {
        current.copy(regionSelection = RegionSelection())
      } else {
        current.copy(regionSelection = RegionSelection(isActive = true))
      }
    }
  }

  /**
   * Records a tapped corner while in region selection mode. The first tap sets one corner; the
   * second tap sets the opposite corner and reveals the confirmation sheet. Taps are ignored once
   * the box is complete (the user must confirm or cancel before re-drawing). No-ops if selection
   * mode is not active.
   */
  fun addRegionCorner(coordinate: GeographicCoordinate) {
    _sceneState.update { current ->
      val selection = current.regionSelection
      if (!selection.isActive || selection.isComplete) {
        return@update current
      }
      val updated =
          if (selection.firstCorner == null) {
            selection.copy(firstCorner = coordinate)
          } else {
            selection.copy(secondCorner = coordinate, isSheetVisible = true)
          }
      current.copy(regionSelection = updated)
    }
  }

  /** Dismisses the confirmation sheet and clears the drawn box, but stays in selection mode. */
  fun clearRegionSelection() {
    _sceneState.update { it.copy(regionSelection = RegionSelection(isActive = true)) }
  }

  /** Toggles the colored overlay showing which routing tiles are currently cached on disk. */
  fun toggleTileCacheOverlay() {
    _sceneState.update { it.copy(showTileCacheOverlay = !it.showTileCacheOverlay) }
  }

  /**
   * Confirms the drawn region and prefetches every intersecting routing tile into `tile_dir`, one
   * hierarchy level at a time (highways first). Progress is streamed into [DemoNavigationSceneState.regionDownload]
   * so the confirmation sheet can show it; the selection is only cleared once the download settles.
   */
  fun downloadSelectedRegion(context: Context) {
    val bounds = sceneState.value.regionSelection.bounds ?: return
    Log.i(TAG, "Region download requested for bounds: $bounds")

    val tileDir = File(context.filesDir, Config.TILE_DIR)
    val urlTemplate = Config.TILE_BASE_URL + Config.TILE_ENDPOINT + Config.TILE_PATH

    viewModelScope.launch(Dispatchers.IO) {
      prefetchRegionTiles(
          tileDir = tileDir,
          bounds = bounds,
          urlTemplate = urlTemplate,
          gzipped = Config.TILE_URL_GZ,
          client = AppModule.tilePrefetchClient,
      ) { progress ->
        _sceneState.update { it.copy(regionDownload = progress) }
      }
      Log.i(TAG, "Region download complete for bounds: $bounds")
      _sceneState.update { it.copy(regionSelection = RegionSelection(), regionDownload = null) }
    }
  }

  fun setDestinationSheetHeight(heightPx: Int) {
    if (_sceneState.value.destinationSheetHeightPx == heightPx) {
      return
    }
    _sceneState.value = _sceneState.value.copy(destinationSheetHeightPx = heightPx)
  }

  fun startSelectedDestinationNavigation() {
    val destination = sceneState.value.selectedDestination ?: return
    clearSelectedDestination()
    startNavigation(destination.coordinate, destination.label)
  }

  /** Starts navigation to the selected destination using the simulated location provider. */
  fun startSimulatedNavigationForSelectedDestination() {
    enableAutoDriveSimulation()
    startSelectedDestinationNavigation()
  }

  fun setTileHierarchyLevelNavigation(tileLevel: Int) {
    _sceneState.update { currState ->
      currState.copy(tileLevelNavigation = tileLevel)
    }

    Log.d("NavigationViewModel", "The tile hierarchy is set to level: $tileLevel")
  }

  override fun toggleMute() {
    val spokenInstructionObserver = ferrostarCore.value.spokenInstructionObserver
    if (spokenInstructionObserver == null) {
      Log.d("NavigationViewModel", "Spoken instruction observer is null, mute operation ignored.")
      return
    }
    spokenInstructionObserver.setMuted(!spokenInstructionObserver.isMuted)
  }

  fun startNavigation(destination: Location, name: String?) {
    startNavigation(
        destination = GeographicCoordinate(destination.latitude, destination.longitude),
        name = name,
    )
  }

  fun startNavigation(destination: GeographicCoordinate, name: String? = null) {
    viewModelScope.launch(Dispatchers.IO) {
      // TODO: Fail gracefully
      val lastLocation = location.value ?: return@launch

      // TODO: Add label to waypoint?
      // TODO: Assign the destination to the `NavigationManagerBridge`
      Log.d(TAG, "fetching route to $destination with name $name")
      val tileLevel = sceneState.value.tileLevelNavigation
      Log.i(TAG, "========= Starting a route at level $tileLevel =========")

      if (tileLevel == -1) {
        _ferrostarCore.update { AppModule.getFerrostarCore(true) }  // <-- this is for offline routing
      } else {
        // Option 1: using use_highways and use_living_streets (less precise)
        val zoomOptions =
            when (tileLevel) {
              0 -> mapOf("use_highways" to 1, "use_living_streets" to 0)
              1 -> mapOf("use_highways" to 0, "use_living_streets" to 1)
              else -> mapOf("use_highways" to 0, "use_living_streets" to 1)
            }
        val options = mapOf("costing_options" to mapOf("auto" to zoomOptions))
        _ferrostarCore.update { AppModule.getFerrostarCore(options) }
      }

      val routes = ferrostarCore.value.getRoutes(
          lastLocation,
          listOf(
              Waypoint(coordinate = destination, kind = WaypointKind.BREAK),
              ),
          )

      if (routes.isEmpty()) {
        Log.w(TAG, "No routes returned for destination $destination; showing no-route-found dialog")
        _sceneState.update { it.copy(isNoRouteFoundDialogVisible = true) }
        return@launch
      }

      val route = routes.first()

      if (simulated.value) {
        locationProvider.enableSimulationOn(route)
      }

      if (navigationUiState.value.isNavigating()) {
        ferrostarCore.value.replaceRoute(route = route)
      } else {
        ferrostarCore.value.startNavigation(route = route)
      }
    }
  }

  // Checks for current connectivity details
  @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
  fun hasValidConnectionStatus (context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return connectivityManager.activeNetwork != null
  }

  override fun stopNavigation() {
    locationProvider.disableSimulation()
    ferrostarCore.value.stopNavigation()
  }

  companion object {
    const val TAG = "DemoNavigationViewModel"
  }
}
