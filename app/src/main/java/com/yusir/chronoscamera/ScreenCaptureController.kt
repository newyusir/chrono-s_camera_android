package com.yusir.chronoscamera
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.yusir.chronoscamera.settings.CaptureMode
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.jvm.Volatile
class ScreenCaptureController(
    private val context: Context,
    private val resultCode: Int,
    private val resultData: Intent,
    private val frameInterval: Int,
    private val captureMode: CaptureMode,
    private val onScrollDetected: () -> Unit = {},
    private val onCapturePaused: () -> Unit = {},
    private val onProjectionStopped: () -> Unit = {}
) {
    private val projectionManager: MediaProjectionManager =
        context.getSystemService(MediaProjectionManager::class.java)
    private val windowManager: WindowManager =
        context.getSystemService(WindowManager::class.java)
    private val handlerThread = HandlerThread("ChronoCaptureThread")
    private val capturedBitmaps = CopyOnWriteArrayList<Bitmap>()
    private var handler: Handler? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val captureIntervalMs: Long = frameInterval
        .coerceIn(MIN_FRAME_INTERVAL, MAX_FRAME_INTERVAL)
        .let { normalized ->
            val clamped = if (normalized % FRAME_INTERVAL_STEP == 0) normalized else normalized - (normalized % FRAME_INTERVAL_STEP)
            maxOf(clamped, MIN_FRAME_INTERVAL)
        }
        .let { normalized ->
            val multiplier = normalized.toDouble() / MIN_FRAME_INTERVAL.toDouble()
            (BASE_INTERVAL_MS * multiplier).roundToLong().coerceAtLeast(BASE_INTERVAL_MS)
        }
    private val pauseThresholdMs: Long = captureIntervalMs * 2
    private var lastSavedAt: Long = 0L
    private var samplingBuffer: IntArray? = null
    private var capturingActive: Boolean = false
    private var hasNotifiedMovement = false
    @Volatile private var isStopping = false
    private var noMovementStartAt: Long = -1L
    private var latestImage: Image? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseResources(stopProjection = false)
            onProjectionStopped.invoke()
        }
    }
    fun start() {
        if (mediaProjection != null) return
        if (!handlerThread.isAlive) {
            handlerThread.start()
        }
        handler = Handler(handlerThread.looper)
        val metrics = DisplayMetrics().apply {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(this)
        }
        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            MAX_IMAGES
        ).also { reader ->
            reader.setOnImageAvailableListener({ onImageAvailable(reader) }, handler)
        }
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(projectionCallback, handler)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
    lastSavedAt = 0L
    capturingActive = false
    hasNotifiedMovement = false
    isStopping = false
    noMovementStartAt = -1L
    latestImage = null
    }
    fun stop() {
        isStopping = true
        releaseResources(stopProjection = true)
    }
    private fun releaseResources(stopProjection: Boolean = false) {
        isStopping = true
        val currentHandler = handler
        if (currentHandler != null && currentHandler.looper.thread != Thread.currentThread()) {
            currentHandler.post { performRelease(stopProjection) }
        } else {
            performRelease(stopProjection)
        }
    }
    private fun performRelease(stopProjection: Boolean) {
        imageReader?.setOnImageAvailableListener(null, null)
        latestImage?.close()
        latestImage = null
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        if (stopProjection) {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        }
        mediaProjection = null
        handler = null
        samplingBuffer = null
        capturingActive = false
        hasNotifiedMovement = false
        isStopping = false
        noMovementStartAt = -1L
    }
    fun release() {
        handlerThread.quitSafely()
    }
    fun getCapturedBitmaps(): List<Bitmap> = capturedBitmaps.toList()
    fun clear() {
        capturedBitmaps.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        capturedBitmaps.clear()
        samplingBuffer = null
        hasNotifiedMovement = false
        noMovementStartAt = -1L
        latestImage?.close()
        latestImage = null
    }
    fun captureManualFrame(): Int {
        if (captureMode != CaptureMode.MANUAL) return capturedBitmaps.size
        val currentHandler = handler
        return if (currentHandler != null && currentHandler.looper.thread != Thread.currentThread()) {
            var result = capturedBitmaps.size
            val latch = CountDownLatch(1)
            currentHandler.post {
                result = captureManualFrameInternal()
                latch.countDown()
            }
            try {
                latch.await(500, TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            result
        } else {
            captureManualFrameInternal()
        }
    }
    private fun captureManualFrameInternal(): Int {
        val image = latestImage?.also { latestImage = null } ?: imageReader?.acquireLatestImage()
        var count = capturedBitmaps.size
        image?.use { frame ->
            val bitmap = try {
                frame.toBitmap()
            } catch (ise: IllegalStateException) {
                null
            } catch (bue: BufferUnderflowException) {
                null
            }
            if (bitmap != null) {
                capturedBitmaps.add(bitmap)
                count = capturedBitmaps.size
                Log.d(TAG, "Captured frames=${capturedBitmaps.size}")
            }
        }
        return count
    }
    private fun onImageAvailable(reader: ImageReader) {
        if (captureMode == CaptureMode.MANUAL) {
            onManualImageAvailable(reader)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val image = reader.acquireLatestImage() ?: return
        image.use { capturedImage ->
            if (isStopping) {
                return
            }
            val bitmap = try {
                capturedImage.toBitmap()
            } catch (ise: IllegalStateException) {
                null
            } catch (bue: BufferUnderflowException) {
                null
            } ?: return
            val sample = sampleBitmap(bitmap)
            val movementDetected = hasMeaningfulChange(samplingBuffer, sample)
            samplingBuffer = sample
            if (!capturingActive) {
                if (!movementDetected) {
                    bitmap.recycle()
                    return
                }
                capturingActive = true
                noMovementStartAt = -1L
                if (!hasNotifiedMovement) {
                    hasNotifiedMovement = true
                    onScrollDetected.invoke()
                }
                lastSavedAt = now - captureIntervalMs
            } else {
                if (!movementDetected) {
                    if (noMovementStartAt < 0) {
                        noMovementStartAt = now
                    }
                    if (now - noMovementStartAt >= pauseThresholdMs) {
                        capturingActive = false
                        hasNotifiedMovement = false
                        noMovementStartAt = -1L
                        onCapturePaused.invoke()
                    }
                    bitmap.recycle()
                    return
                } else {
                    noMovementStartAt = -1L
                }
            }
            if (now - lastSavedAt < captureIntervalMs) {
                bitmap.recycle()
                return
            }
            capturedBitmaps.add(bitmap)
            Log.d(TAG, "Captured frames=${capturedBitmaps.size}")
            lastSavedAt = now
        }
    }
    private fun onManualImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        latestImage?.close()
        latestImage = image
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
    private fun sampleBitmap(bitmap: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, false)
        val buffer = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        scaled.getPixels(buffer, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        scaled.recycle()
        return buffer
    }
    private fun hasMeaningfulChange(previous: IntArray?, current: IntArray): Boolean {
        if (previous == null) return false
        val length = min(previous.size, current.size)
        var diffSum = 0L
        var sampled = 0
        var index = 0
        while (index < length) {
            val prevPixel = previous[index]
            val currPixel = current[index]
            diffSum += colorDistance(prevPixel, currPixel)
            sampled++
            index += SAMPLE_STRIDE
        }
        if (sampled == 0) return false
        val avgDiff = diffSum.toFloat() / sampled
        return avgDiff >= MOVEMENT_THRESHOLD
    }
    private fun colorDistance(first: Int, second: Int): Int {
        val dr = abs(Color.red(first) - Color.red(second))
        val dg = abs(Color.green(first) - Color.green(second))
        val db = abs(Color.blue(first) - Color.blue(second))
        return dr + dg + db
    }
    private inline fun Image.use(block: (Image) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
    companion object {
    private const val MAX_IMAGES = 5
    private const val DEFAULT_FRAME_INTERVAL = 60
    private const val MIN_FRAME_INTERVAL = 60
    private const val MAX_FRAME_INTERVAL = 300
    private const val FRAME_INTERVAL_STEP = 15
    private const val BASE_INTERVAL_MS = 67L
        private const val VIRTUAL_DISPLAY_NAME = "chrono_capture_display"
        private const val SAMPLE_SIZE = 64
        private const val SAMPLE_STRIDE = 4
        private const val MOVEMENT_THRESHOLD = 18f
        private const val TAG = "ScreenCaptureCtrl"
    }
}