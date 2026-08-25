package com.mochits.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import com.mochits.imaging.OpenCVInitializer

/**
 * Entry point aplikasi MochiTs. Anotasi @HiltAndroidApp memicu
 * generasi komponen DI Hilt yang dipakai lintas module (app + core-*).
 */
@HiltAndroidApp
class MochiTsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OpenCVInitializer.init()
    }
}
