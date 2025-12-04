package com.yusir.chronoscamera
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
class ProjectionPermissionActivity : ComponentActivity() {
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                notifyService(result.resultCode, result.data!!)
            }
            finish()
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
    private fun notifyService(resultCode: Int, data: Intent) {
        val intent = Intent(this, FloatingCaptureService::class.java).apply {
            action = FloatingCaptureService.ACTION_PROJECTION_GRANTED
            putExtra(FloatingCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FloatingCaptureService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}