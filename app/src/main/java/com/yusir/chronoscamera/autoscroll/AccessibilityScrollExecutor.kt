package com.yusir.chronoscamera.autoscroll
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
class AccessibilityScrollExecutor : ScrollExecutor {
    override fun isAvailable(): Boolean {
        return instance?.isServiceConnected() == true
    }
    override fun scrollDown(screenWidth: Int, screenHeight: Int): Boolean {
        val service = instance
        if (service == null || !service.isServiceConnected()) {
            Log.w(TAG, "Accessibility service not available")
            return false
        }
        return try {
            service.performScrollDown(screenWidth, screenHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Error during scroll", e)
            false
        }
    }
    override fun getMethodName(): String = "Accessibility"
    companion object {
        private const val TAG = "AccessibilityScroll"
        @Volatile
        private var instance: AccessibilityScrollService? = null
        internal fun setInstance(service: AccessibilityScrollService?) {
            instance = service
        }
        fun getInstance(): AccessibilityScrollService? = instance
    }
}
abstract class AccessibilityScrollService : AccessibilityService() {
    private val connected = AtomicBoolean(false)
    override fun onServiceConnected() {
        super.onServiceConnected()
        connected.set(true)
        AccessibilityScrollExecutor.setInstance(this)
        Log.d(TAG, "AccessibilityScrollService connected")
    }
    override fun onDestroy() {
        connected.set(false)
        AccessibilityScrollExecutor.setInstance(null)
        Log.d(TAG, "AccessibilityScrollService destroyed")
        super.onDestroy()
    }
    fun isServiceConnected(): Boolean = connected.get()
    fun performScrollDown(screenWidth: Int, screenHeight: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture requires API 24+")
            return false
        }
        if (!connected.get()) {
            Log.w(TAG, "Service not connected")
            return false
        }
        val x = screenWidth * 0.5f
        val startY = screenHeight * 0.70f
        val endY = screenHeight * 0.55f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 600))
            .build()
        val latch = CountDownLatch(1)
        val success = AtomicBoolean(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Scroll gesture completed")
                success.set(true)
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Scroll gesture cancelled")
                success.set(false)
                latch.countDown()
            }
        }
        val dispatched = try {
            dispatchGesture(gesture, callback, null)
        } catch (e: Exception) {
            Log.e(TAG, "Exception dispatching gesture", e)
            return false
        }
        if (!dispatched) {
            Log.e(TAG, "Failed to dispatch scroll gesture")
            return false
        }
        return try {
            val completed = latch.await(2, TimeUnit.SECONDS)
            if (!completed) {
                Log.w(TAG, "Gesture timeout")
            }
            success.get()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for gesture", e)
            Thread.currentThread().interrupt()
            false
        }
    }
    companion object {
        private const val TAG = "AccessibilityScroll"
    }
}