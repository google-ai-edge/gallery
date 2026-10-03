package androidx.core.view

object WindowCompat {
  @JvmStatic
  fun setDecorFitsSystemWindows(window: Any?, fit: Boolean) {}

  @JvmStatic
  fun getInsetsController(window: Any?, view: Any?): InsetsController = InsetsController()
}

class InsetsController {
  var isAppearanceLightStatusBars: Boolean = false
}
