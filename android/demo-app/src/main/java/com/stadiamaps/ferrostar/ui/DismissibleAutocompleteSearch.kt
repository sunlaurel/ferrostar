package com.stadiamaps.ferrostar.ui

import android.content.res.Resources
import android.location.Location
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stadiamaps.api.GeocodingApi
import com.stadiamaps.api.auth.ApiKeyAuth
import com.stadiamaps.api.infrastructure.ApiClient
import com.stadiamaps.api.infrastructure.ApiClient.Companion.defaultBasePath
import com.stadiamaps.api.models.FeaturePropertiesV2
import com.stadiamaps.api.models.LayerId
import com.stadiamaps.autocomplete.AutoCompleteSearchBar
import com.stadiamaps.autocomplete.AutoCompleteViewModel
import com.stadiamaps.autocomplete.SearchResult
import com.stadiamaps.autocomplete.SuggestionsDropdown
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * A drop-in replacement for the library's `AutocompleteSearch` that can be collapsed by the parent.
 *
 * The upstream `com.stadiamaps.autocomplete.AutocompleteSearch` keeps its [AutoCompleteViewModel]
 * (and therefore its expanded/`isActive` state) entirely private, with no dismiss hook — and the
 * underlying M3 `DockedSearchBar` does not collapse on focus loss. To support "tap outside to close
 * the dropdown", this fork owns the view model and collapses it whenever [dismissTrigger] changes.
 *
 * This mirrors the upstream composable closely (same service setup and result rendering) so behavior
 * is otherwise identical. If the upstream library later exposes a dismiss/active hook, prefer that
 * and delete this fork.
 *
 * @param dismissTrigger Increment this from the parent (e.g. on an outside tap) to collapse the
 *   dropdown. Any change to a value greater than zero triggers a collapse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DismissibleAutocompleteSearch(
    apiKey: String,
    modifier: Modifier = Modifier,
    useEuEndpoint: Boolean = false,
    userLocation: Location? = null,
    limitLayers: List<LayerId>? = null,
    minSearchLength: Int = 1,
    debounceInterval: Long = 300,
    dismissTrigger: Int = 0,
    resultView: @Composable ((FeaturePropertiesV2, Modifier) -> Unit)? = null,
    onFeatureClicked: (FeaturePropertiesV2) -> Unit = {},
) {
  val service =
      remember(apiKey, useEuEndpoint) {
        val baseUrl = if (useEuEndpoint) "https://api-eu.stadiamaps.com" else defaultBasePath
        val client =
            ApiClient(
                baseUrl = baseUrl,
                okHttpClientBuilder =
                    OkHttpClient.Builder()
                        .addInterceptor(
                            Interceptor { chain: Interceptor.Chain ->
                              val original = chain.request()
                              val newRequest =
                                  original
                                      .newBuilder()
                                      .header(
                                          "Accept-Language",
                                          Resources.getSystem()
                                              .configuration
                                              .locales
                                              .toLanguageTags())
                                      .build()
                              chain.proceed(newRequest)
                            }))
        client.addAuthorization("ApiKeyAuth", ApiKeyAuth("query", "api_key", apiKey))
        client.createService(GeocodingApi::class.java)
      }

  val viewModel: AutoCompleteViewModel = viewModel { AutoCompleteViewModel(service) }
  val coroutineScope = rememberCoroutineScope()

  viewModel.userLocation = userLocation
  viewModel.minSearchLength = minSearchLength
  viewModel.debounceInterval = debounceInterval
  viewModel.limitLayers = limitLayers

  val query by viewModel.query.collectAsState()
  val suggestions by viewModel.suggestions.collectAsState()
  val isActive by viewModel.isActive.collectAsState()
  val isLoading by viewModel.isLoading.collectAsState()

  // Collapse the dropdown when the parent signals an outside tap.
  LaunchedEffect(dismissTrigger) {
    if (dismissTrigger > 0) viewModel.onActiveChange(false)
  }

  Column(modifier = modifier) {
    AutoCompleteSearchBar(
        query = query.first,
        onQueryChanged = viewModel::onQueryChanged,
        onSearch = viewModel::onSearch,
        active = isActive,
        onActiveChange = viewModel::onActiveChange,
    ) {
      if (isActive) {
        SuggestionsDropdown(
            suggestions = suggestions,
            resultView = { feature ->
              val clickModifier =
                  Modifier.clickable {
                    coroutineScope.launch {
                      val detailFeature = viewModel.onFeatureClicked(feature)
                      onFeatureClicked(detailFeature)
                    }
                  }

              if (resultView != null) {
                resultView(feature, clickModifier)
              } else {
                SearchResult(feature, modifier = clickModifier, relativeTo = userLocation)
              }
            },
            isLoading = isLoading,
        )
      }
    }
  }
}
