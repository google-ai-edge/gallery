package android.os

import javax.swing.SwingUtilities

class Looper private constructor() {
  companion object {
    private val mainLooper = Looper()

    @JvmStatic
    fun getMainLooper(): Looper = mainLooper

    @JvmStatic
    fun myLooper(): Looper? = mainLooper
  }
}

class Handler(private val looper: Looper = Looper.getMainLooper()) {
  fun post(runnable: Runnable): Boolean {
    if (SwingUtilities.isEventDispatchThread()) {
      runnable.run()
    } else {
      SwingUtilities.invokeLater(runnable)
    }
    return true
  }

  fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean {
    java.util.Timer("handler-timer", true).schedule(object : java.util.TimerTask() {
      override fun run() {
        SwingUtilities.invokeLater(runnable)
      }
    }, delayMillis)
    return true
  }

  fun removeCallbacks(runnable: Runnable) {}
  fun removeCallbacksAndMessages(token: Any?) {}
}
