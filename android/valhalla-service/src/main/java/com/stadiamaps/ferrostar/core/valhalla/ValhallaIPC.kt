package com.stadiamaps.ferrostar.core.valhalla

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.valhalla.api.models.RouteResponseTrip
import com.valhalla.valhalla.ErrorResponse
import com.valhalla.valhalla.ValhallaException

/**
 * JSON codec shared by [ValhallaService] (server, runs in the `:valhalla` process) and
 * [com.stadiamaps.ferrostar.core.ValhallaServiceRouter] (client, runs in the caller's process)
 * across the [com.stadiamaps.ferrostar.core.service.IValhallaService] AIDL boundary.
 *
 * The routes envelope carries Valhalla's own [RouteResponseTrip] rather than the uniffi
 * `uniffi.ferrostar.Route`: the latter is generated from Ferrostar's Rust core and pulls in leaf
 * types (Kotlin unsigned ints, `java.util.Date`, ...) that Moshi's reflection adapter can't
 * serialize without hand-registered adapters. [RouteResponseTrip] is a plain OpenAPI-generated
 * model with no such types, so the conversion to `uniffi.ferrostar.Route` happens client-side, in
 * the caller's process, as plain Kotlin -- never over the wire.
 */
internal val valhallaIpcMoshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

/**
 * Stable tags for the [ValhallaException] subtypes carried across the AIDL boundary as data, since
 * AIDL can't marshal arbitrary exception types. [UNKNOWN] covers any non-Valhalla failure (e.g. the
 * missing-tiles [IllegalStateException] from `check(...)`).
 */
internal object ValhallaIpcErrorType {
  const val INTERNAL = "internal"
  const val INVALID_ERROR = "invalidError"
  const val INVALID_RESPONSE = "invalidResponse"
  const val NOT_SUPPORTED = "notSupported"
  const val UNKNOWN = "unknown"
}

internal data class RoutesEnvelope(
    val status: String,
    val trip: RouteResponseTrip? = null,
    val errorType: String? = null,
    val message: String? = null,
) {
  companion object {
    const val OK = "ok"
    const val ERROR = "error"
  }
}

/**
 * Maps a caught exception to the (errorType, message) pair carried in an error envelope.
 *
 * Note: [ValhallaException.Internal] does not expose its [ErrorResponse] (the constructor param is
 * not a property), so only its message -- which already embeds the engine error code via
 * [ErrorResponse.toString] -- survives the crossing; see [envelopeErrorToException].
 */
internal fun Throwable.toIpcError(): Pair<String, String?> =
    when (this) {
      is ValhallaException.Internal -> ValhallaIpcErrorType.INTERNAL to message
      is ValhallaException.InvalidError -> ValhallaIpcErrorType.INVALID_ERROR to message
      is ValhallaException.InvalidResponse -> ValhallaIpcErrorType.INVALID_RESPONSE to message
      is ValhallaException.NotSupported -> ValhallaIpcErrorType.NOT_SUPPORTED to message
      else -> ValhallaIpcErrorType.UNKNOWN to message
    }

/** Reconstructs the exception carried by an error envelope, inverting [toIpcError]. */
internal fun envelopeErrorToException(errorType: String?, message: String?): Exception =
    when (errorType) {
      // The engine error code isn't recoverable across the wire (see toIpcError), so rebuild with a
      // sentinel code; the human-readable message is preserved.
      ValhallaIpcErrorType.INTERNAL ->
          ValhallaException.Internal(ErrorResponse(code = -1, message = message ?: ""))
      ValhallaIpcErrorType.INVALID_ERROR -> ValhallaException.InvalidError()
      ValhallaIpcErrorType.INVALID_RESPONSE -> ValhallaException.InvalidResponse()
      ValhallaIpcErrorType.NOT_SUPPORTED -> ValhallaException.NotSupported()
      else -> RuntimeException(message ?: "Unknown error from Valhalla service")
    }

/** Encodes a [RoutesEnvelope] produced by running [block], mapping any failure into the envelope. */
internal fun encodeRoutesEnvelope(block: () -> RouteResponseTrip): String {
  val envelope =
      try {
        RoutesEnvelope(status = RoutesEnvelope.OK, trip = block())
      } catch (e: Exception) {
        val (errorType, message) = e.toIpcError()
        RoutesEnvelope(status = RoutesEnvelope.ERROR, errorType = errorType, message = message)
      }
  return valhallaIpcMoshi.adapter(RoutesEnvelope::class.java).toJson(envelope)
}

/** Decodes a [RoutesEnvelope] JSON string, throwing the carried exception on an error status. */
internal fun decodeRoutesEnvelope(json: String): RouteResponseTrip {
  val envelope = valhallaIpcMoshi.adapter(RoutesEnvelope::class.java).fromJson(json)
  return when (envelope?.status) {
    RoutesEnvelope.OK -> envelope.trip ?: throw ValhallaException.InvalidResponse()
    else -> throw envelopeErrorToException(envelope?.errorType, envelope?.message)
  }
}
