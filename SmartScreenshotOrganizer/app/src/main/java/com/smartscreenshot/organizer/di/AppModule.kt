package com.smartscreenshot.organizer.di

import android.content.Context
import com.smartscreenshot.organizer.data.repository.ScreenshotRepository
import com.smartscreenshot.organizer.data.repository.ScreenshotRepositoryImpl
import com.smartscreenshot.organizer.ocr.MlKitOcrEngine
import com.smartscreenshot.organizer.ocr.OcrEngine
import com.smartscreenshot.organizer.search.LocalSearchEngine
import com.smartscreenshot.organizer.search.SearchEngine
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindScreenshotRepository(
        impl: ScreenshotRepositoryImpl
    ): ScreenshotRepository

    @Binds
    @Singleton
    abstract fun bindOcrEngine(
        impl: MlKitOcrEngine
    ): OcrEngine

    @Binds
    @Singleton
    abstract fun bindSearchEngine(
        impl: LocalSearchEngine
    ): SearchEngine

    companion object {

        @Provides
        @Singleton
        fun provideMoshi(): Moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
