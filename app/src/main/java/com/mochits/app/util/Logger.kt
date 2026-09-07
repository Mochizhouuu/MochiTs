package com.mochits.app.util

import android.util.Log

/**
 * Simple logger wrapper for consistent logging across the app.
 * Replace with Timber in production for more features.
 */
object Logger {
    private const val TAG = "MochiTs"

    fun d(message: String, throwable: Throwable? = null) {
        Log.d(TAG, message, throwable)
    }

    fun i(message: String, throwable: Throwable? = null) {
        Log.i(TAG, message, throwable)
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }

    fun wtf(message: String, throwable: Throwable? = null) {
        Log.wtf(TAG, message, throwable)
    }
}
