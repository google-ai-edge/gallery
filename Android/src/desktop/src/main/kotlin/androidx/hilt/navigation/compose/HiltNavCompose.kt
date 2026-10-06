package androidx.hilt.navigation.compose

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.edge.gallery.desktop.DesktopAppModule

@Composable
inline fun <reified VM : ViewModel> hiltViewModel(): VM =
  viewModel(factory = DesktopAppModule.viewModelFactory)
