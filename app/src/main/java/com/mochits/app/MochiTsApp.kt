package com.mochits.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MochiTsApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
