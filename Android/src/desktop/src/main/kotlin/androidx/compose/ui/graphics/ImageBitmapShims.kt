package androidx.compose.ui.graphics

import android.graphics.Bitmap

fun Bitmap.asImageBitmap(): ImageBitmap = this.bufferedImage.toComposeImageBitmap()
