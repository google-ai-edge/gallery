package com.google.ai.edge.gallery.ui.toggleserver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ToggleServerScreen(
    toggleServerViewModel: ToggleServerViewModel = hiltViewModel()
) {
    val isServerRunning by toggleServerViewModel.isServerRunning.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = { toggleServerViewModel.toggleServer() }) {
            Text(if (isServerRunning) "Stop In-App Server" else "Start In-App Server")
        }
    }
}
