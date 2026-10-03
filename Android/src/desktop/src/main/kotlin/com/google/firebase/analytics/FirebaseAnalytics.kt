package com.google.firebase.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.Firebase

class FirebaseAnalytics {
  fun logEvent(name: String, params: Bundle?) {}
  fun setAnalyticsCollectionEnabled(enabled: Boolean) {}

  companion object {
    private val instance = FirebaseAnalytics()
    @JvmStatic
    fun getInstance(context: Context? = null): FirebaseAnalytics = instance
  }
}

val Firebase.analytics: FirebaseAnalytics
  get() = FirebaseAnalytics.getInstance()
