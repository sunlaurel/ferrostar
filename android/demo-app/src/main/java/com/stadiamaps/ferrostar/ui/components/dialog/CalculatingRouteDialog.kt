package com.stadiamaps.ferrostar.ui.components.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun CalculatingRouteDialog() {
  Dialog(
      onDismissRequest = {},
      properties =
          DialogProperties(
              usePlatformDefaultWidth = false,
              dismissOnBackPress = false,
              dismissOnClickOutside = false,
          ),
  ) {
    Box(
        modifier = Modifier.fillMaxSize().padding(top = 56.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
      Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          color = Color.DarkGray.copy(alpha = 0.85f),
      ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(
              modifier = Modifier.size(24.dp),
              color = Color.White,
              trackColor = Color.White.copy(alpha = 0.3f),
          )
          Spacer(modifier = Modifier.width(16.dp))
          Text(text = "Calculating route…", color = Color.White)
        }
      }
    }
  }
}
