package com.stadiamaps.ferrostar.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.stadiamaps.ferrostar.core.annotation.RoadSegment
import com.stadiamaps.ferrostar.maplibreui.routeline.BorderedPolyline
import org.maplibre.compose.util.MaplibreComposable

val HIGHWAY_COLOR = Color(0xFF3583DD)
val ARTERIAL_COLOR = Color(0xFFE8832A)
val LOCAL_COLOR = Color(0xFF35A74B)

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
 *  - Orange (#E8832A): level 1 — arterial (secondary, tertiary)
 *  - Green  (#35A74B): level 2 — local roads (residential, unclassified, service)
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
