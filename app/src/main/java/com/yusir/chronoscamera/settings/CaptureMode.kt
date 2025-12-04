package com.yusir.chronoscamera.settings
enum class CaptureMode(val storageKey: String) {
    AUTO("auto"),
    MANUAL("manual");
    companion object {
        fun fromKey(key: String?): CaptureMode = when (key) {
            MANUAL.storageKey -> MANUAL
            "scroll" -> AUTO
            else -> AUTO
        }
    }
}