package com.server.edge.gallery

import android.content.Context
import com.server.edge.gallery.customtasks.common.CustomTask
import com.server.edge.gallery.data.DataStoreRepository
import com.server.edge.gallery.data.DownloadRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NativeRuntimeEntryPoint {
  fun downloadRepository(): DownloadRepository

  fun dataStoreRepository(): DataStoreRepository

  fun lifecycleProvider(): AppLifecycleProvider

  fun customTasks(): Set<@JvmSuppressWildcards CustomTask>

  @ApplicationContext
  fun applicationContext(): Context
}
