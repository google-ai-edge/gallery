package androidx.compose.ui.viewinterop

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.awt.SwingPanel

@Composable
fun <T : View> AndroidView(
  factory: (Context) -> T,
  modifier: Modifier = Modifier,
  update: (T) -> Unit = {},
  onRelease: (T) -> Unit = {},
) {
  val context = LocalContext.current
  val view = remember { factory(context) }
  DisposableEffect(view) {
    onDispose {
      onRelease(view)
    }
  }
  update(view)
  SwingPanel(
    factory = { view.component },
    modifier = modifier
  )
}
