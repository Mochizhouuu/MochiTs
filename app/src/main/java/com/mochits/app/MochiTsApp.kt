package com.mochits.app

import android.app.Application
import android.util.Log
import com.mochits.core.imaging.NativeBridge
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MochiTsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val cvVersion = NativeBridge.nativeGetOpenCVVersion()
            Log.i("MochiTsApp", "Native imaging engine initialized. OpenCV version: $cvVersion")
        } catch (e: Throwable) {
            Log.e("MochiTsApp", "Failed to initialize native imaging library", e)
        }
    }
}
