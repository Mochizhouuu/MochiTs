package com.mochits.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Entry point aplikasi MochiTs. Anotasi @HiltAndroidApp memicu
 * generasi komponen DI Hilt yang dipakai lintas module (app + core-*).
 */
@HiltAndroidApp
class MochiTsApp : Application()
