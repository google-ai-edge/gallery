package android.content.res

import com.google.ai.edge.gallery.R

class Resources {
  fun getString(resId: Int): String = R.getString(resId)

  fun getString(resId: Int, vararg formatArgs: Any): String = R.getString(resId, *formatArgs)

  fun getQuantityString(resId: Int, quantity: Int): String = R.getQuantityString(resId, quantity)

  fun getQuantityString(resId: Int, quantity: Int, vararg formatArgs: Any): String =
    R.getQuantityString(resId, quantity, *formatArgs)
}
