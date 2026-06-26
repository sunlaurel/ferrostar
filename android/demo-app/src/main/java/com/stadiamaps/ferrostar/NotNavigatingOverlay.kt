package com.stadiamaps.ferrostar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.stadiamaps.autocomplete.center
import com.stadiamaps.ferrostar.composeui.views.components.controls.NavigationUIButton
import com.stadiamaps.ferrostar.composeui.views.components.gridviews.InnerGridView
import com.stadiamaps.ferrostar.core.location.toAndroidLocation
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState
import com.stadiamaps.ferrostar.ui.DismissibleAutocompleteSearch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotNavigatingOverlay(
    modifier: Modifier = Modifier,
    viewModel: DemoNavigationViewModel,
    navigationMapState: NavigationMapState,
    onTopOverlayBottomChanged: (Int) -> Unit = {},
    dismissSearchTrigger: Int = 0,
) {
  val location by viewModel.location.collectAsState()
  val isSimulating by viewModel.simulated.collectAsState()
  val uiState by viewModel.navigationUiState.collectAsState()
  val sceneState by viewModel.sceneState.collectAsState()
  val stadiaApiKey = AppModule.stadiaApiKey

  LaunchedEffect(stadiaApiKey) {
    if (stadiaApiKey == null) {
      onTopOverlayBottomChanged(0)
    }
  }

  if (!uiState.isNavigating()) {
    InnerGridView(
        modifier = modifier.fillMaxSize().padding(bottom = 16.dp, top = 16.dp),
        topCenter = {
          stadiaApiKey?.let { apiKey ->
            Box(
                modifier =
                    Modifier.onGloballyPositioned { coordinates ->
                      onTopOverlayBottomChanged(coordinates.boundsInRoot().bottom.roundToInt())
                    }
            ) {
              DismissibleAutocompleteSearch(
                  apiKey = apiKey,
                  userLocation = location?.toAndroidLocation(),
                  dismissTrigger = dismissSearchTrigger,
              ) { feature ->
                feature.center()?.let { center ->
                  viewModel.selectDestination(
                      location = center,
                      label = feature.properties.name,
                      origin = DestinationSelectionOrigin.SearchResult,
                  )
                }
              }
            }
          }
        },
        centerEnd = {
          Column(
              horizontalAlignment = Alignment.End,
              verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            NavigationUIButton(
                onClick = { navigationMapState.recenter(isNavigating = false) },
                buttonSize = DpSize(48.dp, 48.dp),
            ) {
              Icon(
                  painter = painterResource(R.drawable.my_location_24px),
                  contentDescription = stringResource(R.string.center_on_my_location),
              )
            }

            // Enters/exits offline-region selection mode. Highlighted while active so the user
            // knows their map taps are placing bounding-box corners rather than panning.
            val regionActive = sceneState.regionSelection.isActive
            NavigationUIButton(
                onClick = { viewModel.toggleRegionSelectionMode() },
                buttonSize = DpSize(48.dp, 48.dp),
                containerColor =
                    if (regionActive) MaterialTheme.colorScheme.primary
                    else FloatingActionButtonDefaults.containerColor,
            ) {
              Icon(
                  painter = painterResource(R.drawable.select_region_24px),
                  contentDescription = stringResource(R.string.select_region),
              )
            }
          }
        },
        bottomEnd = {
          Column(modifier = Modifier.padding(bottom = 24.dp), horizontalAlignment = Alignment.End) {
            Button({ viewModel.toggleSimulation() }) {
              val nextLocationText =
                  if (!isSimulating) {
                    stringResource(R.string.set_location_to_simulated)
                  } else {
                    stringResource(R.string.set_location_to_gps)
                  }
              Text(nextLocationText)
            }

            val currentLocationText =
                if (isSimulating) {
                  stringResource(R.string.location_is_simulated)
                } else {
                  stringResource(R.string.location_is_gps)
                }

            Text(
                currentLocationText,
                style =
                    MaterialTheme.typography.titleSmall.copy(
                        color = MaterialTheme.colorScheme.onTertiary,
                        shadow = Shadow(blurRadius = 4.0f),
                    ),
            )
          }
        },
    )
  }
}
