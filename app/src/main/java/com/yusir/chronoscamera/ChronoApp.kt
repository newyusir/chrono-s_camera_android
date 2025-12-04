package com.yusir.chronoscamera
import android.app.Application
import com.yusir.chronoscamera.settings.UserPreferences
import com.yusir.chronoscamera.shizuku.ShizukuInputService
class ChronoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UserPreferences.applyStoredLocale(this)
        ShizukuInputService.init()
    }
    override fun onTerminate() {
        ShizukuInputService.cleanup()
        super.onTerminate()
    }
}