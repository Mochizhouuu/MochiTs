package com.mochits.project

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProjectModule {

    @Provides
    @Singleton
    fun provideMochiTsDatabase(@ApplicationContext context: Context): MochiTsDatabase =
        Room.databaseBuilder(context, MochiTsDatabase::class.java, MochiTsDatabase.DATABASE_NAME)
            .build()

    @Provides
    fun provideProjectDao(database: MochiTsDatabase): ProjectDao = database.projectDao()
}
