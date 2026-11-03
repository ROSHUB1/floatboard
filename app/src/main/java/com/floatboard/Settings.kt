package com.floatboard

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What happens when you tap a sound while another one is playing. */
object PlayMode {
    const val QUEUE = 0
    const val REPLACE = 1
    const val OVERLAP = 2
}

/** Persisted user settings. Compose-observable, so the overlay reacts live when changed in the app. */
object AppSettings {
    private const val PREFS = "floatboard_prefs"
    const val DEFAULT_W = 320f
    const val DEFAULT_H = 480f
    const val MIN_W = 260f
    const val MIN_H = 300f

    private lateinit var prefs: SharedPreferences

    var opacity by mutableFloatStateOf(0.95f)
        private set
    var tile by mutableIntStateOf(1)
        private set
    var playMode by mutableIntStateOf(PlayMode.QUEUE)
        private set
    var floatWDp by mutableFloatStateOf(DEFAULT_W)
        private set
    var floatHDp by mutableFloatStateOf(DEFAULT_H)
        private set
    var overlayFavsOnly by mutableStateOf(false)
        private set

    /** Thumbnail size of a tile in the floating grid (dp). */
    val tileThumbDp: Int get() = when (tile) { 0 -> 40; 1 -> 52; else -> 64 }

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        opacity = prefs.getFloat("opacity", 0.95f).coerceIn(0.4f, 1f)
        tile = prefs.getInt("tile", 1).coerceIn(0, 2)
        playMode = prefs.getInt("playMode", PlayMode.QUEUE).coerceIn(0, 2)
        floatWDp = prefs.getFloat("floatW", DEFAULT_W).coerceAtLeast(MIN_W)
        floatHDp = prefs.getFloat("floatH", DEFAULT_H).coerceAtLeast(MIN_H)
        overlayFavsOnly = prefs.getBoolean("overlayFavsOnly", false)
    }

    fun updateOpacity(value: Float) {
        opacity = value.coerceIn(0.4f, 1f)
        prefs.edit().putFloat("opacity", opacity).apply()
    }

    fun updateTile(value: Int) {
        tile = value.coerceIn(0, 2)
        prefs.edit().putInt("tile", tile).apply()
    }

    fun updatePlayMode(value: Int) {
        playMode = value.coerceIn(0, 2)
        prefs.edit().putInt("playMode", playMode).apply()
    }

    fun updateFloatSize(wDp: Float, hDp: Float) {
        floatWDp = wDp.coerceAtLeast(MIN_W)
        floatHDp = hDp.coerceAtLeast(MIN_H)
        prefs.edit().putFloat("floatW", floatWDp).putFloat("floatH", floatHDp).apply()
    }

    fun resetFloatSize() = updateFloatSize(DEFAULT_W, DEFAULT_H)

    fun updateOverlayFavsOnly(value: Boolean) {
        overlayFavsOnly = value
        prefs.edit().putBoolean("overlayFavsOnly", value).apply()
    }
}
