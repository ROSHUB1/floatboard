package com.floatboard

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Persisted user settings. Compose-observable, so the overlay reacts live when changed in the app. */
object AppSettings {
    private const val PREFS = "floatboard_prefs"
    private lateinit var prefs: SharedPreferences

    var opacity by mutableFloatStateOf(0.95f)
        private set
    var columns by mutableIntStateOf(3)
        private set
    var overlap by mutableStateOf(false)
        private set

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        opacity = prefs.getFloat("opacity", 0.95f).coerceIn(0.4f, 1f)
        columns = prefs.getInt("columns", 3).coerceIn(3, 4)
        overlap = prefs.getBoolean("overlap", false)
    }

    fun updateOpacity(value: Float) {
        opacity = value.coerceIn(0.4f, 1f)
        prefs.edit().putFloat("opacity", opacity).apply()
    }

    fun updateColumns(value: Int) {
        columns = value.coerceIn(3, 4)
        prefs.edit().putInt("columns", columns).apply()
    }

    fun updateOverlap(value: Boolean) {
        overlap = value
        prefs.edit().putBoolean("overlap", value).apply()
    }
}
