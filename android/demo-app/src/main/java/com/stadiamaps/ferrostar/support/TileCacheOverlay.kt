package com.stadiamaps.ferrostar.support

import com.stadiamaps.ferrostar.RegionBoundingBox
import java.io.File

/** A single Valhalla routing tile found on disk in `tile_dir`, with its geographic bounds. */
data class CachedTile(
    val level: Int,
    val bounds: RegionBoundingBox,
)

/** Tile size in degrees per Valhalla hierarchy level (0 = highway, 1 = arterial, 2 = local). */
internal val TILE_SIZE_DEGREES_BY_LEVEL = mapOf(0 to 4.0, 1 to 1.0, 2 to 0.25)

/**
 * Scans `tileDir` for cached Valhalla tile files (`*.gph`/`*.gph.gz`) and returns the geographic
 * bounds of each one found, by reversing Valhalla's on-disk path scheme
 * (`<level>/<zero-padded tile id split into 3-digit groups>.gph[.gz]`, see
 * `GraphTile::FileSuffix` in valhalla/src/baldr/graphtile.cc) back into a `(level, tileId)` pair,
 * then a lat/lng bounding box via the standard row-major world tile grid.
 */
fun scanCachedTiles(tileDir: File): List<CachedTile> {
  if (!tileDir.isDirectory) return emptyList()

  return tileDir
      .walkTopDown()
      .filter { it.isFile && (it.name.endsWith(".gph") || it.name.endsWith(".gph.gz")) }
      .mapNotNull { file -> parseCachedTile(tileDir, file) }
      .toList()
}

private fun parseCachedTile(tileDir: File, file: File): CachedTile? {
  val relativeParts = file.relativeTo(tileDir).path.split(File.separatorChar)
  val level = relativeParts.firstOrNull()?.toIntOrNull() ?: return null
  val tileSize = TILE_SIZE_DEGREES_BY_LEVEL[level] ?: return null

  // Everything after the level directory is the zero-padded, 3-digit-grouped tile id, e.g.
  // "000/733/191.gph" -> "000733191" -> 733191.
  val idDigits =
      relativeParts
          .drop(1)
          .joinToString("") { it.removeSuffix(".gph.gz").removeSuffix(".gph") }
  val tileId = idDigits.toLongOrNull() ?: return null

  val nCols = (360.0 / tileSize).toInt()
  val row = tileId / nCols
  val col = tileId % nCols
  val minLon = -180.0 + col * tileSize
  val minLat = -90.0 + row * tileSize

  return CachedTile(
      level = level,
      bounds =
          RegionBoundingBox(
              minLat = minLat,
              minLon = minLon,
              maxLat = minLat + tileSize,
              maxLon = minLon + tileSize,
          ),
  )
}
