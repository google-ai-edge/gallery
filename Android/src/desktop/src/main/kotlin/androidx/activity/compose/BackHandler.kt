package androidx.activity.compose

import androidx.compose.runtime.Composable

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
  // On desktop there is no hardware back button. Can be triggered programmatically or via key shortcuts.
}
