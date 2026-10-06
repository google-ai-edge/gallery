package androidx.core.content

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.Executor

object ContextCompat {
  @JvmStatic
  fun checkSelfPermission(context: Context, permission: String): Int = PackageManager.PERMISSION_GRANTED

  @JvmStatic
  fun getMainExecutor(context: Context): Executor = Executor { it.run() }
}
