package androidx.compose.ui.platform

import androidx.compose.runtime.compositionLocalOf

class Configuration {
  var screenWidthDp: Int = 1280
  var screenHeightDp: Int = 800
  var orientation: Int = 1
  val locales: List<java.util.Locale> = listOf(java.util.Locale.getDefault())
}

val LocalConfiguration = compositionLocalOf { Configuration() }
