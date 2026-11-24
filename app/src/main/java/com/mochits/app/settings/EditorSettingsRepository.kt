package com.mochits.app.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pengaturan editor yang dipakai saat menggeser layer.
 * Disimpan di SharedPreferences agar tetap ada setelah aplikasi ditutup.
 */
@Singleton
class EditorSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mochits_editor_settings", Context.MODE_PRIVATE)

    private val _snapToCenter = MutableStateFlow(prefs.getBoolean(KEY_SNAP_TO_CENTER, true))
    val snapToCenter: StateFlow<Boolean> = _snapToCenter.asStateFlow()

    fun setSnapToCenter(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SNAP_TO_CENTER, enabled).apply()
        _snapToCenter.value = enabled
    }

    companion object {
        const val KEY_SNAP_TO_CENTER = "snap_to_center"
    }
}
