package com.yusir.chronoscamera.settings
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import com.yusir.chronoscamera.autoscroll.ScrollMethod
object UserPreferences {
    private const val KEY_FRAME_INTERVAL = "pref_frame_interval"
    private const val KEY_LANGUAGE = "pref_language"
    private const val KEY_CAPTURE_MODE = "pref_capture_mode"
    private const val KEY_SCROLL_PER_CAPTURE = "pref_scroll_per_capture"
    private const val KEY_SCROLL_METHOD = "pref_scroll_method"
    private const val KEY_INITIAL_DELAY = "pref_initial_delay"
    private const val DEFAULT_FRAME_INTERVAL = 60
    private const val MIN_FRAME_INTERVAL = 60
    private const val MAX_FRAME_INTERVAL = 300
    private const val STEP_FRAME_INTERVAL = 15
    private const val DEFAULT_SCROLL_PER_CAPTURE = 4
    private const val MIN_SCROLL_PER_CAPTURE = 1
    private const val MAX_SCROLL_PER_CAPTURE = 10
    private const val DEFAULT_INITIAL_DELAY = 2000
    private const val MIN_INITIAL_DELAY = 1000
    private const val MAX_INITIAL_DELAY = 5000
    private const val STEP_INITIAL_DELAY = 500
    val frameIntervalOptions: List<Int> = buildList {
        var current = MIN_FRAME_INTERVAL
        while (current <= MAX_FRAME_INTERVAL) {
            add(current)
            current += STEP_FRAME_INTERVAL
        }
    }
    fun getFrameInterval(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getInt(KEY_FRAME_INTERVAL, DEFAULT_FRAME_INTERVAL)
        return frameIntervalOptions.firstOrNull { it == stored } ?: DEFAULT_FRAME_INTERVAL
    }
    fun setFrameInterval(context: Context, value: Int) {
        val normalized = frameIntervalOptions.firstOrNull { it == value } ?: DEFAULT_FRAME_INTERVAL
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putInt(KEY_FRAME_INTERVAL, normalized)
            .apply()
    }
    fun getCaptureMode(context: Context): CaptureMode {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getString(KEY_CAPTURE_MODE, null)
        return CaptureMode.fromKey(stored)
    }
    fun setCaptureMode(context: Context, mode: CaptureMode) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(KEY_CAPTURE_MODE, mode.storageKey)
            .apply()
    }
    val scrollPerCaptureOptions: List<Int> = (MIN_SCROLL_PER_CAPTURE..MAX_SCROLL_PER_CAPTURE).toList()
    fun getScrollPerCapture(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getInt(KEY_SCROLL_PER_CAPTURE, DEFAULT_SCROLL_PER_CAPTURE)
        return stored.coerceIn(MIN_SCROLL_PER_CAPTURE, MAX_SCROLL_PER_CAPTURE)
    }
    fun setScrollPerCapture(context: Context, value: Int) {
        val normalized = value.coerceIn(MIN_SCROLL_PER_CAPTURE, MAX_SCROLL_PER_CAPTURE)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putInt(KEY_SCROLL_PER_CAPTURE, normalized)
            .apply()
    }
    fun getScrollMethod(context: Context): ScrollMethod {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getString(KEY_SCROLL_METHOD, null)
        return ScrollMethod.fromKey(stored)
    }
    fun setScrollMethod(context: Context, method: ScrollMethod) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(KEY_SCROLL_METHOD, method.storageKey)
            .apply()
    }
    val initialDelayOptions: List<Int> = buildList {
        var current = MIN_INITIAL_DELAY
        while (current <= MAX_INITIAL_DELAY) {
            add(current)
            current += STEP_INITIAL_DELAY
        }
    }
    fun getInitialDelay(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getInt(KEY_INITIAL_DELAY, DEFAULT_INITIAL_DELAY)
        return initialDelayOptions.firstOrNull { it == stored } ?: DEFAULT_INITIAL_DELAY
    }
    fun setInitialDelay(context: Context, value: Int) {
        val normalized = initialDelayOptions.firstOrNull { it == value } ?: DEFAULT_INITIAL_DELAY
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putInt(KEY_INITIAL_DELAY, normalized)
            .apply()
    }
    fun getLanguage(context: Context): LanguagePreference {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = prefs.getString(KEY_LANGUAGE, null) ?: return LanguagePreference.SYSTEM
        return LanguagePreference.fromKey(key)
    }
    fun setLanguage(context: Context, language: LanguagePreference) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(KEY_LANGUAGE, language.storageKey)
            .apply()
        applyLanguage(language)
    }
    fun applyStoredLocale(context: Context) {
        val preference = getLanguage(context)
        applyLanguage(preference)
    }
    private fun applyLanguage(preference: LanguagePreference) {
        val locales = when (preference) {
            LanguagePreference.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            LanguagePreference.JAPANESE -> LocaleListCompat.forLanguageTags("ja")
            LanguagePreference.ENGLISH -> LocaleListCompat.forLanguageTags("en")
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
enum class LanguagePreference(val storageKey: String) {
    SYSTEM("system"),
    JAPANESE("ja"),
    ENGLISH("en");
    companion object {
        fun fromKey(key: String): LanguagePreference = when (key) {
            JAPANESE.storageKey -> JAPANESE
            ENGLISH.storageKey -> ENGLISH
            else -> SYSTEM
        }
    }
}