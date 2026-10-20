package com.mochits.app.script

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferensi antrean script TL yang dipakai lintas layar (Editor + Pengaturan).
 *
 * Prefs yang sama (`mochits_script_state`) juga menyimpan posisi halaman/baris
 * per proyek; repository ini hanya mengurus toggle auto-restore dan membaca
 * URI/nama file terakhir tanpa menduplikasi format key posisi.
 */
@Singleton
class ScriptSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mochits_script_state", Context.MODE_PRIVATE)

    private val _autoRestore = MutableStateFlow(prefs.getBoolean(KEY_AUTO_RESTORE, true))

    /** true = file script terakhir dimuat ulang otomatis saat proyek dibuka. */
    val autoRestore: StateFlow<Boolean> = _autoRestore.asStateFlow()

    fun setAutoRestore(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RESTORE, enabled).apply()
        _autoRestore.value = enabled
    }

    fun getLastUri(projectId: String): String? = prefs.getString("uri_$projectId", null)

    fun getLastName(projectId: String): String? = prefs.getString("name_$projectId", null)

    companion object {
        const val KEY_AUTO_RESTORE = "auto_restore_last_file"
    }
}
