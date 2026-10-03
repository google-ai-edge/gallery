package android.view

import android.content.Context

open class ViewGroup(context: Context = Context.INSTANCE) : View(context) {
  open class LayoutParams(var width: Int = 0, var height: Int = 0) {
    companion object {
      const val MATCH_PARENT = -1
      const val WRAP_CONTENT = -2
    }
  }
}
