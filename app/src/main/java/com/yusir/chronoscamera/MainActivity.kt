package com.yusir.chronoscamera
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yusir.chronoscamera.FloatingCaptureService
import com.yusir.chronoscamera.databinding.ActivityMainBinding
import com.yusir.chronoscamera.settings.CaptureMode
import com.yusir.chronoscamera.settings.SettingsActivity
import com.yusir.chronoscamera.settings.UserPreferences
import com.yusir.chronoscamera.update.UpdateChecker
import kotlinx.coroutines.launch
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var overlayActive: Boolean = false
    private var isToggleUpdating = false
    private val overlayStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingCaptureService.ACTION_OVERLAY_STATE_CHANGED) {
                overlayActive = intent.getBooleanExtra(FloatingCaptureService.EXTRA_OVERLAY_VISIBLE, false)
                updateStartButton()
            }
        }
    }
    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                ensureNotificationPermissionAndStartService()
            }
        }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startOverlayService()
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.topAppBar.title = ""
        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        setupModeToggle()
        overlayActive = FloatingCaptureService.isOverlayVisible
        updateStartButton()
        binding.startOverlayButton.setOnClickListener {
            if (overlayActive) {
                stopOverlayService()
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                } else {
                    ensureNotificationPermissionAndStartService()
                }
            }
        }
        checkForUpdates()
    }
    private fun checkForUpdates() {
        lifecycleScope.launch {
            val updateInfo = UpdateChecker.checkForUpdate(this@MainActivity)
            if (updateInfo?.hasUpdate == true) {
                showUpdateDialog(updateInfo)
            }
        }
    }
    private fun showUpdateDialog(updateInfo: UpdateChecker.UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, updateInfo.currentVersion, updateInfo.latestVersion))
            .setPositiveButton(R.string.update_download) { _, _ ->
                openDownloadPage(updateInfo.releasePageUrl)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }
    private fun openDownloadPage(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(FloatingCaptureService.ACTION_OVERLAY_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(overlayStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(overlayStateReceiver, filter)
        }
    }
    override fun onResume() {
        super.onResume()
        overlayActive = FloatingCaptureService.isOverlayVisible
        updateStartButton()
    }
    override fun onStop() {
        unregisterReceiver(overlayStateReceiver)
        super.onStop()
    }
    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        overlayPermissionLauncher.launch(intent)
    }
    private fun ensureNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startOverlayService()
        }
    }
    private fun startOverlayService() {
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityServiceDialog()
            return  
        }
        val intent = Intent(this, FloatingCaptureService::class.java).apply {
            action = FloatingCaptureService.ACTION_SHOW_OVERLAY
        }
        startService(intent)
    }
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val ourServiceClass = VolumeKeyAccessibilityService::class.java.name
        return enabledServices.any { serviceInfo ->
            serviceInfo.resolveInfo?.serviceInfo?.let { info ->
                info.packageName == packageName && info.name == ourServiceClass
            } ?: false
        }
    }
    private fun showAccessibilityServiceDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_dialog_title)
            .setMessage(R.string.accessibility_dialog_message)
            .setPositiveButton(R.string.accessibility_dialog_open_settings) { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton(R.string.accessibility_dialog_later, null)
            .show()
    }
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.accessibility_settings_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
    private fun stopOverlayService() {
        val intent = Intent(this, FloatingCaptureService::class.java).apply {
            action = FloatingCaptureService.ACTION_STOP
        }
        startService(intent)
    }
    private fun updateStartButton() {
        binding.startOverlayButton.text = if (overlayActive) {
            getString(R.string.stop_overlay)
        } else {
            getString(R.string.start_overlay)
        }
    }
    private fun setupModeToggle() {
        val currentMode = UserPreferences.getCaptureMode(this)
        isToggleUpdating = true
        when (currentMode) {
            CaptureMode.AUTO -> binding.modeToggleGroup.check(binding.autoModeButton.id)
            CaptureMode.MANUAL -> binding.modeToggleGroup.check(binding.manualModeButton.id)
        }
        isToggleUpdating = false
        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isToggleUpdating || !isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == binding.manualModeButton.id) CaptureMode.MANUAL else CaptureMode.AUTO
            UserPreferences.setCaptureMode(this, mode)
            if (FloatingCaptureService.isOverlayVisible) {
                val intent = Intent(this, FloatingCaptureService::class.java).apply {
                    action = FloatingCaptureService.ACTION_REFRESH_MODE
                }
                startService(intent)
            }
        }
    }
}