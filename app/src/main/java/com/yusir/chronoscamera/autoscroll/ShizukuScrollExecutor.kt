package com.yusir.chronoscamera.autoscroll
import android.util.Log
import com.yusir.chronoscamera.shizuku.ShizukuInputService
class ShizukuScrollExecutor : ScrollExecutor {
    override fun isAvailable(): Boolean {
        return ShizukuInputService.isAvailable()
    }
    override fun scrollDown(screenWidth: Int, screenHeight: Int): Boolean {
        Log.d(TAG, "Scrolling via Shizuku")
        return ShizukuInputService.scrollDown(screenWidth, screenHeight)
    }
    override fun getMethodName(): String = "Shizuku"
    companion object {
        private const val TAG = "ShizukuScrollExecutor"
    }
}