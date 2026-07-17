package com.stadiamaps.ferrostar.ui.components.sheet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.stadiamaps.ferrostar.R
import com.stadiamaps.ferrostar.RegionBoundingBox
import com.stadiamaps.ferrostar.support.RegionDownloadProgress
import java.util.Locale

/**
 * Confirmation sheet shown once the user has drawn an offline-region bounding box. Mirrors
 * [DestinationSelectionBottomSheet]: it previews the selection (here, the box bounds) and offers a
 * primary "download" action plus a cancel that discards the drawn box.
 */
@Composable
fun RegionSelectionBottomSheet(
    bounds: RegionBoundingBox,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    downloadProgress: RegionDownloadProgress? = null,
) {
  Box(
      modifier = Modifier.fillMaxSize().systemBarsPadding(),
      contentAlignment = Alignment.BottomCenter,
  ) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
      RegionSelectionBottomSheetContent(
          bounds = bounds,
          onDownload = onDownload,
          onCancel = onCancel,
          downloadProgress = downloadProgress,
      )
    }
  }
}

@Composable
private fun RegionSelectionBottomSheetContent(
    modifier: Modifier = Modifier,
    bounds: RegionBoundingBox,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    downloadProgress: RegionDownloadProgress? = null,
) {
  val isDownloading = downloadProgress != null
  Column(modifier = modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
    Text(
        text = stringResource(R.string.region_selection_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = stringResource(R.string.region_bounds, formatBounds(bounds)),
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (downloadProgress != null) {
      Text(
          text =
              stringResource(
                  R.string.region_downloading,
                  downloadProgress.currentLevel,
                  downloadProgress.fetchedInLevel,
                  downloadProgress.totalInLevel,
                  downloadProgress.fetchedTotal,
                  downloadProgress.totalAcrossAllLevels,
                  downloadProgress.failed,
              ),
          modifier = Modifier.padding(top = 12.dp),
          style = MaterialTheme.typography.bodyMedium,
      )
      val fraction =
          if (downloadProgress.totalAcrossAllLevels == 0) 0f
          else
              (downloadProgress.fetchedTotal + downloadProgress.failed).toFloat() /
                  downloadProgress.totalAcrossAllLevels
      LinearProgressIndicator(
          progress = { fraction },
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      )
    }
    Button(
        onClick = onDownload,
        enabled = !isDownloading,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
      Text(stringResource(R.string.region_download))
    }
    OutlinedButton(
        onClick = onCancel,
        enabled = !isDownloading,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
    ) {
      Text(stringResource(R.string.region_cancel))
    }
  }
}

private fun formatBounds(bounds: RegionBoundingBox): String =
    String.format(
        Locale.getDefault(),
        "SW %.5f, %.5f → NE %.5f, %.5f",
        bounds.minLat,
        bounds.minLon,
        bounds.maxLat,
        bounds.maxLon,
    )

@Preview(showBackground = true)
@Composable
private fun RegionSelectionBottomSheetContentPreview() {
  MaterialTheme {
    RegionSelectionBottomSheetContent(
        bounds =
            RegionBoundingBox(
                minLat = 37.7045,
                minLon = -122.5274,
                maxLat = 37.8121,
                maxLon = -122.3482,
            ),
        onDownload = {},
        onCancel = {},
    )
  }
}
