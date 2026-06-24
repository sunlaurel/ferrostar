package com.valhalla.valhalla

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.api.models.CostingModel
import com.valhalla.api.models.RouteRequest
import com.valhalla.api.models.RoutingWaypoint
import com.valhalla.config.ValhallaConfigBuilder
import com.valhalla.valhalla.config.ValhallaConfigManager
import com.valhalla.valhalla.files.ValhallaFile
import com.valhalla.valhalla.tiles.GeoBounds
import com.valhalla.valhalla.tiles.RegionTileExtractor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end test of the routing half of region caching against a *real* Valhalla extract (produced
 * by `valhalla_build_extract`/libarchive, not the synthetic tar used in unit tests):
 *
 * 1. [RegionTileExtractor] subsets the fixture `valhalla_tiles.tar` down to an Andorra bbox.
 * 2. The resulting loose `tile_dir` is used to route, confirming the subset is self-consistent.
 */
@RunWith(AndroidJUnit4::class)
class ValhallaRegionSubsetTest {

  private lateinit var appContext: Context
  private lateinit var subsetDir: File

  @Before
  fun setUp() {
    appContext = InstrumentationRegistry.getInstrumentation().targetContext

    // Place the real extract in filesDir, then subset it to a region directory.
    val tar = ValhallaFile.usingAsset(appContext, "valhalla_tiles.tar")
    subsetDir = File(appContext.filesDir, "region_subset")

    val result =
        RegionTileExtractor.extract(
            extractTar = File(tar.absolutePath()),
            bbox = GeoBounds(minLat = 42.4, minLon = 1.4, maxLat = 42.6, maxLon = 1.6),
            outputDir = subsetDir,
            bufferDegrees = 0.5,
        )
    assertTrue("expected to copy at least one tile from the real extract", result.tilesCopied > 0)
  }

  @Test
  fun routesFromSubsettedRegion() {
    val config = ValhallaConfigBuilder().withTileDir(subsetDir.absolutePath).build()
    val valhalla = Valhalla(appContext, config, ValhallaConfigManager(appContext))

    val request =
        RouteRequest(
            locations =
                listOf(
                    RoutingWaypoint(lat = 42.5063, lon = 1.5218),
                    RoutingWaypoint(lat = 42.5086, lon = 1.5394)),
            costing = CostingModel.auto)

    when (val response = valhalla.route(request)) {
      is ValhallaResponse.Json -> assertEquals(0, response.jsonResponse.trip.status)
      is ValhallaResponse.Osrm -> throw AssertionError("format should not be osrm")
    }
  }
}
