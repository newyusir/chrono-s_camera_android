package com.yusir.chronoscamera.bridge
import android.util.Base64
import java.util.Collections
object CapturedImageStore {
    private val lock = Any()
    private var dataUrls: List<String> = emptyList()
    fun updateFromPngBytes(images: List<ByteArray>) {
        val encoded = images.map { bytes ->
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:image/png;base64,$base64"
        }
        synchronized(lock) {
            dataUrls = Collections.unmodifiableList(encoded)
        }
    }
    fun getDataUrls(): List<String> = synchronized(lock) { dataUrls }
    fun clear() {
        synchronized(lock) {
            dataUrls = emptyList()
        }
    }
}