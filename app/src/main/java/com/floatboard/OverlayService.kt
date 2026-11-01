package com.floatboard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Compose needs lifecycle/saved-state owners; a Service has none, so we provide our own. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun create() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun resume() { lifecycleRegistry.currentState = Lifecycle.State.RESUMED }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "floatboard_overlay"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.floatboard.action.STOP"

        var running by mutableStateOf(false)
            private set
    }

    private lateinit var windowManager: WindowManager
    private val owner = OverlayOwner()
    private var overlayView: ComposeView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SoundStore.init(applicationContext)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be called within ~5s of startForegroundService(), even if we bail out right after.
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission missing, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlayView == null) showOverlay()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Floating soundboard", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("FloatBoard is live")
            .setContentText("Floating soundboard is on screen")
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        when {
            Build.VERSION.SDK_INT >= 34 -> {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (micGranted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, notification, type)
            }
            Build.VERSION.SDK_INT >= 30 ->
                startForeground(
                    NOTIFICATION_ID, notification,
                    if (micGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                )
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showOverlay() {
        val density = resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (24 * density).toInt()
            y = (120 * density).toInt()
        }

        owner.create()
        owner.resume()

        val view = ComposeView(this)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setContent {
            OverlayRoot(
                onDrag = { dx, dy ->
                    lp.x = (lp.x + dx).coerceAtLeast(0)
                    lp.y = (lp.y + dy).coerceAtLeast(0)
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "drag after view removed", e)
                    }
                },
                onClose = { stopSelf() },
                onOpenApp = {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                }
            )
        }

        try {
            windowManager.addView(view, lp)
            overlayView = view
            running = true
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (VoiceRecorder.isRecording) VoiceRecorder.stop(this)
        SoundPlayer.stopAll()
        overlayView?.let { v ->
            try {
                v.disposeComposition()
                windowManager.removeView(v)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed", e)
            }
        }
        overlayView = null
        owner.destroy()
        running = false
        super.onDestroy()
    }
}
