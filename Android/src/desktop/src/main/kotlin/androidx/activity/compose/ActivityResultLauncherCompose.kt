package androidx.activity.compose

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

class ManagedActivityResultLauncher<I, O>(
  private val launcher: ActivityResultLauncher<I>,
  val contract: ActivityResultContract<I, O>
) : ActivityResultLauncher<I> {
  override fun launch(input: I) {
    launcher.launch(input)
  }
}

@Composable
fun <I, O> rememberLauncherForActivityResult(
  contract: ActivityResultContract<I, O>,
  onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> {
  val currentOnResult by rememberUpdatedState(onResult)
  return remember(contract) {
    val l = ActivityResultLauncher<I> { input ->
      contract.handle(input, currentOnResult)
    }
    ManagedActivityResultLauncher(l, contract)
  }
}
