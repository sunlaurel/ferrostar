// Cross-process contract for the out-of-process ValhallaService (see android:process on its
// <service> declaration). The request and response are JSON strings rather than AIDL parcelables:
// the Valhalla request/response models are plain Moshi-serializable data classes, so JSON avoids
// having to make each of them Parcelable while keeping the wire format debuggable.
package com.stadiamaps.ferrostar.core.service;

interface IValhallaService {
    // routeRequestJson: a com.valhalla.api.models.RouteRequest encoded as JSON.
    // Returns a RoutesEnvelope (see ValhallaIPC.kt) encoded as JSON, carrying either the resulting
    // Valhalla RouteResponseTrip or a structured error.
    String getRoutes(String routeRequestJson);
}
