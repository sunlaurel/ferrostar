package com.stadiamaps.ferrostar.ui.components.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun RouteAlertDialog(
    onDismiss: () -> Unit
) {
  Dialog(
      onDismissRequest = { onDismiss() },
      properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 56.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
      Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          color = Color.Red.copy(alpha = 0.3f),
      ) {
        Column(modifier = Modifier.padding(24.dp)) {
          Text(text = "No route found", color = Color.White)
          Spacer(modifier = Modifier.height(8.dp))
          Text(
              text = "Unable to create a route to your destination",
              color = Color.White,
          )
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.End,
          ) {
            TextButton(onClick = { onDismiss() }) {
              Text("OK", color = Color.White)
            }
          }
        }
      }
    }
  }
}
