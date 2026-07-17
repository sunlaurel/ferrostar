package com.stadiamaps.ferrostar.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.stadiamaps.ferrostar.core.service.IValhallaService
import com.stadiamaps.ferrostar.core.valhalla.ValhallaService
import com.stadiamaps.ferrostar.core.valhalla.decodeRoutesEnvelope
import com.stadiamaps.ferrostar.core.valhalla.valhallaIpcMoshi
import com.valhalla.api.models.CostingModel
import com.valhalla.api.models.RouteManeuver
import com.valhalla.api.models.RouteRequest as ValhallaRouteRequest
import com.valhalla.api.models.RouteResponseTrip
import com.valhalla.api.models.RoutingResponseWaypoint
import com.valhalla.api.models.RoutingWaypoint
import com.valhalla.api.models.ValhallaLongUnits
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import uniffi.ferrostar.BoundingBox
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.Route as FerrostarRoute
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.SpokenInstruction
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent
import uniffi.ferrostar.Waypoint as FerrostarWaypoint
import uniffi.ferrostar.WaypointKind as FerrostarWaypointKind

const val TAG = "[Valhalla Service Router]"
private const val PRE_TRANSITION_TRIGGER_DISTANCE_M = 60.0

/**
 * A [CustomRouteProvider] that generates routes on-device by binding to [ValhallaService], which
 * runs the Valhalla engine in a separate process. The service returns Valhalla's own JSON trip
 * across the [IValhallaService] AIDL boundary; the mapping into Ferrostar's [FerrostarRoute] model
 * happens here, in the caller's process.
 */
class ValhallaServiceRouter(
    private val context: Context,
) : CustomRouteProvider {

  override suspend fun getRoutes(
      userLocation: UserLocation,
      waypoints: List<FerrostarWaypoint>
  ): List<FerrostarRoute> = suspendCancellableCoroutine { continuation ->
    val intent = Intent(context, ValhallaService::class.java)

    val connection =
        object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val serviceConnection = this
            Log.i(TAG, "Valhalla service connected")
            val service = IValhallaService.Stub.asInterface(binder)

            val osmWaypoints =
                mutableListOf(
                    RoutingWaypoint(userLocation.coordinates.lat, userLocation.coordinates.lng))
            osmWaypoints.addAll(waypoints.map { it.toRoutingWaypoint() })

            val request =
                ValhallaRouteRequest(locations = osmWaypoints, costing = CostingModel.auto)

            CoroutineScope(Dispatchers.IO).launch {
              val routes =
                  try {
                    val requestJson =
                        valhallaIpcMoshi.adapter(ValhallaRouteRequest::class.java).toJson(request)
                    decodeRoutesEnvelope(
                        service.getRoutes(requestJson)
                    ).toFerrostarRoutes()
                  } catch (e: Exception) {
                    Log.e(TAG, "Routes weren't able to be fetched", e)
                    emptyList()
                  }

              context.unbindService(serviceConnection)
              Log.i(TAG, "Valhalla service disconnected")
              continuation.resume(routes)
            }
          }

          override fun onServiceDisconnected(name: ComponentName) {
            continuation.resumeWithException(Exception("Service disconnected"))
          }
        }

    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
  }

  companion object
}

fun FerrostarWaypoint.toRoutingWaypoint(): RoutingWaypoint {
  val coordinate = this.coordinate
  val type =
      when (this.kind) {
        FerrostarWaypointKind.BREAK -> RoutingWaypoint.Type.`break`
        FerrostarWaypointKind.VIA -> RoutingWaypoint.Type.via
      }

  return RoutingWaypoint(lat = coordinate.lat, lon = coordinate.lng, type = type)
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

  val steps =
      legs.flatMap { leg ->
        val legCoordinates = leg.shape.decodePolyline6()
        leg.maneuvers.map { it.toRouteStep(legCoordinates, metersPerUnit) }
      }

  return listOf(
      FerrostarRoute(
          geometry = fullGeometry,
          bbox =
              BoundingBox(
                  sw = GeographicCoordinate(lat = summary.minLat, lng = summary.minLon),
                  ne = GeographicCoordinate(lat = summary.maxLat, lng = summary.maxLon),
              ),
          distance = summary.length * metersPerUnit,
          waypoints = locations.map { it.toFerrostarWaypoint() },
          steps = steps,
      ))
}

private fun RoutingResponseWaypoint.toFerrostarWaypoint(): FerrostarWaypoint =
    FerrostarWaypoint(
        coordinate = GeographicCoordinate(lat = lat, lng = lon),
        kind =
            when (type) {
              RoutingResponseWaypoint.Type.via,
              RoutingResponseWaypoint.Type.through -> FerrostarWaypointKind.VIA
              else -> FerrostarWaypointKind.BREAK
            },
    )

private fun RouteManeuver.toRouteStep(
    legCoordinates: List<GeographicCoordinate>,
    metersPerUnit: Double,
): RouteStep {
  val distanceMeters = length * metersPerUnit
  val stepGeometry =
      legCoordinates.subList(
          beginShapeIndex,
          minOf(endShapeIndex + 1, legCoordinates.size),
      )
  val exitNumbers = sign?.exitNumberElements?.map { it.text } ?: emptyList()

  val visualInstruction =
      VisualInstruction(
          primaryContent =
              VisualInstructionContent(
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
      add(
          SpokenInstruction(
              text = it,
              ssml = null,
              triggerDistanceBeforeManeuver = distanceMeters,
              utteranceId = UUID.randomUUID(),
          ))
    }
    verbalPreTransitionInstruction?.let {
      add(
          SpokenInstruction(
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
private fun Int.toManeuverType(): ManeuverType? =
    when (this) {
      1, 2, 3 -> ManeuverType.DEPART
      4, 5, 6 -> ManeuverType.ARRIVE
      7 -> ManeuverType.NEW_NAME
      8 -> ManeuverType.CONTINUE
      9, 10, 11, 12, 13, 14, 15, 16 -> ManeuverType.TURN
      17, 18, 19 -> ManeuverType.ON_RAMP
      20, 21 -> ManeuverType.OFF_RAMP
      22, 23, 24 -> ManeuverType.FORK
      25, 37, 38 -> ManeuverType.MERGE
      26 -> ManeuverType.ROUNDABOUT
      27 -> ManeuverType.EXIT_ROUNDABOUT
      else -> null
    }

// Maps a Valhalla maneuver type code to a Ferrostar ManeuverModifier.
private fun Int.toManeuverModifier(): ManeuverModifier? =
    when (this) {
      0, 1, 4, 7, 8, 17, 22, 25, 26, 27 -> ManeuverModifier.STRAIGHT
      2, 5, 10, 23 -> ManeuverModifier.RIGHT
      3, 6, 15, 24 -> ManeuverModifier.LEFT
      9, 18, 20 -> ManeuverModifier.SLIGHT_RIGHT
      16, 19, 21 -> ManeuverModifier.SLIGHT_LEFT
      11 -> ManeuverModifier.SHARP_RIGHT
      14 -> ManeuverModifier.SHARP_LEFT
      12, 13 -> ManeuverModifier.U_TURN
      37 -> ManeuverModifier.RIGHT
      38 -> ManeuverModifier.LEFT
      else -> null
    }
