package com.stadiamaps.ferrostar

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.stadiamaps.ferrostar.core.annotation.RoadSegment
import com.stadiamaps.ferrostar.core.annotation.Speed
import com.stadiamaps.ferrostar.core.annotation.SpeedUnit
import com.stadiamaps.ferrostar.core.annotation.valhalla.ValhallaOSRMExtendedAnnotation
import com.stadiamaps.ferrostar.maplibreui.routeline.BorderedPolyline
import kotlinx.serialization.json.Json
import org.maplibre.compose.util.MaplibreComposable
import uniffi.ferrostar.RouteStep

private const val TAG = "ColoredRouteOverlay"

val HIGHWAY_COLOR = Color(0xFF3583DD)
val ARTERIAL_COLOR = Color(0xFFE8832A)
val LOCAL_COLOR = Color(0xFF35A74B)


private val json = Json { ignoreUnknownKeys = true }

/** [DEPRECATED]: Using hardcoded constants for road labels */
private fun speedLimitToHierarchyConversion(limit: Double, unit: SpeedUnit): Int {
  return when (unit) {
    SpeedUnit.KILOMETERS_PER_HOUR -> {
      when {
        limit > 90 -> 0
        limit > 50 -> 1
        else       -> 2
      }
    }
    SpeedUnit.MILES_PER_HOUR -> {
      when {
        limit > 55 -> 0
        limit > 30 -> 1
        else       -> 2
      }
    }

    SpeedUnit.KNOTS -> {
      when {
        limit > 27 -> 0
        limit > 15 -> 1
        else       -> 2
      }
    }
  }
}

/**
 * Derives the Valhalla tile hierarchy level (0, 1, or 2) for this step using
 * the `road_class` field from its per-segment annotations.
 *
 * Requires that the route was requested with `"shape_attributes": ["road_class"]`
 * (set in AppModule.getFerrostarCore). Falls back to level 2 when the field is
 * absent or unparseable.
 *
 * The level is determined by the most frequently occurring road class across all
 * segments in the step — a majority vote avoids misclassification from short
 * connector segments at junctions.
 */
fun RouteStep.hierarchyLevel(): Int {
    val annotations = this.annotations
    if (annotations.isNullOrEmpty()) {
        return 2
    }

    // Log the first annotation so we can see exactly what Valhalla is returning.
    Log.d(TAG, "Sample annotation for '${this.instruction}': ${annotations.firstOrNull()}")

    val levels = annotations.mapNotNull { annotationJson ->
        try {
          val annotation = json.decodeFromString<ValhallaOSRMExtendedAnnotation>(annotationJson)
          // Fall back to speed-based classification.
          annotation.speedLimit?.let { limit ->
            when (limit) {
              is Speed.Value -> speedLimitToHierarchyConversion(limit.value, limit.unit)
              else           -> 2
            }
          }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse annotation: $annotationJson", e)
            null
        }
    }

    if (levels.isEmpty()) return 2

    // Use the most common level across segments (majority vote).
    return levels.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 2
}

/** Maps a hierarchy level (0/1/2) to its display color. */
fun Int.toHierarchyColor(): Color = when (this) {
    0 -> HIGHWAY_COLOR
    1 -> ARTERIAL_COLOR
    else -> LOCAL_COLOR
}


/**
 * Renders the route as per-edge colored polyline segments, each colored by its Valhalla tile
 * hierarchy level:
 *  - Blue   (#3583DD): level 0 — highways (motorway, trunk, primary)
 *  - Orange (#E8832A): level 1 — arterials (secondary, tertiary)
 *  - Green  (#35A74B): level 2 — local roads (residential, unclassified, service)
 *
 * [segments] come from [fetchRoadSegments] (a `/trace_attributes` call). Trace edges are
 * contiguous — one edge's end shape index equals the next edge's begin index — so the drawn
 * polylines join without gaps. Layer IDs are scoped by segment index.
 */
@Composable
@MaplibreComposable
fun ColoredRouteOverlay(segments: List<RoadSegment>) {
    segments.forEachIndexed { index, segment ->
        if (segment.points.size >= 2) {
            BorderedPolyline(
                points = segment.points,
                color = segment.level.toHierarchyColor(),
                borderColor = segment.level.toHierarchyColor(),
                borderOpacity = 0f,
                idPrefix = "ferrostar-segment-$index",
            )
        }
    }
}
