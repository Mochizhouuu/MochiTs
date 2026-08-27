package com.mochits.app

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

@HiltAndroidApp
class MochiTsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            if (!OpenCVLoader.initLocal()) {
                Log.w("MochiTsApp", "OpenCV initLocal returned false, attempting initDebug fallback.")
                try {
                    @Suppress("DEPRECATION")
                    OpenCVLoader.initDebug()
                } catch (t: Throwable) {
                    Log.e("MochiTsApp", "OpenCV initDebug fallback failed safely", t)
                }
            } else {
                Log.i("MochiTsApp", "OpenCV initialized successfully via initLocal.")
            }
        } catch (e: Throwable) {
            Log.e("MochiTsApp", "Failed to initialize OpenCV library safely", e)
        }
    }
}
