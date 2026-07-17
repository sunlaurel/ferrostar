package com.stadiamaps.ferrostar.core.valhalla

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.valhalla.config.models.ValhallaConfig
import java.lang.reflect.Type

/**
 * Moshi factory whose [ValhallaConfig] adapter merges `mjolnir.tile_url` / `tile_url_gz` (and,
 * unless disabled, `loki.use_connectivity`) into the serialized config. It uses a private plain
 * Moshi for the delegate serialization and the map round-trip so it never re-enters itself
 * through the outer Moshi it is registered on.
 *
 * @param disableConnectivityMap Whether to force `loki.use_connectivity` to `false`. Valhalla's
 *   connectivity map (`connectivity_map_t` / `Tiles::ColorMap`) only colors tiles already present
 *   on disk -- it has no notion that a missing tile could still be fetched over `tile_url`. Left
 *   enabled in on-demand mode, two locations whose bridging tiles haven't been downloaded yet look
 *   "unconnected" and `/route` throws error 170 before the engine's own tile-fetching search ever
 *   runs. Defaults to `true`, mirroring `ValhallaConfig.swift`'s `loki.useConnectivity = false` for
 *   this exact scenario. There is no Kotlin `ValhallaConfigBuilder`/`ValhallaConfig.Loki` to set
 *   this on directly -- both are compiled-only classes from the published `valhalla-mobile`
 *   artifact -- so it's injected here at serialization time instead, same as `tile_url` above.
 */
class TileUrlConfigInjectorFactory(
    private val tileUrl: String,
    private val tileUrlGz: Boolean,
    private val disableConnectivityMap: Boolean = true,
) : JsonAdapter.Factory {

  private val plain: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
  private val mapType: Type =
      Types.newParameterizedType(MutableMap::class.java, String::class.java, Any::class.java)
  private val mapAdapter: JsonAdapter<MutableMap<String, Any?>> = plain.adapter(mapType)

  override fun create(
      type: Type,
      annotations: MutableSet<out Annotation>,
      moshi: Moshi,
  ): JsonAdapter<*>? {
    if (Types.getRawType(type) != ValhallaConfig::class.java) return null
    val configAdapter = plain.adapter(ValhallaConfig::class.java)
    return object : JsonAdapter<ValhallaConfig>() {
      override fun toJson(writer: JsonWriter, value: ValhallaConfig?) {
        val map = mapAdapter.fromJson(configAdapter.toJson(value)) ?: mutableMapOf()
        @Suppress("UNCHECKED_CAST")
        val mjolnir =
            (map["mjolnir"] as? Map<String, Any?>)?.let { HashMap(it) } ?: HashMap<String, Any?>()
        mjolnir["tile_url"] = tileUrl
        mjolnir["tile_url_gz"] = tileUrlGz
        map["mjolnir"] = mjolnir

        if (disableConnectivityMap) {
          @Suppress("UNCHECKED_CAST")
          val loki =
              (map["loki"] as? Map<String, Any?>)?.let { HashMap(it) } ?: HashMap<String, Any?>()
          loki["use_connectivity"] = false
          map["loki"] = loki
        }

        mapAdapter.toJson(writer, map)
      }

      override fun fromJson(reader: JsonReader): ValhallaConfig? = configAdapter.fromJson(reader)
    }
  }
}
