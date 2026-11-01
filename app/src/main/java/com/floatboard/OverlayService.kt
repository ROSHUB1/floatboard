package com.floatboard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot

private const val EDGE_L = 1
private const val EDGE_R = 2
private const val EDGE_T = 4
private const val EDGE_B = 8

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

/**
 * Root view of the overlay window. Two jobs:
 *  1. Exposes the finger's RAW screen position, so dragging the window is stable (Compose's own
 *     coordinates move with the window and cause jitter).
 *  2. Hold an edge/corner of the window (inside the padding gutter) to resize it.
 */
private class ResizeFrame(
    context: Context,
    private val edgePx: Int,
    private val canResize: () -> Boolean,
    private val onResizeStart: () -> Unit,
    private val onResize: (edges: Int, dx: Float, dy: Float) -> Unit,
    private val onResizeEnd: () -> Unit
) : FrameLayout(context) {

    var rawX = 0f
        private set
    var rawY = 0f
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var edges = 0
    private var armed = false
    private var resizing = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var downTime = 0L

    private val longPress = Runnable {
        if (armed && !resizing) {
            resizing = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            cancelChildren()
            onResizeStart()
        }
    }

    private fun cancelChildren() {
        val cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downLocalX, downLocalY, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun edgesAt(x: Float, y: Float): Int {
        var e = 0
        if (x <= edgePx) e = e or EDGE_L
        if (x >= width - edgePx) e = e or EDGE_R
        if (y <= edgePx) e = e or EDGE_T
        if (y >= height - edgePx) e = e or EDGE_B
        return e
    }

    private fun disarm() {
        handler.removeCallbacks(longPress)
        armed = false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        rawX = ev.rawX
        rawY = ev.rawY
        val action = ev.actionMasked

        if (action == MotionEvent.ACTION_DOWN) {
            edges = if (canResize()) edgesAt(ev.x, ev.y) else 0
            resizing = false
            armed = edges != 0
            downRawX = ev.rawX
            downRawY = ev.rawY
            downLocalX = ev.x
            downLocalY = ev.y
            downTime = ev.downTime
            if (armed) {
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                super.dispatchTouchEvent(ev)
                return true   // keep receiving this gesture even if no child wants it
            }
        }

        if (resizing) {
            when (action) {
                MotionEvent.ACTION_MOVE -> onResize(edges, ev.rawX - downRawX, ev.rawY - downRawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resizing = false
                    disarm()
                    onResizeEnd()
                }
            }
            return true
        }

        if (armed) {
            if (action == MotionEvent.ACTION_MOVE && hypot(ev.rawX - downRawX, ev.rawY - downRawY) > slop) disarm()
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) disarm()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
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
    private lateinit var lp: WindowManager.LayoutParams
    private val owner = OverlayOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var frame: ResizeFrame? = null

    private var isBubble = false
    private var boardW = 0
    private var boardH = 0
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var rsX = 0
    private var rsY = 0
    private var rsW = 0
    private var rsH = 0

    private val density get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels
    private fun dp(v: Float) = (v * density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(applicationContext)
        SoundStore.init(applicationContext)
        AppSettings.init(applicationContext)
        Entitlement.init(applicationContext)
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
        if (frame == null) showOverlay()
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
            .setSmallIcon(R.drawable.ic_music)
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

    // ───────────── window geometry ─────────────

    private fun clampW(px: Int) = px.coerceIn(dp(AppSettings.MIN_W), maxOf(dp(AppSettings.MIN_W), screenW - dp(8f)))
    private fun clampH(px: Int) = px.coerceIn(dp(AppSettings.MIN_H), maxOf(dp(AppSettings.MIN_H), screenH - dp(8f)))

    private fun keepOnScreen() {
        lp.x = lp.x.coerceIn(0, (screenW - lp.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenH - lp.height).coerceAtLeast(0))
    }

    private fun pushLayout() {
        val f = frame ?: return
        try {
            windowManager.updateViewLayout(f, lp)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "layout update after view removed", e)
        }
    }

    private fun onDragStart() {
        frame?.let {
            dragStartRawX = it.rawX
            dragStartRawY = it.rawY
        }
        dragStartX = lp.x
        dragStartY = lp.y
    }

    private fun onDragMove() {
        val f = frame ?: return
        lp.x = (dragStartX + (f.rawX - dragStartRawX)).toInt()
        lp.y = (dragStartY + (f.rawY - dragStartRawY)).toInt()
        keepOnScreen()
        pushLayout()
    }

    private fun applyWindowMode(bubble: Boolean) {
        isBubble = bubble
        if (bubble) {
            lp.width = dp(56f)
            lp.height = dp(56f)
        } else {
            lp.width = boardW
            lp.height = boardH
        }
        keepOnScreen()
        pushLayout()
    }

    private fun onResizeStart() {
        rsX = lp.x
        rsY = lp.y
        rsW = lp.width
        rsH = lp.height
        OverlayUiState.resizing = true
    }

    private fun onResize(edges: Int, dx: Float, dy: Float) {
        val minW = dp(AppSettings.MIN_W)
        val minH = dp(AppSettings.MIN_H)
        val sw = screenW
        val sh = screenH
        var left = rsX
        var top = rsY
        var right = rsX + rsW
        var bottom = rsY + rsH
        if (edges and EDGE_L != 0) left = (rsX + dx).toInt().coerceIn(maxOf(0, right - sw), right - minW)
        if (edges and EDGE_R != 0) right = (rsX + rsW + dx).toInt().coerceIn(left + minW, minOf(sw, left + sw))
        if (edges and EDGE_T != 0) top = (rsY + dy).toInt().coerceIn(maxOf(0, bottom - sh), bottom - minH)
        if (edges and EDGE_B != 0) bottom = (rsY + rsH + dy).toInt().coerceIn(top + minH, minOf(sh, top + sh))
        lp.x = left
        lp.y = top
        lp.width = right - left
        lp.height = bottom - top
        pushLayout()
    }

    private fun onResizeEnd() {
        boardW = lp.width
        boardH = lp.height
        AppSettings.updateFloatSize(boardW / density, boardH / density)
        OverlayUiState.resizing = false
    }

    /** Applies a size saved in settings (e.g. "Reset window size" in the app) to the live window. */
    private fun applySavedSize(wDp: Float, hDp: Float) {
        if (frame == null || OverlayUiState.resizing) return
        val w = clampW(dp(wDp))
        val h = clampH(dp(hDp))
        if (w == boardW && h == boardH) return
        boardW = w
        boardH = h
        if (!isBubble) {
            lp.width = w
            lp.height = h
            keepOnScreen()
            pushLayout()
        }
    }

    private fun showOverlay() {
        boardW = clampW(dp(AppSettings.floatWDp))
        boardH = clampH(dp(AppSettings.floatHDp))
        lp = WindowManager.LayoutParams(
            boardW,
            boardH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(24f)
            y = dp(120f)
        }
        keepOnScreen()

        owner.create()
        owner.resume()

        val compose = ComposeView(this)
        compose.setViewTreeLifecycleOwner(owner)
        compose.setViewTreeSavedStateRegistryOwner(owner)
        compose.setViewTreeViewModelStoreOwner(owner)
        compose.setContent {
            OverlayRoot(
                onDragStart = ::onDragStart,
                onDragMove = ::onDragMove,
                onWindowMode = ::applyWindowMode,
                onClose = { stopSelf() },
                onOpenApp = {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                }
            )
        }

        val root = ResizeFrame(
            context = this,
            edgePx = dp(12f),
            canResize = { !isBubble },
            onResizeStart = ::onResizeStart,
            onResize = ::onResize,
            onResizeEnd = ::onResizeEnd
        )
        root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Compose looks up the lifecycle owner from the TOP view of the window, which is `root`.
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)

        try {
            windowManager.addView(root, lp)
            frame = root
            running = true
            scope.launch {
                snapshotFlow { AppSettings.floatWDp to AppSettings.floatHDp }
                    .collect { (w, h) -> applySavedSize(w, h) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (VoiceRecorder.isRecording) VoiceRecorder.stop(this)
        SoundPlayer.stopAll()
        scope.cancel()
        OverlayUiState.resizing = false
        frame?.let { f ->
            try {
                (f.getChildAt(0) as? ComposeView)?.disposeComposition()
                windowManager.removeView(f)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed", e)
            }
        }
        frame = null
        owner.destroy()
        running = false
        super.onDestroy()
    }
}
