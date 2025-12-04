package com.yusir.chronoscamera.autoscroll
interface ScrollExecutor {
    fun isAvailable(): Boolean
    fun scrollDown(screenWidth: Int, screenHeight: Int): Boolean
    fun getMethodName(): String
}