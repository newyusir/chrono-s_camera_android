package com.yusir.chronoscamera.autoscroll
enum class ScrollMethod(val storageKey: String) {
    ACCESSIBILITY("accessibility"),
    SHIZUKU("shizuku");
    companion object {
        fun fromKey(key: String?): ScrollMethod = when (key) {
            SHIZUKU.storageKey -> SHIZUKU
            else -> ACCESSIBILITY 
        }
    }
}