package com.yusir.chronoscamera.shizuku
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
class ShellService : IShellService.Stub() {
    companion object {
        private const val TAG = "ShellService"
    }
    override fun executeCommand(command: String): Int {
        return try {
            Log.d(TAG, "Executing command: $command")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                val error = errorReader.readText()
                Log.w(TAG, "Command failed with exit code $exitCode: $error")
            }
            exitCode
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command", e)
            -1
        }
    }
}