package androidx.activity.result

import android.content.Intent

class ActivityResult(val resultCode: Int, val data: Intent?)

fun interface ActivityResultLauncher<I> {
  fun launch(input: I)
}
