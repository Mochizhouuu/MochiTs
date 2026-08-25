package com.mochits.imaging

import org.opencv.android.OpenCVLoader

object OpenCVInitializer {
    fun init() {
        try {
            OpenCVLoader.initLocal()
        } catch (_: Throwable) {
        }
    }
}
