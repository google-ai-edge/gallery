package android.webkit;

public class WebViewClient {
  public void onPageFinished(WebView view, String url) {}
  public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return null; }
  public WebResourceResponse shouldInterceptRequest(WebView view, String url) { return null; }
  public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return false; }
  public boolean shouldOverrideUrlLoading(WebView view, String url) { return false; }
}
