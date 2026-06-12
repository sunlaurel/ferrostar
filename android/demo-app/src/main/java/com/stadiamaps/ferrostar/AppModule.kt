package com.stadiamaps.ferrostar

import android.content.Context
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.http.HttpClientProvider
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import com.stadiamaps.ferrostar.core.location.toAndroidLocation
import com.stadiamaps.ferrostar.core.service.FerrostarForegroundServiceManager
import com.stadiamaps.ferrostar.core.service.ForegroundServiceManager
import com.stadiamaps.ferrostar.core.withJsonOptions
import com.stadiamaps.ferrostar.googleplayservices.FusedNavigationLocationProvider
import com.stadiamaps.ferrostar.support.initialSimulatedLocation
import java.time.Duration
import okhttp3.OkHttpClient
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.WellKnownRouteProvider

/**
 * A basic sample of a dependency injection module for the demo app. This is only used to
 * demonstrate and test the basics of FerrostarCore with a dependency injection like stack. In a
 * real app, use your preferred injection system.
 */
object AppModule {
  private const val TAG = "AppModule"

  private lateinit var appContext: Context

  // Here we show examples of how to use Ferrostar with a routing API.
  //
  // See https://stadiamaps.github.io/ferrostar/vendors.html for a list of vendors
  // known to work with Ferrostar.
  //
  // Option 1: Stadia Maps
  //
  // You can get an API key (free for development and evaluation; no credit card required)
  // at client.stadiamaps.com.
  // NOTE: The demo app requires a Stadia Maps API key for the search box to work.
  //
  // Add a line to your local.properties file to enable Stadia Maps:
  // stadiaApiKey=YOUR-API-KEY
  val stadiaApiKey =
      if (BuildConfig.stadiaApiKey.isBlank() || BuildConfig.stadiaApiKey == "null") {
        null
      } else {
        BuildConfig.stadiaApiKey
      }

  val mapStyleUrl: String by lazy {
    if (stadiaApiKey != null)
        "https://tiles.stadiamaps.com/styles/alidade_smooth.json?api_key=$stadiaApiKey"
    else "https://demotiles.maplibre.org/style.json"
  }

  const val ROUTE_PROFILE = "auto"

  fun init(context: Context) {
    appContext = context
  }

  val locationProvider: NavigationLocationProvider by lazy {
    NavigationLocationProvider(
        liveProviding = FusedNavigationLocationProvider(appContext),
        simulatedProvider =
            SimulatedLocationProvider(
                warpFactor = 3u,
                initialLocation = initialSimulatedLocation.toAndroidLocation(),
            ),
    )
  }

  val httpClient: HttpClientProvider by lazy {
    OkHttpClient.Builder().callTimeout(Duration.ofSeconds(15)).build().toOkHttpClientProvider()
  }

  private val foregroundServiceManager: ForegroundServiceManager by lazy {
    FerrostarForegroundServiceManager(appContext, DefaultForegroundNotificationBuilder(appContext))
  }

  val valhallaBaseUrl: String by lazy {
    "http://10.0.2.2:8002"
  }

  val valhallaRouteEndpoint: String by lazy {
    "/route"
  }
  val valhallaTraceAttributesEndpoint: String by lazy {
    "/trace_attributes"
  }

  // Valhalla-based API

  var routeProvider: WellKnownRouteProvider =
      WellKnownRouteProvider.Valhalla("$valhallaBaseUrl$valhallaRouteEndpoint", ROUTE_PROFILE)

  fun getFerrostarCore(): FerrostarCore {
    return FerrostarCore(
        wellKnownRouteProvider = routeProvider,
        httpClient = httpClient,
        locationProvider = locationProvider,
        foregroundServiceManager = foregroundServiceManager,
        navigationControllerConfig = NavigationControllerConfig.demoConfig(),
    )
  }

  fun getFerrostarCore(options: Map<String, Any>? = null): FerrostarCore {
    val newRouteProvider =
        if (options != null) routeProvider.withJsonOptions(options) else routeProvider
    return FerrostarCore(
        wellKnownRouteProvider = newRouteProvider,
        httpClient = httpClient,
        locationProvider = locationProvider,
        foregroundServiceManager = foregroundServiceManager,
        navigationControllerConfig = NavigationControllerConfig.demoConfig(),
    )
  }


//  // Not all navigation apps will require this sort of extra configuration.
//  // In fact, we hope that most don't!
//  // In case you do though, this sample implementation shows what you'll need to get started
//  // (this basically re-implements the default behaviors).
//  core.deviationHandler = RouteDeviationHandler { _, _, remainingWaypoints ->
//    CorrectiveAction.GetNewRoutes(remainingWaypoints)
//  }
//
//  core.alternativeRouteProcessor = AlternativeRouteProcessor { it, routes ->
//    Log.i(TAG, "Received alternate route(s): $routes")
//    if (routes.isNotEmpty()) {
//      // NB: Use `replaceRoute` for cases like this!
//      it.replaceRoute(routes.first())
//    }
//  }

  // The AndroidTtsObserver handles spoken instructions as they are triggered by FerrostarCore.
  val ttsObserver: AndroidTtsObserver by lazy { AndroidTtsObserver(appContext) }

  val viewModel: DemoNavigationViewModel by lazy { DemoNavigationViewModel() }
}
