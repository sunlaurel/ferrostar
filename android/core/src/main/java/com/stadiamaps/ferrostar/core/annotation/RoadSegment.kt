package com.stadiamaps.ferrostar.core.annotation

import android.util.Log
import com.stadiamaps.ferrostar.core.http.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.RouteRequest

private const val TAG = "RoadSegment"
private val json = Json { ignoreUnknownKeys = true }

/** A contiguous run of route geometry that shares a single tile hierarchy [level]. */
data class RoadSegment(
    val points: List<GeographicCoordinate>,
    val level: Int,
)

fun String.toHierarchyLevel(): Int =
    when (this) {
      "motorway",
      "trunk",
      "primary" -> 0
      "secondary",
      "tertiary" -> 1
      else -> 2 // unclassified, residential, service_other
    }

/**
 * Fetches per-edge road classification for a route's [geometry] via Valhalla's
 * `/trace_attributes` endpoint, returning contiguous [RoadSegment]s each tagged with a tile
 * hierarchy level (0 = highway, 1 = arterial, 2 = local).
 */
suspend fun fetchRoadSegments(
    httpClient: HttpClientProvider,
    traceURL: String,
    geometry: List<GeographicCoordinate>,
    profile: String,
): List<RoadSegment> =
    withContext(Dispatchers.IO) {
      // trace_attributes needs at least a start and end point to form an edge.
      if (geometry.size < 2) return@withContext emptyList()

      val body =
          buildJsonObject {
            putJsonArray("shape") {
              geometry.forEach { coord ->
                addJsonObject {
                  put("lat", coord.lat)
                  put("lon", coord.lng)
                }
              }
            }
            put("costing", profile)
            // edge_walk follows the supplied shape exactly, so returned shape indices map back
            // onto `geometry` with no re-matching offset.
            put("shape_match", "edge_walk")
            putJsonObject("filters") {
              put("action", "include")
              putJsonArray("attributes") {
                add("edge.road_class")
                add("edge.begin_shape_index")
                add("edge.end_shape_index")
              }
            }
          }

      val request =
          RouteRequest.HttpPost(
              traceURL,
              mapOf("Content-Type" to "application/json"),
              body.toString().toByteArray(),
          )

      val response = httpClient.call(request)
      // Read the body exactly once — bodyBytes() consumes the underlying stream.
      val bytes = response.bodyBytes()
      if (!response.isSuccessful || bytes == null) {
        Log.e(TAG, "trace_attributes failed: HTTP ${response.code}")
        return@withContext emptyList()
      }

      try {
        val edges =
            json.parseToJsonElement(String(bytes)).jsonObject["edges"]?.jsonArray
                ?: run {
                  Log.w(TAG, "trace_attributes returned no edges")
                  return@withContext emptyList()
                }

        edges.mapNotNull { element ->
          val edge = element.jsonObject
          val roadClass = edge["road_class"]?.jsonPrimitive?.contentOrNull
          val begin = edge["begin_shape_index"]?.jsonPrimitive?.intOrNull
          val end = edge["end_shape_index"]?.jsonPrimitive?.intOrNull

          // Skip (rather than abort) any edge missing fields or with an out-of-range span.
          // `end` is the inclusive index of the edge's last shape point.
          if (roadClass == null || begin == null || end == null) return@mapNotNull null
          if (begin < 0 || end >= geometry.size || begin >= end) {
            Log.w(
                TAG,
                "Skipping edge with out-of-range indices [$begin..$end] (size=${geometry.size})",
            )
            return@mapNotNull null
          }

          RoadSegment(
              // Copy so the segment doesn't retain a view into `geometry`.
              points = geometry.subList(begin, end + 1).toList(),
              level = roadClass.toHierarchyLevel(),
          )
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse trace_attributes edges", e)
        emptyList()
      }
    }
