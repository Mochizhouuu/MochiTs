package com.mochits.app.di

import android.content.Context
import androidx.room.Room
import com.mochits.app.font.CustomFontDao
import com.mochits.app.font.FontRepository
import com.mochits.app.imaging.LaMaModelManager
import com.mochits.app.project.MochiTsDatabase
import com.mochits.app.project.ProjectDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

}
