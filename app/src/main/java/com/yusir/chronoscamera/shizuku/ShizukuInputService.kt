package com.yusir.chronoscamera.shizuku
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
object ShizukuInputService {
    private const val TAG = "ShizukuInputService"
    private const val REQUEST_CODE_PERMISSION = 1001
    @Volatile
    private var isPermissionGranted = false
    @Volatile
    private var shellService: IShellService? = null
    @Volatile
    private var isServiceBound = false
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_PERMISSION) {
            isPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission result: granted=$isPermissionGranted")
            if (isPermissionGranted) {
                bindShellService()
            }
        }
    }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        checkPermission()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        isPermissionGranted = false
        shellService = null
        isServiceBound = false
    }
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.yusir.chronoscamera", ShellService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(true)
        .version(1)
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Shell service connected")
            shellService = IShellService.Stub.asInterface(service)
            isServiceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Shell service disconnected")
            shellService = null
            isServiceBound = false
        }
    }
    fun init() {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        if (Shizuku.pingBinder()) {
            checkPermission()
        }
    }
    fun cleanup() {
        unbindShellService()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }
    private fun bindShellService() {
        if (isServiceBound) return
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            Log.d(TAG, "Binding shell service...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind shell service", e)
        }
    }
    private fun unbindShellService() {
        if (!isServiceBound) return
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind shell service", e)
        }
        shellService = null
        isServiceBound = false
    }
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && isPermissionGranted && shellService != null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Shizuku availability", e)
            false
        }
    }
    fun isRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }
    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "Shizuku not running, cannot request permission")
            return
        }
        if (checkPermission()) return
        try {
            Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting Shizuku permission", e)
        }
    }
    fun checkPermission(): Boolean {
        return try {
            val result = Shizuku.checkSelfPermission()
            isPermissionGranted = result == PackageManager.PERMISSION_GRANTED
            if (isPermissionGranted && !isServiceBound) {
                bindShellService()
            }
            isPermissionGranted
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Shizuku permission", e)
            isPermissionGranted = false
            false
        }
    }
    fun executeInputRoll(dx: Int, dy: Int): Boolean {
        val service = shellService
        if (service == null) {
            Log.w(TAG, "Shell service not available for input roll")
            return false
        }
        return try {
            val command = "input roll $dx $dy"
            val result = service.executeCommand(command)
            Log.d(TAG, "Executed: $command, result=$result")
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Error executing input roll", e)
            false
        }
    }
    fun scrollDownRoll(amount: Int = 3): Boolean {
        return executeInputRoll(0, -amount)
    }
    fun scrollUpRoll(amount: Int = 3): Boolean {
        return executeInputRoll(0, amount)
    }
    fun executeSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int = 300): Boolean {
        val service = shellService
        if (service == null) {
            Log.w(TAG, "Shell service not available for swipe")
            return false
        }
        return try {
            val command = "input swipe $startX $startY $endX $endY $durationMs"
            val result = service.executeCommand(command)
            Log.d(TAG, "Executed: $command, result=$result")
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Error executing swipe", e)
            false
        }
    }
    fun scrollDown(screenWidth: Int, screenHeight: Int, scrollDistance: Int = 300): Boolean {
        val x = (screenWidth * 0.5).toInt() 
        val startY = (screenHeight * 0.70).toInt()  
        val endY = (screenHeight * 0.50).toInt()    
        Log.d(TAG, "Scrolling: swipe from ($x, $startY) to ($x, $endY)")
        return executeSwipe(x, startY, x, endY, 600)
    }
    fun scrollUp(screenWidth: Int, screenHeight: Int, scrollDistance: Int = 300): Boolean {
        val x = (screenWidth * 0.75).toInt()
        val startY = (screenHeight * 0.3).toInt()
        val endY = (screenHeight * 0.7).toInt()
        return executeSwipe(x, startY, x, endY)
    }
}