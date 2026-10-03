package androidx.core.graphics

import android.graphics.Bitmap

fun Bitmap.scale(dstWidth: Int, dstHeight: Int, filter: Boolean = true): Bitmap =
  Bitmap.createScaledBitmap(this, dstWidth, dstHeight, filter)
