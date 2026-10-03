package net.openid.appauth

import android.content.Context
import android.content.Intent
import android.net.Uri

class AuthorizationServiceConfiguration(val authEndpoint: Uri, val tokenEndpoint: Uri)

class TokenResponse(
  val accessToken: String? = null,
  val refreshToken: String? = null,
  val accessTokenExpirationTime: Long? = null,
)

class AuthorizationService(context: Context) {
  fun dispose() {}
  fun getAuthorizationRequestIntent(request: AuthorizationRequest): Intent {
    val intent = Intent(Intent.ACTION_VIEW)
    intent.data = Uri.parse("https://huggingface.co/settings/tokens")
    intent.putExtra("is_hf_auth", true)
    return intent
  }
  fun performTokenRequest(request: Any?, callback: (TokenResponse?, AuthorizationException?) -> Unit) {
    if (request is String) {
       // Request contains our manual token!
       callback(TokenResponse(
           accessToken = request,
           refreshToken = request,
           accessTokenExpirationTime = System.currentTimeMillis() + 31536000000L // 1 year
       ), null)
    } else {
       callback(null, AuthorizationException("Empty auth result"))
    }
  }
}

class AuthorizationRequest {
  class Builder(serviceConfiguration: Any?, clientId: String, responseType: String, redirectUri: Uri) {
    fun setScope(scope: String) = this
    fun build(): AuthorizationRequest = AuthorizationRequest()
  }
}

class AuthorizationResponse {
  val authorizationCode: String? = null
  var desktopToken: String? = null
  
  fun createTokenExchangeRequest(): Any? = desktopToken

  companion object {
    @JvmStatic
    fun fromIntent(intent: Intent): AuthorizationResponse? {
      val token = intent.getStringExtra("hf_desktop_token")
      if (!token.isNullOrEmpty()) {
          val response = AuthorizationResponse()
          response.desktopToken = token
          return response
      }
      return null
    }
  }
}

class AuthorizationException(message: String? = null) : Exception(message) {
  companion object {
    @JvmStatic
    fun fromIntent(intent: Intent): AuthorizationException? = null
  }
}

object ResponseTypeValues {
  const val CODE = "code"
}
