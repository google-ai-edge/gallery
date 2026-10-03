package androidx.webkit

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse

class WebViewAssetLoader {
  class AssetsPathHandler(val context: Context)
  class InternalStoragePathHandler(val context: Context, val directory: java.io.File)

  class Builder {
    fun addPathHandler(path: String, handler: Any): Builder = this
    fun build(): WebViewAssetLoader = WebViewAssetLoader()
  }

  fun shouldInterceptRequest(url: Uri?): WebResourceResponse? = null
}
