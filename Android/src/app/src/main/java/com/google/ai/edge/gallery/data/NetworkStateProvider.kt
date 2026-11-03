/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Tells the state of the device's default network, which model downloads go through. */
interface NetworkStateProvider {
  /**
   * Returns whether the default network can reach the internet. It isn't required to be validated,
   * which can take a while after connecting or fail behind captive portals and proxies.
   */
  fun isConnected(): Boolean

  /**
   * Returns whether the default network is metered or cellular, so that downloading uses mobile
   * data, which can cost the user money.
   */
  fun isOnMobileData(): Boolean
}

/**
 * A [NetworkStateProvider] that reads the default network from the system [ConnectivityManager].
 */
class DefaultNetworkStateProvider(context: Context) : NetworkStateProvider {
  private val connectivityManager: ConnectivityManager? =
    context.applicationContext.getSystemService(ConnectivityManager::class.java)

  override fun isConnected(): Boolean {
    val connectivityManager = connectivityManager ?: return true
    val capabilities =
      connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  override fun isOnMobileData(): Boolean {
    val connectivityManager = connectivityManager ?: return false
    val capabilities =
      connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
  }
}
