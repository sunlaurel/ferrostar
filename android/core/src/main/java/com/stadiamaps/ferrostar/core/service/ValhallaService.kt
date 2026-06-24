package com.stadiamaps.ferrostar.core.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.valhalla.api.models.RouteManeuver
import com.valhalla.api.models.RouteRequest as ValhallaRouteRequest
import com.valhalla.api.models.RouteResponseTrip
import com.valhalla.api.models.RoutingResponseWaypoint
import com.valhalla.api.models.ValhallaLongUnits
import com.valhalla.config.ValhallaConfigBuilder
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.ValhallaResponse
import com.valhalla.valhalla.files.ValhallaFile
import java.io.File
import uniffi.ferrostar.BoundingBox
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.Route as FerrostarRoute
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.SpokenInstruction
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind
import java.util.UUID

const val FILE_NAME = "norcal-latest.tar"
const val TAG = "[Valhalla Service]"

/**
 * Directory (under `filesDir`) holding a region-scoped subset of routing tiles produced by
 * [com.valhalla.valhalla.tiles.RegionTileExtractor]. When present and non-empty, routing prefers this
 * over the full [FILE_NAME] extract, which lets the large extract be deleted to reclaim space.
 */
const val OFFLINE_TILE_DIR = "offline_tiles"

// How far before a maneuver to trigger the pre-transition spoken instruction, in meters.
private const val PRE_TRANSITION_TRIGGER_DISTANCE_M = 60.0

class ValhallaService : Service() {

  private val binder: Binder = LocalBinder()

  override fun onBind(p0: Intent?): IBinder = binder

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return START_STICKY   // for continuous background tasks
  }

  inner class LocalBinder : Binder() {
    val service: ValhallaService
      get() = this@ValhallaService
  }

  fun getRoutes(rr: ValhallaRouteRequest): List<FerrostarRoute> {
    Log.d(TAG, "current file path: ${this.filesDir}")
    val config = buildTileConfig()
    val valhalla = Valhalla(this, config)
    return when (val response = valhalla.route(rr)) {
      is ValhallaResponse.Osrm -> {
        Log.w(TAG, "OSRM response format is not yet supported by ValhallaService; returning empty list")
        emptyList()
      }
      is ValhallaResponse.Json -> response.jsonResponse.trip.toFerrostarRoutes()
    }
  }

  /**
   * Build the Valhalla config, preferring a region-scoped [OFFLINE_TILE_DIR] of loose tiles when one
   * has been cached, and otherwise falling back to the full [FILE_NAME] `tile_extract`.
   *
   * Using `tile_dir` lets the (large) full extract be deleted once a region has been cached, while
   * still routing fully offline within that region.
   */
  private fun buildTileConfig(): com.valhalla.config.models.ValhallaConfig {
    val offlineDir = File(filesDir, OFFLINE_TILE_DIR)
    if (offlineDir.hasTiles()) {
      Log.d(TAG, "Routing from offline tile_dir: ${offlineDir.absolutePath}")
      return ValhallaConfigBuilder().withTileDir(offlineDir.absolutePath).build()
    }

    val tarFile = ValhallaFile(this, FILE_NAME, filesDir)
    // Note: filesDir is wiped on reinstall, so the source must be (re)placed after each install.
    check(tarFile.exists()) {
      "No routing tiles found: neither a cached '$OFFLINE_TILE_DIR' tile directory nor the full " +
          "extract at ${tarFile.absolutePath()}. Cache a region first, or build tiles with " +
          "valhalla_build_extract and copy the extract into filesDir."
    }
    Log.d(TAG, "Routing from full tile_extract: ${tarFile.absolutePath()}")
    return ValhallaConfigBuilder().withTileExtract(tarFile.absolutePath()).build()
  }

  /** True if this directory exists and contains at least one `.gph` tile. */
  private fun File.hasTiles(): Boolean =
      isDirectory && walkTopDown().any { it.isFile && it.extension == "gph" }
}

private fun RouteResponseTrip.toFerrostarRoutes(): List<FerrostarRoute> {
  val metersPerUnit = if (units == ValhallaLongUnits.miles) 1609.344 else 1000.0

  // Concatenate all leg shapes into one route geometry, dropping the repeated junction point
  // at each leg boundary (the last coord of leg N equals the first coord of leg N+1).
  val fullGeometry = mutableListOf<GeographicCoordinate>()
  for (leg in legs) {
    val legCoordinates = leg.shape.decodePolyline6()
    if (fullGeometry.isEmpty()) fullGeometry.addAll(legCoordinates)
    else fullGeometry.addAll(legCoordinates.drop(1))
  }

  val steps = legs.flatMap { leg ->
    val legCoordinates = leg.shape.decodePolyline6()
    leg.maneuvers.map { it.toRouteStep(legCoordinates, metersPerUnit) }
  }

  return listOf(
    FerrostarRoute(
      geometry = fullGeometry,
      bbox = BoundingBox(
        sw = GeographicCoordinate(lat = summary.minLat, lng = summary.minLon),
        ne = GeographicCoordinate(lat = summary.maxLat, lng = summary.maxLon),
      ),
      distance = summary.length * metersPerUnit,
      waypoints = locations.map { it.toWaypoint() },
      steps = steps,
    )
  )
}

private fun RoutingResponseWaypoint.toWaypoint(): Waypoint = Waypoint(
  coordinate = GeographicCoordinate(lat = lat, lng = lon),
  kind = when (type) {
    RoutingResponseWaypoint.Type.via,
    RoutingResponseWaypoint.Type.through -> WaypointKind.VIA
    else -> WaypointKind.BREAK
  },
)

private fun RouteManeuver.toRouteStep(
    legCoordinates: List<GeographicCoordinate>,
    metersPerUnit: Double,
): RouteStep {
  val distanceMeters = length * metersPerUnit
  val stepGeometry = legCoordinates.subList(
    beginShapeIndex,
    minOf(endShapeIndex + 1, legCoordinates.size),
  )
  val exitNumbers = sign?.exitNumberElements?.map { it.text } ?: emptyList()

  val visualInstruction = VisualInstruction(
    primaryContent = VisualInstructionContent(
      text = instruction,
      maneuverType = type.toManeuverType(),
      maneuverModifier = type.toManeuverModifier(),
      roundaboutExitDegrees = null,
      laneInfo = null,
      exitNumbers = exitNumbers,
    ),
    secondaryContent = null,
    subContent = null,
    triggerDistanceBeforeManeuver = distanceMeters,
  )

  val spokenInstructions = buildList {
    verbalTransitionAlertInstruction?.let {
      add(SpokenInstruction(
        text = it,
        ssml = null,
        triggerDistanceBeforeManeuver = distanceMeters,
        utteranceId = UUID.randomUUID(),
      ))
    }
    verbalPreTransitionInstruction?.let {
      add(SpokenInstruction(
        text = it,
        ssml = null,
        triggerDistanceBeforeManeuver = minOf(distanceMeters, PRE_TRANSITION_TRIGGER_DISTANCE_M),
        utteranceId = UUID.randomUUID(),
      ))
    }
  }

  return RouteStep(
    geometry = stepGeometry,
    distance = distanceMeters,
    duration = time,
    roadName = (beginStreetNames ?: streetNames)?.firstOrNull(),
    exits = exitNumbers,
    instruction = instruction,
    visualInstructions = listOf(visualInstruction),
    spokenInstructions = spokenInstructions,
    annotations = null,
    incidents = emptyList(),
    drivingSide = null,
    roundaboutExitNumber = roundaboutExitCount?.toUByte(),
  )
}

// Decode a Google-style encoded polyline with 6 digits of decimal precision (Valhalla's default).
// Each coordinate pair is delta-encoded and zigzag-encoded into ASCII characters.
private fun String.decodePolyline6(): List<GeographicCoordinate> {
  val coordinates = mutableListOf<GeographicCoordinate>()
  var lat = 0
  var lng = 0
  var i = 0
  while (i < length) {
    var b: Int
    var shift = 0
    var result = 0
    do {
      b = this[i++].code - 63
      result = result or ((b and 0x1f) shl shift)
      shift += 5
    } while (b >= 0x20)
    lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

    shift = 0
    result = 0
    do {
      b = this[i++].code - 63
      result = result or ((b and 0x1f) shl shift)
      shift += 5
    } while (b >= 0x20)
    lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

    coordinates.add(GeographicCoordinate(lat = lat / 1e6, lng = lng / 1e6))
  }
  return coordinates
}

// Maps a Valhalla maneuver type code to a Ferrostar ManeuverType.
// Type codes are documented in the RouteManeuver model (0 = none, 1 = start, ...).
private fun Int.toManeuverType(): ManeuverType? = when (this) {
  1, 2, 3       -> ManeuverType.DEPART
  4, 5, 6       -> ManeuverType.ARRIVE
  7             -> ManeuverType.NEW_NAME
  8             -> ManeuverType.CONTINUE
  9, 10, 11,
  12, 13, 14,
  15, 16        -> ManeuverType.TURN
  17, 18, 19    -> ManeuverType.ON_RAMP
  20, 21        -> ManeuverType.OFF_RAMP
  22, 23, 24    -> ManeuverType.FORK
  25, 37, 38    -> ManeuverType.MERGE
  26            -> ManeuverType.ROUNDABOUT
  27            -> ManeuverType.EXIT_ROUNDABOUT
  else          -> null
}

// Maps a Valhalla maneuver type code to a Ferrostar ManeuverModifier.
private fun Int.toManeuverModifier(): ManeuverModifier? = when (this) {
  0, 1, 4, 7, 8,
  17, 22, 25,
  26, 27        -> ManeuverModifier.STRAIGHT
  2, 5, 10, 23  -> ManeuverModifier.RIGHT
  3, 6, 15, 24  -> ManeuverModifier.LEFT
  9, 18, 20     -> ManeuverModifier.SLIGHT_RIGHT
  16, 19, 21    -> ManeuverModifier.SLIGHT_LEFT
  11            -> ManeuverModifier.SHARP_RIGHT
  14            -> ManeuverModifier.SHARP_LEFT
  12, 13        -> ManeuverModifier.U_TURN
  37            -> ManeuverModifier.RIGHT
  38            -> ManeuverModifier.LEFT
  else          -> null
}
