package android.webkit

import android.view.View
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import netscape.javascript.JSObject
import javax.swing.JComponent
import javax.swing.SwingUtilities

open class WebView(context: Any? = null) : View() {
  override val component: JComponent = JFXPanel()

  private var fxWebView: javafx.scene.web.WebView? = null
  private val jsInterfaces = mutableMapOf<String, Any>()

  var settings: WebSettings = WebSettings()
  var webViewClient: WebViewClient? = null
  var webChromeClient: WebChromeClient? = null

  init {
    Platform.runLater {
      val webView = javafx.scene.web.WebView()
      fxWebView = webView
      val scene = Scene(webView)
      (component as JFXPanel).scene = scene
      
      webView.engine.loadWorker.stateProperty().addListener { _, _, newState ->
        if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
            val window = webView.engine.executeScript("window") as? JSObject
            jsInterfaces.forEach { (name, obj) ->
                window?.setMember(name, obj)
            }
        }
      }
    }
  }

  fun addJavascriptInterface(obj: Any, name: String) {
    jsInterfaces[name] = obj
    Platform.runLater {
      val window = fxWebView?.engine?.executeScript("window") as? JSObject
      window?.setMember(name, obj)
    }
  }

  fun loadUrl(url: String) {
    Platform.runLater {
      var actualUrl = url
      if (url.startsWith("https://appassets.androidplatform.net/")) {
        val path = url.removePrefix("https://appassets.androidplatform.net/")
        val resourceUrl = javaClass.classLoader.getResource(path)
        if (resourceUrl != null) {
          actualUrl = resourceUrl.toString()
        }
      }
      fxWebView?.engine?.load(actualUrl)
    }
  }

  fun loadDataWithBaseURL(baseUrl: String?, data: String, mimeType: String?, encoding: String?, historyUrl: String?) {
    Platform.runLater {
      fxWebView?.engine?.loadContent(data, mimeType ?: "text/html")
    }
  }

  fun evaluateJavascript(script: String, resultCallback: ((String) -> Unit)? = null) {
    Platform.runLater {
      try {
        val result = fxWebView?.engine?.executeScript(script)
        resultCallback?.invoke(result?.toString() ?: "")
      } catch (e: Exception) {
        e.printStackTrace()
        resultCallback?.invoke("")
      }
    }
  }

  fun stopLoading() {
    Platform.runLater {
        fxWebView?.engine?.loadWorker?.cancel()
    }
  }

  fun destroy() {
    Platform.runLater {
        fxWebView?.engine?.load(null)
    }
  }

  fun setOnTouchListener(listener: (View, Any?) -> Boolean) {}
}

class WebSettings {
  var javaScriptEnabled: Boolean = true
  var domStorageEnabled: Boolean = true
  var allowFileAccess: Boolean = true
  var mediaPlaybackRequiresUserGesture: Boolean = false
}



open class WebChromeClient {
  open fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
  open fun onPermissionRequest(request: PermissionRequest?) {}
}

class PermissionRequest {
  val resources: Array<String> = emptyArray()
  fun deny() {}
  fun grant(resources: Array<String>) {}

  companion object {
    const val RESOURCE_VIDEO_CAPTURE = "android.webkit.resource.VIDEO_CAPTURE"
    const val RESOURCE_AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE"
  }
}

class WebResourceRequest(val url: android.net.Uri? = null)

class WebResourceResponse(
  val mimeType: String? = null,
  val encoding: String? = null,
  val data: java.io.InputStream? = null
)

class ConsoleMessage(
  private val message: String = "",
  private val sourceId: String = "",
  private val lineNumber: Int = 0,
  private val level: MessageLevel = MessageLevel.LOG
) {
  enum class MessageLevel {
    LOG, WARNING, ERROR, DEBUG, TIP
  }
  fun message(): String = message
  fun sourceId(): String = sourceId
  fun lineNumber(): Int = lineNumber
  fun messageLevel(): MessageLevel = level
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class JavascriptInterface
