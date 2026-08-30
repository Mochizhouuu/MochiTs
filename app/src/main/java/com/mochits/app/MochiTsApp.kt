package com.mochits.app

import android.app.Application
import android.util.Log
import com.mochits.core.imaging.NativeBridge
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class MochiTsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val startTime = System.currentTimeMillis()
                val cvVersion = NativeBridge.nativeGetOpenCVVersion()
                val elapsed = System.currentTimeMillis() - startTime
                Log.i("MochiTsApp", "Native imaging engine initialized asynchronously in ${elapsed}ms. OpenCV version: $cvVersion")
            } catch (e: Throwable) {
                Log.e("MochiTsApp", "Failed to initialize native imaging library", e)
            }
        }
    }
}
