package com.yusir.chronoscamera.clipboard
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
class InMemoryImageProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null
    override fun getType(uri: Uri): String = MIME_TYPE
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.lastPathSegment ?: throw FileNotFoundException("Missing image id")
        val file = imageStore[id] ?: throw FileNotFoundException("Image not found")
        Log.d(TAG, "openFile id=$id path=${file.absolutePath}")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    companion object {
        private const val AUTHORITY_SUFFIX = ".clipboard"
        private const val MIME_TYPE = "image/png"
        private const val TAG = "InMemoryImageProvider"
        private val imageStore: MutableMap<String, File> = ConcurrentHashMap()
        fun authority(context: Context): String = "${context.packageName}$AUTHORITY_SUFFIX"
        fun replaceAll(context: Context, images: List<ByteArray>): List<Uri> {
            val authority = authority(context)
            val cacheDir = File(context.cacheDir, "clipboard_images")
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                Log.e(TAG, "Failed to create clipboard cache directory")
                return emptyList()
            }
            val uris = ArrayList<Uri>(images.size)
            synchronized(imageStore) {
                clearLocked()
                for (bytes in images) {
                    val id = UUID.randomUUID().toString()
                    val file = File(cacheDir, "$id.png")
                    try {
                        FileOutputStream(file).use { it.write(bytes) }
                    } catch (ioe: IOException) {
                        Log.e(TAG, "Failed to write clipboard image", ioe)
                        file.delete()
                        clearLocked()
                        return emptyList()
                    }
                    imageStore[id] = file
                    uris += Uri.Builder()
                        .scheme("content")
                        .authority(authority)
                        .appendPath(id)
                        .build()
                }
                Log.d(TAG, "Prepared ${uris.size} clipboard images")
            }
            return uris
        }
        private fun clearLocked() {
            imageStore.values.forEach { file ->
                if (!file.delete()) {
                    Log.w(TAG, "Failed to delete clipboard cache file: ${file.absolutePath}")
                }
            }
            imageStore.clear()
        }
        fun mimeType(): String = MIME_TYPE
    }
}