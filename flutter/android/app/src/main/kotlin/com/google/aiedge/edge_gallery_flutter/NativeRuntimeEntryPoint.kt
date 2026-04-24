package com.google.aiedge.edge_gallery_flutter

import android.content.Context
import com.google.ai.edge.gallery.AppLifecycleProvider
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.DownloadRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

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
