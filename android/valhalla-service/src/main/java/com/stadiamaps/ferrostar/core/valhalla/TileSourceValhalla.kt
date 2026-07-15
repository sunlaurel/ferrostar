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
 * Moshi factory whose [ValhallaConfig] adapter merges `mjolnir.tile_url` / `tile_url_gz` into the
 * serialized config. It uses a private plain Moshi for the delegate serialization and the map
 * round-trip so it never re-enters itself through the outer Moshi it is registered on.
 */
class TileUrlConfigInjectorFactory(
    private val tileUrl: String,
    private val tileUrlGz: Boolean,
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
        mapAdapter.toJson(writer, map)
      }

      override fun fromJson(reader: JsonReader): ValhallaConfig? = configAdapter.fromJson(reader)
    }
  }
}
