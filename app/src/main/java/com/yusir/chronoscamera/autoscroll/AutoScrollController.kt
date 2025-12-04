package com.yusir.chronoscamera.autoscroll
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
class AutoScrollController(
    private val context: Context,
    private val resultCode: Int,
    private val resultData: Intent,
    private val scrollExecutor: ScrollExecutor,
    private val scrollsPerCapture: Int = 4,
    private val initialDelayMs: Long = 2000L,
    private val onStateChanged: (AutoScrollState) -> Unit,
    private val onCaptureCountChanged: (Int) -> Unit,
    private val onCompleted: (List<Bitmap>) -> Unit,
    private val onError: (String) -> Unit,
    private val onBeforeFirstCapture: (() -> Unit)? = null
) {
    private val projectionManager: MediaProjectionManager =
        context.getSystemService(MediaProjectionManager::class.java)
    private val windowManager: WindowManager =
        context.getSystemService(WindowManager::class.java)
    private val handlerThread = HandlerThread("AutoScrollThread")
    private val capturedBitmaps = CopyOnWriteArrayList<Bitmap>()
    private var handler: Handler? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val isRunning = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)
    private var scrollCount = 0
    private var latestBitmap: Bitmap? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private val scrollSettleDelayMs = 800L  
    private val captureDelayMs = 300L  
    private val maxScrollsWithoutChange = 1  
    private var lastThumbTop = -1
    private var scrollsWithoutChange = 0
    private var lastCapturedBitmap: Bitmap? = null  
    private var topMargin = 0
    private var bottomMargin = 0
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped")
            stop()
        }
    }
    enum class AutoScrollState {
        IDLE,
        STARTING,
        DETECTING_SCROLLBAR,
        SCROLLING,
        COMPLETED,
        ERROR
    }
    fun setScreenMargins(top: Int, bottom: Int) {
        topMargin = top
        bottomMargin = bottom
    }
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Already running")
            return
        }
        if (!scrollExecutor.isAvailable()) {
            Log.e(TAG, "${scrollExecutor.getMethodName()} not available")
            onError("${scrollExecutor.getMethodName()} is not available")
            isRunning.set(false)
            return
        }
        Log.d(TAG, "Starting auto scroll with ${scrollExecutor.getMethodName()} method")
        isStopping.set(false)
        scrollCount = 0
        capturedBitmaps.clear()
        latestBitmap = null
        lastThumbTop = -1
        scrollsWithoutChange = 0
        lastCapturedBitmap?.recycle()
        lastCapturedBitmap = null
        onStateChanged(AutoScrollState.STARTING)
        if (!handlerThread.isAlive) {
            handlerThread.start()
        }
        val looper = handlerThread.looper
        if (looper == null) {
            Log.e(TAG, "Failed to get looper from HandlerThread")
            onError("Internal error: Failed to initialize")
            isRunning.set(false)
            return
        }
        handler = Handler(looper)
        val metrics = DisplayMetrics().apply {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(this)
        }
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            3
        ).also { reader ->
            reader.setOnImageAvailableListener({ onImageAvailable(it) }, handler)
        }
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            imageReader?.close()
            imageReader = null
            onError("Failed to get screen capture permission")
            isRunning.set(false)
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, handler)
        virtualDisplay = projection.createVirtualDisplay(
            "AutoScrollCapture",
            screenWidth,
            screenHeight,
            metrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
        if (virtualDisplay == null) {
            Log.e(TAG, "Failed to create VirtualDisplay")
            projection.unregisterCallback(projectionCallback)
            projection.stop()
            mediaProjection = null
            imageReader?.close()
            imageReader = null
            onError("Failed to start screen capture")
            isRunning.set(false)
            return
        }
        handler?.postDelayed({
            startAutoScrollLoop()
        }, initialDelayMs) 
    }
    fun stop() {
        if (isStopping.getAndSet(true)) return
        Log.d(TAG, "Stopping auto scroll")
        stopAndCleanup()
    }
    private fun stopAndCleanup() {
        handler?.removeCallbacksAndMessages(null)
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing imageReader", e)
        }
        imageReader = null
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing virtualDisplay", e)
        }
        virtualDisplay = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping mediaProjection", e)
        }
        mediaProjection = null
        isRunning.set(false)
        onStateChanged(AutoScrollState.IDLE)
    }
    fun release() {
        stop()
        handlerThread.quitSafely()
    }
    fun getCapturedBitmaps(): List<Bitmap> = capturedBitmaps.toList()
    fun clear() {
        capturedBitmaps.forEach { if (!it.isRecycled) it.recycle() }
        capturedBitmaps.clear()
        latestBitmap?.recycle()
        latestBitmap = null
        lastCapturedBitmap?.recycle()
        lastCapturedBitmap = null
    }
    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val bitmap = image.toBitmap()
            if (bitmap != null) {
                latestBitmap?.recycle()
                latestBitmap = bitmap
            }
        } finally {
            image.close()
        }
    }
    private fun startAutoScrollLoop() {
        if (isStopping.get()) return
        onStateChanged(AutoScrollState.DETECTING_SCROLLBAR)
        onBeforeFirstCapture?.invoke()
        handler?.postDelayed({
            performAutoScrollCycle()
        }, 500) 
    }
    private fun performAutoScrollCycle() {
        if (isStopping.get()) return
        val currentBitmap = latestBitmap
        if (currentBitmap == null) {
            Log.w(TAG, "No bitmap available, retrying...")
            handler?.postDelayed({ performAutoScrollCycle() }, 100)
            return
        }
        val scrollbarInfo = ScrollbarDetector.detectScrollbar(
            currentBitmap,
            topMargin,
            bottomMargin
        )
        if (!scrollbarInfo.found) {
            Log.w(TAG, "Scrollbar not found, retrying...")
            handler?.postDelayed({ performAutoScrollCycle() }, 200)
            return
        }
        onStateChanged(AutoScrollState.SCROLLING)
        if (capturedBitmaps.isEmpty()) {
            captureCurrentFrame()
        }
        if (scrollbarInfo.isAtBottom) {
            Log.d(TAG, "Reached bottom of scroll")
            captureCurrentFrame()
            completeAutoScroll()
            return
        }
        performScrollAndContinue()
    }
    private fun performScrollAndContinue() {
        if (isStopping.get()) return
        val scrolled = try {
            scrollExecutor.scrollDown(screenWidth, screenHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during scroll", e)
            false
        }
        if (!scrolled) {
            Log.e(TAG, "Failed to scroll via ${scrollExecutor.getMethodName()}")
            isStopping.set(true)
            handler?.post {
                onError("Failed to scroll")
                onStateChanged(AutoScrollState.ERROR)
                stopAndCleanup()
            }
            return
        }
        Log.d(TAG, "Scroll performed via ${scrollExecutor.getMethodName()}, waiting for settle...")
        scrollCount++
        handler?.postDelayed({
            if (isStopping.get()) return@postDelayed
            captureCurrentFrame()
            handler?.postDelayed({
                checkAndContinue()
            }, captureDelayMs)
        }, scrollSettleDelayMs)
    }
    private fun checkAndContinue() {
        if (isStopping.get()) return
        val currentBitmap = latestBitmap
        if (currentBitmap == null) {
            handler?.postDelayed({ checkAndContinue() }, 100)
            return
        }
        val scrollbarInfo = ScrollbarDetector.detectScrollbar(
            currentBitmap,
            topMargin,
            bottomMargin
        )
        val imageChanged = if (lastCapturedBitmap != null) {
            val changed = !areBitmapsSimilar(lastCapturedBitmap!!, currentBitmap)
            if (!changed) {
                scrollsWithoutChange++
                Log.d(TAG, "Image content unchanged (count: $scrollsWithoutChange)")
            } else {
                scrollsWithoutChange = 0
                Log.d(TAG, "Image content changed, continuing...")
            }
            changed
        } else {
            true 
        }
        lastCapturedBitmap?.recycle()
        lastCapturedBitmap = currentBitmap.copy(currentBitmap.config, false)
        val isAtBottom = (scrollbarInfo.found && scrollbarInfo.isAtBottom) || 
                         scrollsWithoutChange >= maxScrollsWithoutChange
        if (isAtBottom) {
            if (scrollsWithoutChange >= maxScrollsWithoutChange) {
                Log.d(TAG, "Reached bottom (no image change for $scrollsWithoutChange scrolls)")
                val framesToRemove = scrollsWithoutChange
                if (framesToRemove > 0 && capturedBitmaps.size >= framesToRemove) {
                    Log.d(TAG, "Removing last $framesToRemove unchanged frames")
                    repeat(framesToRemove) {
                        val removed = capturedBitmaps.removeAt(capturedBitmaps.size - 1)
                        removed.recycle()
                    }
                    onCaptureCountChanged(capturedBitmaps.size)
                }
            } else {
                Log.d(TAG, "Reached bottom after scroll $scrollCount (scrollbar at bottom)")
            }
            completeAutoScroll()
        } else {
            performScrollAndContinue()
        }
    }
    private fun areBitmapsSimilar(bitmap1: Bitmap, bitmap2: Bitmap): Boolean {
        if (bitmap1.width != bitmap2.width || bitmap1.height != bitmap2.height) {
            return false
        }
        val startX = (bitmap1.width * 0.2).toInt()
        val endX = (bitmap1.width * 0.8).toInt()
        val startY = (bitmap1.height * 0.3).toInt()
        val endY = (bitmap1.height * 0.7).toInt()
        var samePixels = 0
        var totalSamples = 0
        val sampleStep = 20 
        for (y in startY until endY step sampleStep) {
            for (x in startX until endX step sampleStep) {
                val pixel1 = bitmap1.getPixel(x, y)
                val pixel2 = bitmap2.getPixel(x, y)
                if (pixel1 == pixel2) {
                    samePixels++
                }
                totalSamples++
            }
        }
        val similarity = samePixels.toFloat() / totalSamples
        Log.d(TAG, "Image similarity: ${(similarity * 100).toInt()}%")
        return similarity > 0.95f
    }
    private fun captureCurrentFrame() {
        val bitmap = latestBitmap
        if (bitmap != null && !bitmap.isRecycled) {
            val copy = bitmap.copy(bitmap.config, false)
            if (copy != null) {
                capturedBitmaps.add(copy)
                Log.d(TAG, "Captured frame ${capturedBitmaps.size}")
                onCaptureCountChanged(capturedBitmaps.size)
            }
        }
    }
    private fun completeAutoScroll() {
        Log.d(TAG, "Auto scroll completed with ${capturedBitmaps.size} captures")
        onStateChanged(AutoScrollState.COMPLETED)
        val bitmaps = capturedBitmaps.toList()
        handler?.post {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
            virtualDisplay?.release()
            virtualDisplay = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
            isRunning.set(false)
            onCompleted(bitmaps)
        }
    }
    private fun Image.toBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer ?: return null
        val imageWidth = width
        val imageHeight = height
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * imageWidth
        val bitmapWidth = imageWidth + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, imageHeight, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, imageWidth, imageHeight)
    }
    companion object {
        private const val TAG = "AutoScrollController"
    }
}