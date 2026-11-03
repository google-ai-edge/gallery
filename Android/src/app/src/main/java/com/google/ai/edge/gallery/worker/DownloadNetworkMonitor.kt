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

package com.google.ai.edge.gallery.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AGDownloadNetworkMonitor"

/**
 * Counts the meaningful changes of the device's default network, so that throughputs measured on
 * one network aren't compared to connections on another (see [RelativeStallDetector]).
 *
 * The generation goes up when a different network becomes the default, when the default network is
 * lost, or when the default network's transports (Wi-Fi, cellular, Ethernet, VPN...) or whether it
 * is metered change. Updates that change none of these are ignored, and so is the first network
 * reported, which is the network the app started on.
 *
 * It is thread-safe: the events come from the system's callback thread, and the generation is read
 * by the downloads.
 */
class NetworkGenerationTracker {
  /** The current generation, which starts at 0. */
  @Volatile
  var generation = 0L
    private set

  // Guarded by this.
  private var sawNetwork = false
  private var networkId: Long? = null
  private var transports: Set<Int> = emptySet()
  private var notMetered: Boolean? = null

  /** Records that the network [networkId] became the default network. */
  @Synchronized
  fun onAvailable(networkId: Long) {
    if (networkId != this.networkId) {
      switchTo(networkId)
    }
  }

  /** Records that the default network was lost. */
  @Synchronized
  fun onLost() {
    this.networkId = null
    transports = emptySet()
    notMetered = null
    generation++
  }

  /**
   * Records that the default network [networkId] has [transports]
   * (`NetworkCapabilities.TRANSPORT_*` values), and is not metered if [notMetered].
   */
  @Synchronized
  fun onCapabilitiesChanged(networkId: Long, transports: Set<Int>, notMetered: Boolean) {
    when {
      networkId != this.networkId -> switchTo(networkId)
      // The first capabilities of a network come right after it becomes the default network.
      this.notMetered == null -> {}
      transports != this.transports || notMetered != this.notMetered -> generation++
    }
    this.transports = transports
    this.notMetered = notMetered
  }

  /** Makes [networkId] the default network, whose capabilities aren't known yet. */
  private fun switchTo(networkId: Long) {
    this.networkId = networkId
    transports = emptySet()
    notMetered = null
    if (sawNetwork) {
      generation++
    }
    sawNetwork = true
  }
}

/** The transports whose changes count as a network change. */
private val TRACKED_TRANSPORTS =
  listOf(
    NetworkCapabilities.TRANSPORT_CELLULAR,
    NetworkCapabilities.TRANSPORT_WIFI,
    NetworkCapabilities.TRANSPORT_BLUETOOTH,
    NetworkCapabilities.TRANSPORT_ETHERNET,
    NetworkCapabilities.TRANSPORT_VPN,
    NetworkCapabilities.TRANSPORT_WIFI_AWARE,
    NetworkCapabilities.TRANSPORT_LOWPAN,
    NetworkCapabilities.TRANSPORT_USB,
  )

/**
 * Reports the changes of the device's default network to a [NetworkGenerationTracker]. The app uses
 * one for the whole life of its process (see `DownloadWorker`).
 */
class DefaultNetworkMonitor {
  private val started = AtomicBoolean(false)

  /**
   * Starts reporting the default network's changes to the tracker that [tracker] returns at each
   * change, unless it was already started.
   *
   * The callback is registered with the application context and never unregistered, as it lives as
   * long as the process. Monitoring is best-effort: if the callback can't be registered, e.g.
   * because the app has too many callbacks, the downloads go on without it, and the next call tries
   * again.
   */
  fun start(context: Context, tracker: () -> NetworkGenerationTracker) {
    if (!started.compareAndSet(false, true)) {
      return
    }
    try {
      register(context, tracker)
    } catch (e: RuntimeException) {
      Log.w(TAG, "Failed to monitor the default network", e)
      started.set(false)
    }
  }

  private fun register(context: Context, tracker: () -> NetworkGenerationTracker) {
    val connectivityManager =
      context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
    connectivityManager.registerDefaultNetworkCallback(
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          tracker().onAvailable(network.networkHandle)
        }

        override fun onLost(network: Network) {
          tracker().onLost()
        }

        override fun onCapabilitiesChanged(
          network: Network,
          networkCapabilities: NetworkCapabilities,
        ) {
          tracker()
            .onCapabilitiesChanged(
              networkId = network.networkHandle,
              transports =
                TRACKED_TRANSPORTS.filter { networkCapabilities.hasTransport(it) }.toSet(),
              notMetered =
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
        }
      }
    )
  }
}
