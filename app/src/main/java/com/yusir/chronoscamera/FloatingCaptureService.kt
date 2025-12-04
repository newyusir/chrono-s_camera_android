package com.yusir.chronoscamera
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import androidx.core.view.isVisible
import androidx.media.VolumeProviderCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.yusir.chronoscamera.bridge.CapturedImageStore
import com.yusir.chronoscamera.clipboard.InMemoryImageProvider
import com.yusir.chronoscamera.settings.CaptureMode
import com.yusir.chronoscamera.settings.UserPreferences
import com.yusir.chronoscamera.autoscroll.AutoScrollController
import com.yusir.chronoscamera.autoscroll.AccessibilityScrollExecutor
import com.yusir.chronoscamera.autoscroll.ScrollExecutor
import com.yusir.chronoscamera.autoscroll.ScrollMethod
import com.yusir.chronoscamera.autoscroll.ShizukuScrollExecutor
import com.yusir.chronoscamera.shizuku.ShizukuInputService
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
class FloatingCaptureService : Service() {
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var overlayView: View? = null
    private var stateLabel: TextView? = null
    private var stateIcon: androidx.appcompat.widget.AppCompatImageView? = null
    private var manualCountLabel: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var trashView: View? = null
    private var captureController: ScreenCaptureController? = null
    private var autoScrollController: AutoScrollController? = null
    private var projectionResultCode: Int? = null
    private var projectionResultData: Intent? = null
    private var pendingStartAfterPermission: Boolean = false
    private var pendingStopSelf = false
    private var isCapturing: Boolean = false
    private var isForeground = false
    private var isRecordingActive = false
    private var isScrollPaused = false
    private var isCopyingToClipboard = false
    private var manualCaptureCount = 0
    private var autoCaptureCount = 0
    private var autoScrollState = AutoScrollController.AutoScrollState.IDLE
    private var activeCaptureMode: CaptureMode? = null
    private var mediaSession: MediaSessionCompat? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastManualCaptureAt: Long = 0L
    private var lastKnownVolume: Int = -1
    private var volumeObserver: ContentObserver? = null
    private val preferredCaptureMode: CaptureMode
        get() = UserPreferences.getCaptureMode(applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipboardExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ChronoClipboardWorker").apply { isDaemon = true }
    }
    private val audioManager: AudioManager? by lazy { getSystemService(AudioManager::class.java) }
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { }
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        activeService = this
        refreshManualCaptureArmedState()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> showOverlay()
            ACTION_PROJECTION_GRANTED -> handleProjectionGranted(intent)
            ACTION_STOP -> {
                pendingStopSelf = true
                val copying = stopCapturing(copyToClipboard = true)
                if (!copying) {
                    pendingStopSelf = false
                    stopSelf()
                }
            }
            ACTION_REFRESH_MODE -> updateOverlayState()
        }
        return START_STICKY
    }
    override fun onDestroy() {
        stopCapturing(copyToClipboard = false)
        autoScrollController?.let {
            it.stop()
            it.clear()
            it.release()
        }
        autoScrollController = null
        exitForeground()
        removeOverlay()
        removeTrashView()
        clipboardExecutor.shutdownNow()
        pendingStopSelf = false
        disableManualVolumeCapture()
        stopVolumeObserver()
        activeService = null
        refreshManualCaptureArmedState()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun showOverlay() {
        if (overlayView != null) {
            overlayView?.isVisible = true
            updateOverlayState()
            notifyOverlayStateChanged()
            return
        }
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_overlay_button, null)
        val textView = view.findViewById<TextView>(R.id.stateLabel)
        val iconView = view.findViewById<androidx.appcompat.widget.AppCompatImageView>(R.id.stateIcon)
        val countView = view.findViewById<TextView>(R.id.manualCountLabel)
        stateLabel = textView
        stateIcon = iconView
        manualCountLabel = countView
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 48
            y = 360
        }
        layoutParams = params
        view.setOnTouchListener(
            OverlayDragTouchListener {
                if (isCapturing) {
                    stopCapturing(copyToClipboard = true)
                } else {
                    startOrRequestCapture()
                }
            }
        )
        windowManager.addView(view, params)
        overlayView = view
        updateOverlayState()
        ensureTrashView()
        isOverlayVisible = true
        notifyOverlayStateChanged()
    }
    private fun startOrRequestCapture() {
        if (isCopyingToClipboard) {
            Toast.makeText(applicationContext, R.string.clipboard_copying, Toast.LENGTH_SHORT).show()
            return
        }
        val code = projectionResultCode
        val data = projectionResultData
        if (code != null && data != null) {
            startCaptureSession(code, data)
        } else {
            pendingStartAfterPermission = true
            launchProjectionPermissionActivity()
        }
    }
    private fun launchProjectionPermissionActivity() {
        val intent = Intent(this, ProjectionPermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
    @Suppress("DEPRECATION")
    private fun handleProjectionGranted(intent: Intent) {
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
        if (resultData != null && resultCode == android.app.Activity.RESULT_OK) {
            projectionResultCode = resultCode
            projectionResultData = resultData
            if (pendingStartAfterPermission) {
                pendingStartAfterPermission = false
                startCaptureSession(resultCode, resultData)
            }
        }
    }
    private fun onScrollDetected() {
        mainHandler.post {
            if (!isRecordingActive && isCapturing) {
                isRecordingActive = true
                isScrollPaused = false
                updateOverlayState()
                updateForegroundNotification()
            }
        }
    }
    private fun onCapturePaused() {
        mainHandler.post {
            if (isCapturing) {
                isRecordingActive = false
                isScrollPaused = true
                updateOverlayState()
                updateForegroundNotification()
            }
        }
    }
    private fun startCaptureSession(resultCode: Int, resultData: Intent) {
        val mode = preferredCaptureMode
        captureController?.let {
            it.stop()
            it.clear()
            it.release()
        }
        captureController = null
        autoScrollController?.let {
            it.stop()
            it.clear()
            it.release()
        }
        autoScrollController = null
        isRecordingActive = false
        isScrollPaused = false
        manualCaptureCount = 0
        autoCaptureCount = 0
        autoScrollState = AutoScrollController.AutoScrollState.IDLE
        lastManualCaptureAt = 0L
        pendingStopSelf = false
        if (mode == CaptureMode.AUTO) {
            startAutoScrollSession(resultCode, resultData)
        } else {
            startManualCaptureSession(resultCode, resultData)
        }
    }
    private fun startAutoScrollSession(resultCode: Int, resultData: Intent) {
        val scrollMethod = UserPreferences.getScrollMethod(applicationContext)
        val scrollExecutor: ScrollExecutor = when (scrollMethod) {
            ScrollMethod.SHIZUKU -> ShizukuScrollExecutor()
            ScrollMethod.ACCESSIBILITY -> AccessibilityScrollExecutor()
        }
        if (!scrollExecutor.isAvailable()) {
            when (scrollMethod) {
                ScrollMethod.SHIZUKU -> {
                    if (ShizukuInputService.isRunning()) {
                        ShizukuInputService.requestPermission()
                        Toast.makeText(applicationContext, R.string.shizuku_permission_required, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(applicationContext, R.string.shizuku_not_available, Toast.LENGTH_LONG).show()
                    }
                }
                ScrollMethod.ACCESSIBILITY -> {
                    Toast.makeText(applicationContext, R.string.accessibility_not_enabled, Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        Log.d(TAG, "Starting auto scroll with ${scrollExecutor.getMethodName()} method")
        val scrollsPerCapture = UserPreferences.getScrollPerCapture(applicationContext)
        val initialDelayMs = UserPreferences.getInitialDelay(applicationContext).toLong()
        val controller = AutoScrollController(
            context = applicationContext,
            resultCode = resultCode,
            resultData = resultData,
            scrollExecutor = scrollExecutor,
            scrollsPerCapture = scrollsPerCapture,
            initialDelayMs = initialDelayMs,
            onStateChanged = { state ->
                mainHandler.post {
                    autoScrollState = state
                    updateOverlayState()
                    updateForegroundNotification()
                }
            },
            onCaptureCountChanged = { count ->
                mainHandler.post {
                    autoCaptureCount = count
                    updateOverlayState()
                }
            },
            onCompleted = { bitmaps ->
                mainHandler.post {
                    handleAutoScrollCompleted(bitmaps)
                }
            },
            onError = { error ->
                mainHandler.post {
                    Toast.makeText(applicationContext, error, Toast.LENGTH_SHORT).show()
                    stopAutoScrollCapturing(copyToClipboard = false)
                }
            },
            onBeforeFirstCapture = {
                mainHandler.post {
                    overlayView?.visibility = View.INVISIBLE
                }
            }
        )
        enterForeground()
        autoScrollController = controller
        activeCaptureMode = CaptureMode.AUTO
        isCapturing = true
        autoCaptureCount = 0
        updateOverlayState()
        controller.start()
    }
    private fun startManualCaptureSession(resultCode: Int, resultData: Intent) {
        val frameInterval = UserPreferences.getFrameInterval(applicationContext)
        val controller = ScreenCaptureController(
            applicationContext,
            resultCode,
            resultData,
            frameInterval,
            CaptureMode.MANUAL,
            onScrollDetected = { onScrollDetected() },
            onCapturePaused = { onCapturePaused() },
            onProjectionStopped = { handleProjectionRevoked() }
        )
        enterForeground()
        val started = runCatching { controller.start() }.onFailure { error ->
            Log.e(TAG, "Failed to start capture session", error)
        }.isSuccess
        if (!started) {
            controller.clear()
            controller.release()
            clearProjectionToken()
            pendingStartAfterPermission = false
            exitForeground()
            isCapturing = false
            disableManualVolumeCapture()
            Toast.makeText(applicationContext, R.string.capture_start_failed, Toast.LENGTH_SHORT).show()
            updateOverlayState()
            return
        }
        captureController = controller
        activeCaptureMode = CaptureMode.MANUAL
        isCapturing = true
        isScrollPaused = false
        manualCaptureCount = 0
        lastManualCaptureAt = 0L
        enableManualVolumeCapture()
        updateOverlayState()
    }
    private fun handleAutoScrollCompleted(bitmaps: List<Bitmap>) {
        autoScrollController = null
        isCapturing = false
        autoScrollState = AutoScrollController.AutoScrollState.COMPLETED
        activeCaptureMode = null
        overlayView?.visibility = View.VISIBLE
        updateOverlayState()
        if (bitmaps.isEmpty()) {
            exitForeground()
            Toast.makeText(applicationContext, R.string.clipboard_failed, Toast.LENGTH_SHORT).show()
            if (pendingStopSelf) {
                pendingStopSelf = false
                stopSelf()
            }
            return
        }
        isCopyingToClipboard = true
        updateOverlayState()
        clipboardExecutor.execute {
            val copied = copyImagesToClipboardBlocking(bitmaps)
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            clearProjectionToken()
            mainHandler.post {
                isCopyingToClipboard = false
                autoScrollState = AutoScrollController.AutoScrollState.IDLE
                updateOverlayState()
                exitForeground()
                if (copied) {
                    Toast.makeText(applicationContext, R.string.clipboard_copied, Toast.LENGTH_SHORT).show()
                    launchMergeTool()
                } else {
                    CapturedImageStore.clear()
                    Toast.makeText(applicationContext, R.string.clipboard_failed, Toast.LENGTH_SHORT).show()
                }
                if (pendingStopSelf) {
                    pendingStopSelf = false
                    stopSelf()
                }
            }
        }
    }
    private fun stopAutoScrollCapturing(copyToClipboard: Boolean): Boolean {
        val controller = autoScrollController ?: return false
        controller.stop()
        val bitmaps = controller.getCapturedBitmaps()
        autoScrollController = null
        isCapturing = false
        autoScrollState = AutoScrollController.AutoScrollState.IDLE
        autoCaptureCount = 0
        activeCaptureMode = null
        overlayView?.visibility = View.VISIBLE
        updateOverlayState()
        exitForeground()
        if (!copyToClipboard || bitmaps.isEmpty()) {
            CapturedImageStore.clear()
            controller.clear()
            controller.release()
            clearProjectionToken()
            isCopyingToClipboard = false
            updateOverlayState()
            if (pendingStopSelf) {
                pendingStopSelf = false
                stopSelf()
            }
            return false
        }
        isCopyingToClipboard = true
        updateOverlayState()
        clipboardExecutor.execute {
            val copied = copyImagesToClipboardBlocking(bitmaps)
            controller.clear()
            controller.release()
            clearProjectionToken()
            mainHandler.post {
                isCopyingToClipboard = false
                updateOverlayState()
                if (copied) {
                    Toast.makeText(applicationContext, R.string.clipboard_copied, Toast.LENGTH_SHORT).show()
                    launchMergeTool()
                } else {
                    CapturedImageStore.clear()
                    Toast.makeText(applicationContext, R.string.clipboard_failed, Toast.LENGTH_SHORT).show()
                }
                if (pendingStopSelf) {
                    pendingStopSelf = false
                    stopSelf()
                }
            }
        }
        return true
    }
    private fun stopCapturing(copyToClipboard: Boolean): Boolean {
        if (autoScrollController != null) {
            return stopAutoScrollCapturing(copyToClipboard)
        }
        val controller = captureController ?: return false
        controller.stop()
        val bitmaps = controller.getCapturedBitmaps()
        captureController = null
        isCapturing = false
        isRecordingActive = false
        isScrollPaused = false
        manualCaptureCount = 0
        disableManualVolumeCapture()
        activeCaptureMode = null
        updateOverlayState()
        exitForeground()
        if (!copyToClipboard) {
            CapturedImageStore.clear()
            controller.clear()
            controller.release()
            clearProjectionToken()
            isCopyingToClipboard = false
            updateOverlayState()
            if (pendingStopSelf) {
                pendingStopSelf = false
                stopSelf()
            }
            return false
        }
        isCopyingToClipboard = true
        updateOverlayState()
        clipboardExecutor.execute {
            val copied = copyImagesToClipboardBlocking(bitmaps)
            controller.clear()
            controller.release()
            clearProjectionToken()
            mainHandler.post {
                isCopyingToClipboard = false
                updateOverlayState()
                if (copied) {
                    Toast.makeText(applicationContext, R.string.clipboard_copied, Toast.LENGTH_SHORT).show()
                    launchMergeTool()
                } else {
                    CapturedImageStore.clear()
                    Toast.makeText(applicationContext, R.string.clipboard_failed, Toast.LENGTH_SHORT).show()
                }
                if (pendingStopSelf) {
                    pendingStopSelf = false
                    stopSelf()
                }
            }
        }
        return true
    }
    private fun handleProjectionRevoked() {
        mainHandler.post {
            val controller = captureController ?: return@post
            controller.stop()
            val bitmaps = controller.getCapturedBitmaps()
            captureController = null
            val wasCapturing = isCapturing
            isCapturing = false
            isRecordingActive = false
            isScrollPaused = false
            manualCaptureCount = 0
            disableManualVolumeCapture()
            activeCaptureMode = null
            updateOverlayState()
            exitForeground()
            if (!wasCapturing) {
                CapturedImageStore.clear()
                controller.clear()
                controller.release()
                Toast.makeText(applicationContext, R.string.projection_revoked, Toast.LENGTH_SHORT).show()
                if (pendingStopSelf) {
                    pendingStopSelf = false
                    stopSelf()
                }
                return@post
            }
            isCopyingToClipboard = true
            updateOverlayState()
            clipboardExecutor.execute {
                val copied = copyImagesToClipboardBlocking(bitmaps)
                controller.clear()
                controller.release()
                clearProjectionToken()
                mainHandler.post {
                    isCopyingToClipboard = false
                    updateOverlayState()
                    if (copied) {
                        Toast.makeText(applicationContext, R.string.clipboard_copied, Toast.LENGTH_SHORT).show()
                        launchMergeTool()
                    } else {
                        CapturedImageStore.clear()
                        Toast.makeText(applicationContext, R.string.clipboard_failed, Toast.LENGTH_SHORT).show()
                    }
                    Toast.makeText(applicationContext, R.string.projection_revoked, Toast.LENGTH_SHORT).show()
                    if (pendingStopSelf) {
                        pendingStopSelf = false
                        stopSelf()
                    }
                }
            }
        }
    }
    private fun removeOverlay() {
        overlayView?.let { view ->
            windowManager.removeView(view)
        }
        overlayView = null
        stateLabel = null
        stateIcon = null
        manualCountLabel = null
        hideTrashArea()
        isOverlayVisible = false
        notifyOverlayStateChanged()
    }
    private fun ensureTrashView() {
        if (trashView != null) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_overlay_trash, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 160
        }
        view.visibility = View.GONE
        windowManager.addView(view, params)
    trashView = view
    }
    private fun removeTrashView() {
        trashView?.let { windowManager.removeView(it) }
    trashView = null
    }
    private fun showTrashArea() {
        ensureTrashView()
        trashView?.let { target ->
            target.visibility = View.VISIBLE
            target.isActivated = false
        }
    }
    private fun hideTrashArea() {
        trashView?.let { target ->
            target.visibility = View.GONE
            target.isActivated = false
        }
    }
    private fun updateTrashHighlight(isInside: Boolean) {
        trashView?.isActivated = isInside
    }
    private fun isPointInTrash(rawX: Float, rawY: Float): Boolean {
        val target = trashView ?: return false
        if (target.visibility != View.VISIBLE || target.width == 0 || target.height == 0) return false
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val centerX = location[0] + target.width / 2f
        val centerY = location[1] + target.height / 2f
        val distance = hypot(rawX - centerX, rawY - centerY)
        val radius = maxOf(target.width, target.height) * 0.7f
        return distance <= radius
    }
    private fun dismissOverlayAndStop() {
        pendingStopSelf = false
        hideTrashArea()
        stopCapturing(copyToClipboard = false)
        removeOverlay()
        CapturedImageStore.clear()
        stopSelf()
    }
    private fun clearProjectionToken() {
        projectionResultCode = null
        projectionResultData = null
    }
    private fun notifyOverlayStateChanged() {
        val intent = Intent(ACTION_OVERLAY_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_OVERLAY_VISIBLE, isOverlayVisible)
        }
        sendBroadcast(intent)
    }
    private fun enableManualVolumeCapture() {
        if (mediaSession != null) return
        val session = MediaSessionCompat(this, "ManualCaptureSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        }
        val provider = object : VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_RELATIVE,  1,  0) {
            override fun onAdjustVolume(direction: Int) {
                Log.d(TAG, "Manual volume adjust direction=$direction")
                if (direction < 0) {
                    dispatchManualCapture()
                }
            }
            override fun onSetVolumeTo(volume: Int) {
                Log.d(TAG, "Manual volume set ignored volume=$volume")
            }
        }
        session.setPlaybackToRemote(provider)
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    PlaybackStateCompat.STATE_PLAYING,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f
                )
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
                .build()
        )
        session.isActive = true
        mediaSession = session
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setAcceptsDelayedFocusGain(false)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            val result = audioManager?.requestAudioFocus(focusRequest)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioFocusRequest = focusRequest
            } else {
                Log.w(TAG, "Failed to acquire audio focus for manual capture: result=$result")
            }
        } else {
            val result = audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "Failed to acquire legacy audio focus for manual capture: result=$result")
            }
        }
        startVolumeObserver()
    }
    private fun disableManualVolumeCapture() {
        stopVolumeObserver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager?.abandonAudioFocusRequest(request)
            }
            audioFocusRequest = null
        } else {
            audioManager?.abandonAudioFocus(audioFocusChangeListener)
        }
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
        refreshManualCaptureArmedState()
    }
    private fun startVolumeObserver() {
        if (volumeObserver != null) return
        lastKnownVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        Log.d(TAG, "Starting volume observer, initial volume=$lastKnownVolume")
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return
                if (currentVolume == lastKnownVolume) return
                Log.d(TAG, "Volume changed: last=$lastKnownVolume current=$currentVolume")
                audioManager?.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    lastKnownVolume,
                    0  
                )
                if (currentVolume < lastKnownVolume) {
                    Log.d(TAG, "Volume decreased, triggering manual capture")
                    dispatchManualCapture()
                } else if (currentVolume > lastKnownVolume) {
                    Log.d(TAG, "Volume increased, triggering stop")
                    handleManualStopRequest()
                }
            }
        }
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            observer
        )
        volumeObserver = observer
    }
    private fun stopVolumeObserver() {
        volumeObserver?.let {
            contentResolver.unregisterContentObserver(it)
            Log.d(TAG, "Volume observer stopped")
        }
        volumeObserver = null
    }
    private fun handleManualStopRequest() {
        if (activeCaptureMode != CaptureMode.MANUAL || !isCapturing || isCopyingToClipboard) return
        Log.d(TAG, "handleManualStopRequest - stopping capture")
        stopCapturing(copyToClipboard = true)
    }
    private fun dispatchManualCapture() {
        if (activeCaptureMode != CaptureMode.MANUAL || !isCapturing || isCopyingToClipboard) return
        val controller = captureController ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastManualCaptureAt < MANUAL_CAPTURE_DEBOUNCE_MS) {
            return
        }
        lastManualCaptureAt = now
        Log.d(TAG, "dispatchManualCapture invoked")
        val count = controller.captureManualFrame()
        if (count != manualCaptureCount) {
            manualCaptureCount = count
            updateOverlayState()
        }
    }
    private fun handleManualCaptureFromAccessibility() {
        Log.d(TAG, "dispatchManualCapture via accessibility")
        dispatchManualCapture()
    }
    private fun updateOverlayState() {
        val mode = activeCaptureMode ?: preferredCaptureMode
        when {
            isCopyingToClipboard -> {
                stateLabel?.text = getString(R.string.capture_processing)
                stateIcon?.setImageResource(android.R.drawable.stat_sys_upload)
                manualCountLabel?.isVisible = false
            }
            mode == CaptureMode.MANUAL -> {
                if (isCapturing) {
                    stateLabel?.text = getString(R.string.manual_capture_hint)
                    manualCountLabel?.isVisible = true
                    manualCountLabel?.text = getString(R.string.manual_capture_count, manualCaptureCount)
                    stateIcon?.setImageResource(android.R.drawable.ic_menu_camera)
                } else {
                    stateLabel?.text = getString(R.string.capture_start)
                    stateIcon?.setImageResource(android.R.drawable.ic_media_play)
                    manualCountLabel?.isVisible = false
                }
            }
            mode == CaptureMode.AUTO -> {
                if (isCapturing) {
                    manualCountLabel?.isVisible = true
                    manualCountLabel?.text = getString(R.string.manual_capture_count, autoCaptureCount)
                    val labelRes = when (autoScrollState) {
                        AutoScrollController.AutoScrollState.STARTING,
                        AutoScrollController.AutoScrollState.DETECTING_SCROLLBAR -> R.string.auto_capture_waiting
                        AutoScrollController.AutoScrollState.SCROLLING -> R.string.auto_capture_running
                        AutoScrollController.AutoScrollState.COMPLETED -> R.string.auto_capture_finished
                        else -> R.string.capture_start
                    }
                    stateLabel?.text = getString(labelRes)
                    stateIcon?.setImageResource(android.R.drawable.ic_menu_camera)
                } else {
                    stateLabel?.text = getString(R.string.capture_start)
                    stateIcon?.setImageResource(android.R.drawable.ic_media_play)
                    manualCountLabel?.isVisible = false
                }
            }
        }
        refreshManualCaptureArmedState()
    }
    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when {
            isCapturing && isRecordingActive -> getString(R.string.notification_title_recording)
            isCapturing -> getString(R.string.notification_title_waiting)
            else -> getString(R.string.notification_title_ready)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setOngoing(true)
            .setContentIntent(pendingStop)
        if (isCapturing) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_stop),
                pendingStop
            )
        }
        return builder.build()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        notificationManager?.createNotificationChannel(channel)
    }
    private fun copyImagesToClipboardBlocking(bitmaps: List<Bitmap>): Boolean {
        if (bitmaps.isEmpty()) return false
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
        val byteArrays = bitmaps.mapNotNull { bitmap ->
            ByteArrayOutputStream().use { stream ->
                val success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                if (!success) return@mapNotNull null
                stream.toByteArray()
            }
        }
        if (byteArrays.isEmpty()) {
            CapturedImageStore.clear()
            return false
        }
        CapturedImageStore.updateFromPngBytes(byteArrays)
        val uris = InMemoryImageProvider.replaceAll(applicationContext, byteArrays)
        if (uris.isEmpty()) {
            CapturedImageStore.clear()
            return false
        }
        val mimeTypes = arrayOf(ClipDescription.MIMETYPE_TEXT_URILIST, InMemoryImageProvider.mimeType())
        val description = ClipDescription("ChronoCapture", mimeTypes)
        val uriListText = uris.joinToString(separator = "\r\n") { it.toString() }
        val clip = ClipData(description, ClipData.Item(uriListText))
        uris.forEach { uri ->
            clip.addItem(contentResolver, ClipData.Item(uri))
        }
        clipboard.setPrimaryClip(clip)
        Log.d(TAG, "Copied ${bitmaps.size} bitmaps as ${uris.size} clipboard items (+text item)")
        return true
    }
    private fun launchMergeTool() {
        val intent = Intent(applicationContext, MergeWebActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            mainHandler.post {
                Toast.makeText(
                    applicationContext,
                    R.string.merge_tool_launch_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    private fun enterForeground() {
        if (isForeground) {
            updateForegroundNotification()
            return
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
    }
    private fun updateForegroundNotification() {
        if (!isForeground) return
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    private fun exitForeground() {
        if (!isForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        isForeground = false
    }
    private inner class OverlayDragTouchListener(
        private val onTap: () -> Unit
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var isDragging = false
        private val touchSlop = ViewConfiguration.get(this@FloatingCaptureService).scaledTouchSlop
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            return when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    showTrashArea()
                    updateTrashHighlight(false)
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + deltaX
                        params.y = initialY + deltaY
                        windowManager.updateViewLayout(overlayView, params)
                        val inside = isPointInTrash(event.rawX, event.rawY)
                        updateTrashHighlight(inside)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val inside = isPointInTrash(event.rawX, event.rawY)
                    hideTrashArea()
                    if (inside) {
                        dismissOverlayAndStop()
                    } else if (!isDragging) {
                        onTap()
                    }
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideTrashArea()
                    updateTrashHighlight(false)
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }
    private fun refreshManualCaptureArmedState() {
        manualCaptureArmed = activeCaptureMode == CaptureMode.MANUAL && isCapturing && !isCopyingToClipboard
    }
    companion object {
        const val ACTION_SHOW_OVERLAY = "com.yusir.chronoscamera.action.SHOW_OVERLAY"
        const val ACTION_PROJECTION_GRANTED = "com.yusir.chronoscamera.action.PROJECTION_GRANTED"
        const val ACTION_STOP = "com.yusir.chronoscamera.action.STOP"
        const val ACTION_REFRESH_MODE = "com.yusir.chronoscamera.action.REFRESH_MODE"
        const val ACTION_OVERLAY_STATE_CHANGED = "com.yusir.chronoscamera.action.OVERLAY_STATE_CHANGED"
        const val EXTRA_OVERLAY_VISIBLE = "extra_overlay_visible"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "chrono_capture_channel"
        private const val NOTIFICATION_ID = 42
        private const val MANUAL_CAPTURE_DEBOUNCE_MS = 120L
        private const val TAG = "FloatingCaptureSvc"
        @Volatile
        var isOverlayVisible: Boolean = false
        @Volatile
        private var activeService: FloatingCaptureService? = null
        @Volatile
        private var manualCaptureArmed: Boolean = false
        internal fun isManualCaptureArmed(): Boolean = manualCaptureArmed
        internal fun triggerManualCaptureFromAccessibility() {
            activeService?.mainHandler?.post {
                activeService?.handleManualCaptureFromAccessibility()
            }
        }
    }
}