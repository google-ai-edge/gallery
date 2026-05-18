package com.smartscreenshot.organizer.di

import android.content.Context
import androidx.room.Room
import com.smartscreenshot.organizer.data.db.AppDatabase
import com.smartscreenshot.organizer.data.db.ScreenshotDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "smart_screenshot_db"
        )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideScreenshotDao(database: AppDatabase): ScreenshotDao =
        database.screenshotDao()
}
