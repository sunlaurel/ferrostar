package com.stadiamaps.ferrostar.core.valhalla

object Config {
  const val TAR_FILE_NAME = "valhalla-tiles.tar"
  const val TILE_DIR = "tile_dir"
//  const val TILE_BASE_URL: String = "http://localhost:8080"
  const val TILE_BASE_URL: String = "http://10.0.2.2:8080"
  const val TILE_ENDPOINT: String = "/tiles"
  const val TILE_PATH: String = "/{tilePath}"

  /** Whether tiles served from [TILE_BASE_URL] are gzip-compressed. */
  const val TILE_URL_GZ: Boolean = false
}
