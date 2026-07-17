package com.stadiamaps.ferrostar.support

import android.util.Log
import com.stadiamaps.ferrostar.RegionBoundingBox
import java.io.File
import kotlin.math.floor
import kotlin.math.log10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "TilePrefetch"

/** Max concurrent tile downloads within a single hierarchy level. */
private const val MAX_CONCURRENT_FETCHES = 6

/** Progress of an in-flight region tile download, reported after each tile settles. */
data class RegionDownloadProgress(
    val currentLevel: Int,
    val fetchedInLevel: Int,
    val totalInLevel: Int,
    val fetchedTotal: Int,
    val totalAcrossAllLevels: Int,
    val failed: Int,
) {
  val isComplete: Boolean
    get() = fetchedTotal + failed >= totalAcrossAllLevels
}

/**
 * Enumerates the Valhalla routing tiles whose bounding boxes intersect [bounds] at each requested
 * [levels]. This is the forward direction of the tile grid math in [scanCachedTiles]: it converts a
 * lat/lng box into `(level, tileId)` pairs using the same row-major world grid
 * (`tileId = row * nCols + col`) that [parseCachedTile] reverses.
 */
internal fun findTilesInBounds(
    bounds: RegionBoundingBox,
    levels: Set<Int>,
): List<Pair<Int, Long>> {
  val result = mutableListOf<Pair<Int, Long>>()
  for (level in levels) {
    val size = TILE_SIZE_DEGREES_BY_LEVEL[level] ?: continue
    val nCols = (360.0 / size).toInt()
    val nRows = (180.0 / size).toInt()

    val minCol = floor((bounds.minLon + 180.0) / size).toInt().coerceIn(0, nCols - 1)
    val maxCol = floor((bounds.maxLon + 180.0) / size).toInt().coerceIn(0, nCols - 1)
    val minRow = floor((bounds.minLat + 90.0) / size).toInt().coerceIn(0, nRows - 1)
    val maxRow = floor((bounds.maxLat + 90.0) / size).toInt().coerceIn(0, nRows - 1)

    for (row in minRow..maxRow) {
      for (col in minCol..maxCol) {
        result.add(level to (row.toLong() * nCols + col))
      }
    }
  }
  return result
}

/**
 * Builds the relative tile path Valhalla uses on disk by reimplementing the FileSuffix method.
 * Keeping this byte-identical to Valhalla is what lets prefetched files
 * be found by the engine's local-disk lookup instead of re-fetched over HTTP.
 */
internal fun relativeTilePath(level: Int, tileId: Long): String {
  val size =
      TILE_SIZE_DEGREES_BY_LEVEL[level]
          ?: throw IllegalArgumentException("Unknown tile hierarchy level: $level")
  val maxLength = maxTileIdDigits(size)
  val padded = tileId.toString().padStart(maxLength, '0')
  return "$level/" + padded.chunked(3).joinToString("/")
}

/** Digit count for a level's largest tile id, rounded up to a multiple of 3 (matches FileSuffix). */
private fun maxTileIdDigits(tileSize: Double): Int {
  val nCols = (360.0 / tileSize).toInt()
  val nRows = (180.0 / tileSize).toInt()
  val maxId = nCols * nRows - 1
  var len = floor(log10(maxId.toDouble())).toInt() + 1
  if (len % 3 != 0) len += 3 - (len % 3)
  return len
}

/**
 * Downloads every routing tile intersecting [bounds] into [tileDir], one hierarchy level at a time
 * in order (0 = highways, then 1 = arterial roads, then 2 = local roads). Levels are processed strictly
 * sequentially so that an interrupted download still leaves the most structurally important tiles
 * usable; within a level, up to [MAX_CONCURRENT_FETCHES] tiles are fetched in parallel.
 *
 * Tiles already present on disk are skipped. A single tile's failure is recorded and does not abort
 * its level. [onProgress] is invoked (off the main thread) after each tile settles.
 */
suspend fun prefetchRegionTiles(
    tileDir: File,
    bounds: RegionBoundingBox,
    urlTemplate: String,
    gzipped: Boolean,
    client: OkHttpClient,
    levels: List<Int> = listOf(0, 1, 2),
    onProgress: (RegionDownloadProgress) -> Unit = {},
) = coroutineScope {
  val perLevel = levels.map { it to findTilesInBounds(bounds, setOf(it)) }
  val totalAcrossAllLevels = perLevel.sumOf { it.second.size }

  var fetchedTotal = 0
  var failed = 0
  val semaphore = Semaphore(MAX_CONCURRENT_FETCHES)

  for ((level, tiles) in perLevel) {
    var fetchedInLevel = 0
    val progressLock = Mutex()

    // awaitAll here is the per-level barrier: the next level does not start until every tile in
    // this level has been attempted.
    tiles
        .map { (lvl, tileId) ->
          async(Dispatchers.IO) {
            val ok = semaphore.withPermit { fetchTile(tileDir, urlTemplate, gzipped, client, lvl, tileId) }
            progressLock.withLock {
              fetchedInLevel++
              if (ok) fetchedTotal++ else failed++
              onProgress(
                  RegionDownloadProgress(
                      currentLevel = level,
                      fetchedInLevel = fetchedInLevel,
                      totalInLevel = tiles.size,
                      fetchedTotal = fetchedTotal,
                      totalAcrossAllLevels = totalAcrossAllLevels,
                      failed = failed,
                  ))
            }
          }
        }
        .awaitAll()
  }
}

/**
 * Fetches a single tile into [tileDir], returning true on success (or if it already exists). Writes
 * to a temp file then renames so a partial download never looks like a complete cached tile. Bytes
 * are stored exactly as received (no decompression), matching how the engine persists fetched tiles.
 */
private fun fetchTile(
    tileDir: File,
    urlTemplate: String,
    gzipped: Boolean,
    client: OkHttpClient,
    level: Int,
    tileId: Long,
): Boolean {
  val relativePath = relativeTilePath(level, tileId) + if (gzipped) ".gph.gz" else ".gph"
  val dest = File(tileDir, relativePath)
  if (dest.exists()) return true

  val url = urlTemplate.replace("{tilePath}", relativePath)
  return try {
    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
      val body = response.body
      if (!response.isSuccessful || body == null) {
        Log.w(TAG, "Fetch failed for $url: HTTP ${response.code}")
        return false
      }
      dest.parentFile?.mkdirs()
      val tmp = File(dest.parentFile, "${dest.name}.tmp")
      tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
      if (!tmp.renameTo(dest)) {
        tmp.delete()
        return false
      }
      true
    }
  } catch (e: Exception) {
    Log.w(TAG, "Fetch error for $url", e)
    false
  }
}
