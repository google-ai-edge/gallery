package android.app

import android.content.Context
import android.content.Intent
import android.view.Window

open class Activity : Context() {
  var intent: Intent? = null
  open val window: Window = Window()

  companion object {
    const val ACTIVITY_SERVICE = "activity"
    const val RESULT_OK = -1
    const val RESULT_CANCELED = 0
  }
}
