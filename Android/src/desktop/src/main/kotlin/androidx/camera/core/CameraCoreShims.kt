package androidx.camera.core

import android.graphics.Bitmap
import java.util.concurrent.Executor

class CameraControl

class Camera(val cameraControl: CameraControl = CameraControl())

open class UseCase

open class Preview : UseCase() {
  interface SurfaceProvider

  var surfaceProvider: SurfaceProvider? = null

  class Builder {
    fun build(): Preview = Preview()
  }
}

class CameraSelector {
  class Builder {
    fun requireLensFacing(lensFacing: Int): Builder = this
    fun build(): CameraSelector = CameraSelector()
  }

  companion object {
    val DEFAULT_FRONT_CAMERA = CameraSelector()
    val DEFAULT_BACK_CAMERA = CameraSelector()
    const val LENS_FACING_FRONT = 0
    const val LENS_FACING_BACK = 1
  }
}

class ImageInfo {
  val rotationDegrees: Int = 0
}

class ImageProxy : AutoCloseable {
  val imageInfo: ImageInfo = ImageInfo()
  override fun close() {}
  fun toBitmap(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}

open class ImageCapture : UseCase() {
  class Builder {
    fun setResolutionSelector(selector: Any?): Builder = this
    fun build(): ImageCapture = ImageCapture()
  }

  abstract class OnImageCapturedCallback {
    open fun onCaptureSuccess(image: ImageProxy) {}
    open fun onError(exception: Exception) {}
  }

  fun takePicture(executor: Executor, callback: OnImageCapturedCallback) {}
}

open class ImageAnalysis : UseCase() {
  @Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER)
  @Retention(AnnotationRetention.SOURCE)
  annotation class OutputImageFormat

  class Builder {
    fun setResolutionSelector(selector: Any?): Builder = this
    fun setOutputImageFormat(format: Int): Builder = this
    fun setBackpressureStrategy(strategy: Int): Builder = this
    fun build(): ImageAnalysis = ImageAnalysis()
  }

  fun setAnalyzer(executor: Executor, analyzer: (ImageProxy) -> Unit) {}

  companion object {
    const val OUTPUT_IMAGE_FORMAT_RGBA_8888 = 1
    const val STRATEGY_KEEP_ONLY_LATEST = 0
  }
}
