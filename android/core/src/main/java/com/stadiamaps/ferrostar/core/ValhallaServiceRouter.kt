package com.stadiamaps.ferrostar.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.stadiamaps.ferrostar.core.service.ValhallaService
import com.valhalla.api.models.CostingModel
import com.valhalla.api.models.RouteRequest as ValhallaRouteRequest
import com.valhalla.api.models.RoutingWaypoint
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import uniffi.ferrostar.Route as FerrostarRoute
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint as FerrostarWaypoint
import uniffi.ferrostar.WaypointKind as FerrostarWaypointKind

const val TAG = "Valhalla Service Router"

class ValhallaServiceRouter(
    private val context: Context
): CustomRouteProvider {

  override suspend fun getRoutes(
      userLocation: UserLocation,
      waypoints: List<FerrostarWaypoint>
  ): List<FerrostarRoute> = suspendCancellableCoroutine { continuation ->

    val intent = Intent(context, ValhallaService::class.java)

    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        Log.i(TAG, "Valhalla service started")
        val localBinder = binder as ValhallaService.LocalBinder
        val service = localBinder.service

        val osmWaypoints = mutableListOf(RoutingWaypoint(userLocation.coordinates.lat, userLocation.coordinates.lng))
        osmWaypoints.addAll(waypoints.map { it.toRoutingWaypoint() })

        val vrr = ValhallaRouteRequest(
            locations = osmWaypoints,
            costing = CostingModel.auto
        )
        val route = service.getRoutes(vrr)

        context.unbindService(this)
        Log.i(TAG, "Valhalla service ended")
        continuation.resume(route)  // ← Properly resume the coroutine
      }

      override fun onServiceDisconnected(name: ComponentName) {
        continuation.resumeWithException(
            Exception("Service disconnected")
        )
      }
    }

    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
  }

  companion object
}

fun FerrostarWaypoint.toRoutingWaypoint(): RoutingWaypoint {
  val coordinate = this.coordinate
  val lat = coordinate.lat
  val lon = coordinate.lng
  val type = when (this.kind) {
    FerrostarWaypointKind.BREAK -> RoutingWaypoint.Type.`break`
    FerrostarWaypointKind.VIA   -> RoutingWaypoint.Type.via
  }

  return RoutingWaypoint(
      lat = lat,
      lon = lon,
      type = type
  )
}
