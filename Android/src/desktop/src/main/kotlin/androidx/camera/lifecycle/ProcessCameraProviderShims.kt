package androidx.camera.lifecycle

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class ProcessCameraProvider {
  fun hasCamera(cameraSelector: CameraSelector): Boolean = false
  fun unbindAll() {}
  fun bindToLifecycle(
    lifecycleOwner: Any?,
    cameraSelector: CameraSelector,
    vararg useCases: Any?
  ): Camera = Camera()

  companion object {
    @JvmStatic
    fun getInstance(context: Context): ListenableFuture<ProcessCameraProvider> {
      return object : ListenableFuture<ProcessCameraProvider> {
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = true
        override fun get(): ProcessCameraProvider = ProcessCameraProvider()
        override fun get(timeout: Long, unit: TimeUnit): ProcessCameraProvider = ProcessCameraProvider()
        override fun addListener(listener: Runnable, executor: Executor) {
          executor.execute(listener)
        }
      }
    }
  }
}

suspend fun ProcessCameraProvider.Companion.awaitInstance(context: Context): ProcessCameraProvider =
  ProcessCameraProvider()
