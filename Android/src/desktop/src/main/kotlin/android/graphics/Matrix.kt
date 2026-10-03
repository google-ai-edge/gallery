package android.graphics

class Matrix {
  var postRotateDeg: Float = 0f

  fun postRotate(degrees: Float) {
    postRotateDeg += degrees
  }

  fun postScale(sx: Float, sy: Float) {}
  fun preScale(sx: Float, sy: Float) {}
}
