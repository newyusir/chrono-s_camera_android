package com.yusir.chronoscamera.settings
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.yusir.chronoscamera.R
import com.yusir.chronoscamera.autoscroll.ScrollMethod
import com.yusir.chronoscamera.databinding.ActivitySettingsBinding
private data class LanguageSelection(val preference: LanguagePreference, val buttonId: Int)
private data class ScrollMethodSelection(val method: ScrollMethod, val buttonId: Int)
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val languageSelections = listOf(
        LanguageSelection(LanguagePreference.SYSTEM, R.id.languageSystemButton),
        LanguageSelection(LanguagePreference.JAPANESE, R.id.languageJaButton),
        LanguageSelection(LanguagePreference.ENGLISH, R.id.languageEnButton)
    )
    private val scrollMethodSelections = listOf(
        ScrollMethodSelection(ScrollMethod.ACCESSIBILITY, R.id.scrollMethodAccessibilityButton),
        ScrollMethodSelection(ScrollMethod.SHIZUKU, R.id.scrollMethodShizukuButton)
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        setupScrollMethodGroup(binding.scrollMethodToggleGroup)
        setupInitialDelaySlider()
        setupLanguageGroup(binding.languageToggleGroup)
    }
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
    private fun setupScrollMethodGroup(group: MaterialButtonToggleGroup) {
        var currentMethod = UserPreferences.getScrollMethod(this)
        val checkedId = scrollMethodSelections.firstOrNull { it.method == currentMethod }?.buttonId
            ?: R.id.scrollMethodAccessibilityButton
        group.check(checkedId)
        group.addOnButtonCheckedListener { toggleGroup, checkedIdInternal, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            val selection = scrollMethodSelections.firstOrNull { it.buttonId == checkedIdInternal } ?: return@addOnButtonCheckedListener
            if (selection.method == currentMethod) {
                return@addOnButtonCheckedListener
            }
            currentMethod = selection.method
            UserPreferences.setScrollMethod(this, selection.method)
            scrollMethodSelections
                .filter { it.buttonId != checkedIdInternal }
                .forEach { toggleGroup.uncheck(it.buttonId) }
        }
    }
    private fun setupInitialDelaySlider() {
        val currentDelay = UserPreferences.getInitialDelay(this)
        binding.initialDelaySlider.value = currentDelay.toFloat()
        updateInitialDelayValueText(currentDelay)
        binding.initialDelaySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val delayMs = value.toInt()
                UserPreferences.setInitialDelay(this, delayMs)
                updateInitialDelayValueText(delayMs)
            }
        }
    }
    private fun updateInitialDelayValueText(delayMs: Int) {
        val seconds = delayMs / 1000f
        binding.initialDelayValue.text = getString(R.string.settings_initial_delay_value, seconds)
    }
    private fun setupLanguageGroup(group: MaterialButtonToggleGroup) {
        var currentPreference = UserPreferences.getLanguage(this)
        val checkedId = languageSelections.firstOrNull { it.preference == currentPreference }?.buttonId
            ?: R.id.languageSystemButton
        group.check(checkedId)
        group.addOnButtonCheckedListener { toggleGroup, checkedIdInternal, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            val selection = languageSelections.firstOrNull { it.buttonId == checkedIdInternal } ?: return@addOnButtonCheckedListener
            if (selection.preference == currentPreference) {
                return@addOnButtonCheckedListener
            }
            currentPreference = selection.preference
            UserPreferences.setLanguage(this, selection.preference)
            recreate()
            languageSelections
                .filter { it.buttonId != checkedIdInternal }
                .forEach { toggleGroup.uncheck(it.buttonId) }
        }
    }
}