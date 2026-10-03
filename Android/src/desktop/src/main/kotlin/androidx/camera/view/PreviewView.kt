package androidx.camera.view

import android.content.Context
import android.view.View
import androidx.camera.core.Preview

class PreviewView(context: Context) : View(context) {
  val surfaceProvider: Preview.SurfaceProvider = object : Preview.SurfaceProvider {}
}
