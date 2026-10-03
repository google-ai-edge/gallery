package android.widget

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.R

class Toast(private val context: Context, private val text: CharSequence, private val duration: Int) {
  fun show() {
    Log.i("Toast", text.toString())
  }

  companion object {
    const val LENGTH_SHORT: Int = 0
    const val LENGTH_LONG: Int = 1

    @JvmStatic
    fun makeText(context: Context, text: CharSequence, duration: Int): Toast = Toast(context, text, duration)

    @JvmStatic
    fun makeText(context: Context, resId: Int, duration: Int): Toast = Toast(context, R.getString(resId), duration)
  }
}
