package com.stadiamaps.ferrostar.core.valhalla

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.stadiamaps.ferrostar.core.service.IValhallaService
import com.stadiamaps.ferrostar.core.valhalla.Config.TAR_FILE_NAME
import com.stadiamaps.ferrostar.core.valhalla.Config.TILE_BASE_URL
import com.stadiamaps.ferrostar.core.valhalla.Config.TILE_ENDPOINT
import com.stadiamaps.ferrostar.core.valhalla.Config.TILE_PATH
import com.stadiamaps.ferrostar.core.valhalla.Config.TILE_URL_GZ
import com.valhalla.api.models.RouteRequest as ValhallaRouteRequest
import com.valhalla.api.models.RouteResponseTrip
import com.valhalla.config.ValhallaConfigBuilder
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.ValhallaException
import com.valhalla.valhalla.ValhallaResponse
import com.valhalla.valhalla.config.ValhallaConfigManager
import com.valhalla.valhalla.files.ValhallaFile
import java.io.File
const val TAG = "[Valhalla Service]"

/**
 * Bound service that runs the on-device Valhalla routing engine in its own process (see
 * `android:process` on the `<service>` declaration in the demo app's manifest).
 *
 * Because it lives in a separate process, callers reach it only through the [IValhallaService] AIDL
 * interface -- never a same-process `Binder` cast. The engine talks in Valhalla's own JSON models;
 * the mapping to Ferrostar's `uniffi.ferrostar.Route` is done by the client
 * ([com.stadiamaps.ferrostar.core.ValhallaServiceRouter]) so that type never has to cross the wire.
 */
class ValhallaService : Service() {

  private val binder =
      object : IValhallaService.Stub() {
        override fun getRoutes(routeRequestJson: String): String = encodeRoutesEnvelope {
          val request =
              valhallaIpcMoshi.adapter(ValhallaRouteRequest::class.java).fromJson(routeRequestJson)
                  ?: throw ValhallaException.InvalidResponse()
          this@ValhallaService.route(request)
        }
      }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return START_STICKY // for continuous background tasks
  }

  /**
   * Runs a routing request against the engine and returns the resulting trip.
   *
   * @throws ValhallaException.NotSupported if the engine returns an OSRM-format response; the
   *   Ferrostar mapping downstream only understands the native Valhalla JSON trip.
   */
  private fun route(request: ValhallaRouteRequest): RouteResponseTrip {
//    val valhalla = buildValhallaTileExtract()  // <-- used when routing from a tile extract
    val valhalla = buildValhallaTileDir(this)  // <-- used when routing from url
    return when (val response = valhalla.route(request)) {
      is ValhallaResponse.Osrm -> {
        Log.w(TAG, "OSRM response format is not yet supported by ValhallaService")
        throw ValhallaException.NotSupported()
      }
      is ValhallaResponse.Json -> response.jsonResponse.trip
    }
  }

  /** Build the Valhalla config pointing at the full [TAR_FILE_NAME] `tile_extract` in `filesDir`. */
  private fun buildValhallaTileExtract(): Valhalla {
    val tarFile = ValhallaFile(this, TAR_FILE_NAME, resolveParentDir())
    // Note: filesDir is wiped on reinstall, so the extract must be (re)placed after each install.
    check(tarFile.exists()) {
      "No routing tiles found at ${tarFile.absolutePath()}. Build tiles with valhalla_build_extract " +
          "and copy the extract into filesDir (see the demo-app setup instructions)."
    }
    check(File(tarFile.absolutePath()).canRead()) {
      "Routing tiles exist but are unreadable at ${tarFile.absolutePath()}."
    }
    Log.d(TAG, "Routing from full tile_extract: ${tarFile.absolutePath()}")
    val config = ValhallaConfigBuilder().withTileExtract(tarFile.absolutePath()).build()
    return Valhalla(this, config)
  }

  fun buildValhallaTileDir(
      context: Context,
      tileDir: String = Config.TILE_DIR,
  ): Valhalla {
    val cacheDir =
        File(context.filesDir, tileDir).apply {
          check(mkdirs() || isDirectory) { "Unable to create tile_dir at $absolutePath" }
        }

    val config = ValhallaConfigBuilder().withTileDir(cacheDir.absolutePath).build()
    val moshi =
        Moshi.Builder()
            .add(TileUrlConfigInjectorFactory("$TILE_BASE_URL$TILE_ENDPOINT$TILE_PATH", TILE_URL_GZ))
            .add(KotlinJsonAdapterFactory())
            .build()
    return Valhalla(context, config, ValhallaConfigManager(context, moshi = moshi))
  }

  private fun resolveParentDir(): File {
    val internalFile = File(filesDir, TAR_FILE_NAME)
    if (internalFile.exists() && internalFile.canRead()) return filesDir

    val externalFile = getExternalFilesDir(null)?.let { File(it, TAR_FILE_NAME) }
    if (externalFile?.exists() == true && externalFile.canRead()) {
      externalFile.inputStream().use { input ->
        internalFile.outputStream().use { output -> input.copyTo(output) }
      }
    }
    return filesDir
  }
}
