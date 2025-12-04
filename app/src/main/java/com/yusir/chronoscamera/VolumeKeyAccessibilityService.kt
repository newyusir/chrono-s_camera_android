package com.yusir.chronoscamera
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.yusir.chronoscamera.autoscroll.AccessibilityScrollService
class VolumeKeyAccessibilityService : AccessibilityScrollService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "VolumeKeyAccessibilityService connected")
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } ?: AccessibilityServiceInfo().apply {
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            notificationTimeout = 0
        }
        Log.d(TAG, "ServiceInfo flags set: ${serviceInfo?.flags}")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }
    override fun onInterrupt() {
    }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.d(TAG, "onKeyEvent received: keyCode=${event.keyCode} action=${event.action}")
        val manualActive = FloatingCaptureService.isManualCaptureArmed()
        Log.d(TAG, "manualCaptureArmed=$manualActive")
        if (!manualActive) {
            return super.onKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    Log.d(TAG, "Accessibility intercepted volume down")
                    FloatingCaptureService.triggerManualCaptureFromAccessibility()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    return true
                }
            }
        }
        return super.onKeyEvent(event)
    }
    companion object {
        private const val TAG = "VolumeKeyService"
    }
}