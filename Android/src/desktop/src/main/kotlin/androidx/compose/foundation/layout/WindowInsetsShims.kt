package androidx.compose.foundation.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

val WindowInsets.Companion.isImeVisible: Boolean
  @Composable
  @ReadOnlyComposable
  get() = false
