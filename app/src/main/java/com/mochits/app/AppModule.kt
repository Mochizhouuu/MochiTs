package com.mochits.app

import android.content.Context
import com.mochits.inpaint.ModelManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Penyedia dependensi level aplikasi.
 *
 * ModelManager dibuat @Singleton agar status unduhan (StateFlow) sinkron
 * antar layar Settings & Editor — sebelumnya tiap layar membuat instance
 * sendiri sehingga status tidak nyambung. Unduhan model dijalankan di
 * [CoroutineScope] aplikasi (bukan viewModelScope) agar tidak terputus
 * saat user keluar dari layar Settings.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideModelManager(@ApplicationContext context: Context): ModelManager =
        ModelManager(context)

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
