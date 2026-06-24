package com.valhalla.valhalla

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.api.models.CostingModel
import com.valhalla.api.models.RouteRequest
import com.valhalla.api.models.RoutingWaypoint
import com.valhalla.config.ValhallaConfigBuilder
import com.valhalla.valhalla.config.ValhallaConfigManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the prebuilt native library can route from a loose `tile_dir` (not just a
 * `tile_extract` tar). This is the foundation of the offline region-subsetting feature, which writes
 * a directory of `.gph` tiles and points Valhalla at it via [ValhallaConfigBuilder.withTileDir].
 */
@RunWith(AndroidJUnit4::class)
class ValhallaTileDirTest {

  private lateinit var appContext: Context
  private lateinit var valhalla: Valhalla

  @Before
  fun setUp() {
    appContext = InstrumentationRegistry.getInstrumentation().targetContext

    // The fixture ships an unpacked tile directory (0/, 1/, 2/ ... *.gph) alongside the tar.
    val tileDir = File(appContext.filesDir, "test_tile_dir")
    if (tileDir.exists()) tileDir.deleteRecursively()
    copyAsset(appContext, "valhalla_tiles", tileDir)

    val config = ValhallaConfigBuilder().withTileDir(tileDir.absolutePath).build()
    valhalla = Valhalla(appContext, config, ValhallaConfigManager(appContext))
  }

  @Test
  fun routesFromTileDir() {
    val request =
        RouteRequest(
            locations =
                listOf(
                    RoutingWaypoint(lat = 42.5063, lon = 1.5218),
                    RoutingWaypoint(lat = 42.5086, lon = 1.5394)),
            costing = CostingModel.auto)

    when (val response = valhalla.route(request)) {
      is ValhallaResponse.Json -> assertEquals(0, response.jsonResponse.trip.status)
      is ValhallaResponse.Osrm -> fail("format should not be osrm")
    }
  }

  /** Recursively copy an asset file or directory tree into [dest]. */
  private fun copyAsset(context: Context, assetPath: String, dest: File) {
    val children = context.assets.list(assetPath) ?: emptyArray()
    if (children.isEmpty()) {
      dest.parentFile?.mkdirs()
      context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
    } else {
      dest.mkdirs()
      for (child in children) {
        copyAsset(context, "$assetPath/$child", File(dest, child))
      }
    }
  }
}
