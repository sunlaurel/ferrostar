package com.valhalla.valhalla.tiles

/**
 * Geographic bounding box in WGS84 degrees.
 *
 * Note that this is a simple axis-aligned box in lat/lng space; it does not handle antimeridian
 * crossing. Valhalla's tile grid is itself a plain equirectangular degree grid, so for the purpose
 * of selecting tiles this representation matches the engine's own model.
 */
data class GeoBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
  /** True if this box shares any area (or edge) with [other]. */
  fun intersects(other: GeoBounds): Boolean =
      minLon <= other.maxLon &&
          maxLon >= other.minLon &&
          minLat <= other.maxLat &&
          maxLat >= other.minLat

  /** Expand the box by [degrees] on every side, clamped to valid lat/lng ranges. */
  fun buffered(degrees: Double): GeoBounds =
      GeoBounds(
          minLat = (minLat - degrees).coerceAtLeast(-90.0),
          minLon = (minLon - degrees).coerceAtLeast(-180.0),
          maxLat = (maxLat + degrees).coerceAtMost(90.0),
          maxLon = (maxLon + degrees).coerceAtMost(180.0),
      )
}

/** A Valhalla graph tile identified by its hierarchy [level] and [tileId]. */
data class Tile(val level: Int, val tileId: Int)

/**
 * Maps between Valhalla graph tile identities and geographic bounds.
 *
 * Valhalla lays tiles out on a plain equirectangular degree grid, row-major, with the origin at the
 * southwest corner (-180, -90). The grid resolution depends on the hierarchy level:
 *
 * | Level | Tile size | Road class      |
 * |-------|-----------|-----------------|
 * | 0     | 4.0°      | highway/primary |
 * | 1     | 1.0°      | arterial        |
 * | 2     | 0.25°     | local           |
 *
 * On disk (and inside a `tile_extract` tar) a tile is stored at the path
 * `{level}/{ddd}/{ddd}[/{ddd}].gph`, where the `{ddd}` segments are the zero-padded, 3-digit groups
 * of the tile id. For example `2/000/762/486.gph` is level 2, tile id 762486.
 *
 * This mirrors `valhalla::midgard::Tiles<PointLL>`, `valhalla::baldr::TileHierarchy`, and
 * `GraphTile::FileSuffix` in the Valhalla source. The math here is verified against real fixture
 * tiles (e.g. the level-2 tile 762486 covers lon [1.5, 1.75], lat [42.25, 42.5]).
 */
object TileGrid {

  /** The width/height, in degrees, of a tile at the given hierarchy [level], or null if unsupported. */
  fun tileSizeDegrees(level: Int): Double? =
      when (level) {
        0 -> 4.0
        1 -> 1.0
        2 -> 0.25
        else -> null // level 3 (transit) and beyond are not part of the routing extract
      }

  /** The number of columns (tiles spanning 360° of longitude) at the given [level]. */
  fun columns(level: Int): Int? = tileSizeDegrees(level)?.let { Math.round(360.0 / it).toInt() }

  /** The geographic bounds covered by [tile], or null if its level is unsupported. */
  fun boundsOf(tile: Tile): GeoBounds? {
    val size = tileSizeDegrees(tile.level) ?: return null
    val ncols = columns(tile.level) ?: return null
    val row = tile.tileId / ncols
    val col = tile.tileId % ncols
    val minLon = -180.0 + col * size
    val minLat = -90.0 + row * size
    return GeoBounds(minLat = minLat, minLon = minLon, maxLat = minLat + size, maxLon = minLon + size)
  }

  /**
   * Parse a tile archive/file path (e.g. `2/000/762/486.gph`, optionally prefixed with `./`) into a
   * [Tile]. Returns null if [path] is not a `.gph` tile path with a supported level.
   */
  fun tileFromPath(path: String): Tile? {
    val normalized = path.removePrefix("./").trim('/')
    if (!normalized.endsWith(".gph")) return null
    val segments = normalized.removeSuffix(".gph").split('/')
    if (segments.size < 2) return null
    val level = segments.first().toIntOrNull() ?: return null
    if (tileSizeDegrees(level) == null) return null
    // The remaining 3-digit groups concatenate to the tile id, e.g. ["000","762","486"] -> 762486.
    val digits = segments.drop(1).joinToString("")
    val tileId = digits.toIntOrNull() ?: return null
    return Tile(level, tileId)
  }
}
