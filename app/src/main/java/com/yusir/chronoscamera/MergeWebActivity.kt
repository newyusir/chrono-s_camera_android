package com.yusir.chronoscamera
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.yusir.chronoscamera.bridge.CapturedImageStore
import com.yusir.chronoscamera.databinding.ActivityMergeWebBinding
import org.json.JSONArray
import org.json.JSONObject
class MergeWebActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMergeWebBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMergeWebBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWebView()
    }
    override fun onResume() {
        super.onResume()
        notifyInventory()
    }
    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
    val settings: WebSettings = webView.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    settings.mediaPlaybackRequiresUserGesture = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(ClipboardBridge(), "ChronoBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectBridgeScript()
            }
            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    view.loadUrl(MERGE_TOOL_URL)
                }
            }
        }
        webView.setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleDownloadRequest(url, contentDisposition, mimeType)
        })
        webView.loadUrl(MERGE_TOOL_URL)
    }
    private fun injectBridgeScript() {
        binding.webView.evaluateJavascript(BRIDGE_SCRIPT, null)
        notifyInventory()
    }
    private fun handleDownloadRequest(url: String?, contentDisposition: String?, mimeType: String?) {
        val safeUrl = url ?: return
        when {
            safeUrl.startsWith("blob:") -> fetchBlobAndSave(safeUrl, contentDisposition, mimeType)
            safeUrl.startsWith("data:") -> saveDataUrl(safeUrl, contentDisposition)
            else -> enqueueDownload(Uri.parse(safeUrl), contentDisposition, mimeType)
        }
    }
    private fun enqueueDownload(uri: Uri, contentDisposition: String?, mimeType: String?) {
        val request = DownloadManager.Request(uri).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val guessedName = URLUtil.guessFileName(uri.toString(), contentDisposition, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessedName)
            } else {
                setDestinationInExternalFilesDir(this@MergeWebActivity, Environment.DIRECTORY_DOWNLOADS, guessedName)
            }
            mimeType?.let { setMimeType(it) }
            addRequestHeader("User-Agent", binding.webView.settings.userAgentString)
        }
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { manager.enqueue(request) }
            .onSuccess { Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show() }
            .onFailure {
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            }
    }
    private fun fetchBlobAndSave(blobUrl: String, contentDisposition: String?, mimeType: String?) {
        val js = """
            (async () => {
                try {
                    const response = await fetch('$blobUrl');
                    const blob = await response.blob();
                    const reader = new FileReader();
                    return await new Promise((resolve, reject) => {
                        reader.onloadend = () => resolve(reader.result || '');
                        reader.onerror = () => reject(new Error('read_error'));
                        reader.readAsDataURL(blob);
                    });
                } catch (err) {
                    return '__error__:' + String(err);
                }
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js) { result ->
            val parsed = parseJsString(result)
            if (parsed.isNullOrEmpty()) {
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            if (parsed.startsWith("__error__:")) {
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            saveDataUrl(parsed, contentDisposition)
        }
    }
    private fun saveDataUrl(dataUrl: String, contentDisposition: String?) {
        val commaIndex = dataUrl.indexOf(',')
        if (commaIndex <= 0) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val header = dataUrl.substring(5, commaIndex)
        val base64Part = dataUrl.substring(commaIndex + 1)
        val mimeType = header.substringBefore(';', "image/png")
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "png"
        val guessed = URLUtil.guessFileName("download", contentDisposition, mimeType)
        val fileName = if (!guessed.isNullOrBlank()) guessed else "chrono_merge_${'$'}{System.currentTimeMillis()}.$extension"
        val bytes = try {
            Base64.decode(base64Part, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (bytes == null) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }
        val targetCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(targetCollection, values)
        if (uri == null) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(bytes)
            } ?: throw IllegalStateException("empty stream")
        }.onSuccess {
            Toast.makeText(this, R.string.download_complete, Toast.LENGTH_SHORT).show()
        }.onFailure {
            resolver.delete(uri, null, null)
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }
    private fun notifyInventory() {
        val count = CapturedImageStore.getDataUrls().size
        val script = "window.ChronoBridgeInventory && window.ChronoBridgeInventory($count);"
        binding.webView.evaluateJavascript(script, null)
    }
    private fun parseJsString(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return try {
            JSONObject("{" + "\"value\":$raw}").getString("value")
        } catch (t: Throwable) {
            null
        }
    }
    private inner class ClipboardBridge {
        @JavascriptInterface
        fun requestImages(requestId: String) {
            val dataUrls = CapturedImageStore.getDataUrls()
            val payload = JSONArray()
            dataUrls.forEach { payload.put(it) }
            val script = buildString {
                append("window.ChronoBridgeDeliver && window.ChronoBridgeDeliver(")
                append(JSONObject.quote(requestId))
                append(", ")
                append(payload.toString())
                append(");")
            }
            binding.webView.post {
                binding.webView.evaluateJavascript(script, null)
            }
        }
        @JavascriptInterface
        fun writeText(rawText: String?): Boolean {
            val text = rawText ?: return false
            val clipboard = getSystemService<ClipboardManager>() ?: return false
            return runCatching {
                val clip = ClipData.newPlainText("MergedScreenshot", text)
                clipboard.setPrimaryClip(clip)
                runOnUiThread {
                    Toast.makeText(this@MergeWebActivity, R.string.clipboard_write_success, Toast.LENGTH_SHORT).show()
                }
                true
            }.onFailure {
                runOnUiThread {
                    Toast.makeText(this@MergeWebActivity, R.string.clipboard_write_failed, Toast.LENGTH_SHORT).show()
                }
            }.getOrElse { false }
        }
        @JavascriptInterface
        fun writeImage(dataUrl: String?): Boolean {
            if (dataUrl.isNullOrEmpty()) return false
            val clipboard = getSystemService<ClipboardManager>() ?: return false
            return runCatching {
                val commaIndex = dataUrl.indexOf(',')
                if (commaIndex <= 0) return@runCatching false
                val header = dataUrl.substring(5, commaIndex)
                val base64Part = dataUrl.substring(commaIndex + 1)
                val mimeType = header.substringBefore(';', "image/png")
                val bytes = Base64.decode(base64Part, Base64.DEFAULT)
                val cacheFile = java.io.File(cacheDir, "merged_${System.currentTimeMillis()}.png")
                cacheFile.writeBytes(bytes)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MergeWebActivity,
                    "${packageName}.fileprovider",
                    cacheFile
                )
                val clip = ClipData.newUri(contentResolver, "MergedImage", uri)
                clipboard.setPrimaryClip(clip)
                runOnUiThread {
                    Toast.makeText(this@MergeWebActivity, R.string.clipboard_write_success, Toast.LENGTH_SHORT).show()
                }
                true
            }.onFailure { e ->
                android.util.Log.e("ClipboardBridge", "Failed to write image", e)
                runOnUiThread {
                    Toast.makeText(this@MergeWebActivity, R.string.clipboard_write_failed, Toast.LENGTH_SHORT).show()
                }
            }.getOrElse { false }
        }
    }
    companion object {
        private const val MERGE_TOOL_URL = "https:
        private val BRIDGE_SCRIPT = """
            (() => {
                if (window.__chronoBridgeInjected) {
                    return;
                }
                if (window.location && window.location.protocol === 'chrome-error:') {
                    return;
                }
                window.__chronoBridgeInjected = true;
                const pending = new Map();
                let requestId = 0;
                const requestTimeout = 4000;
                const ensureBanner = () => {
                    const host = document.body || document.documentElement;
                    if (!host) {
                        return null;
                    }
                    let element = document.getElementById('nativeClipboardStatus');
                    if (!element) {
                        element = document.createElement('div');
                        element.id = 'nativeClipboardStatus';
                        element.style.position = 'fixed';
                        element.style.bottom = '16px';
                        element.style.right = '16px';
                        element.style.padding = '8px 12px';
                        element.style.background = 'rgba(30, 32, 36, 0.85)';
                        element.style.color = '
                        element.style.fontSize = '14px';
                        element.style.borderRadius = '4px';
                        element.style.zIndex = '9999';
                        element.style.pointerEvents = 'none';
                        element.style.boxShadow = '0 2px 6px rgba(0,0,0,0.25)';
                        element.style.transition = 'opacity 150ms ease-in-out';
                        element.textContent = 'Captured images not ready.';
                        host.appendChild(element);
                    }
                    return element;
                };
                const updateBanner = (count) => {
                    const element = ensureBanner();
                    if (!element) {
                        return;
                    }
                    if (count && count > 0) {
                        element.textContent = 'Captured images ready: ' + count;
                        element.style.opacity = '1';
                    } else {
                        element.textContent = 'Captured images not ready.';
                        element.style.opacity = '0.6';
                    }
                };
                window.ChronoBridgeInventory = (count) => {
                    updateBanner(count);
                };
                window.ChronoBridgeDeliver = (requestId, payload) => {
                    const resolver = pending.get(requestId);
                    if (!resolver) {
                        return;
                    }
                    pending.delete(requestId);
                    let dataUrls = [];
                    if (Array.isArray(payload)) {
                        dataUrls = payload;
                    } else if (typeof payload === 'string') {
                        try {
                            dataUrls = JSON.parse(payload || '[]');
                        } catch (err) {
                            console.warn('Failed to parse clipboard payload', err);
                        }
                    }
                    const items = dataUrls.map((dataUrl) => ({
                        types: ['image/png'],
                        async getType(type) {
                            if (type !== 'image/png') {
                                throw new Error('Unsupported type: ' + type);
                            }
                            const response = await fetch(dataUrl);
                            return await response.blob();
                        }
                    }));
                    resolver(items);
                };
                const requestNativeClipboard = () => new Promise((resolve) => {
                    const id = String(++requestId);
                    pending.set(id, resolve);
                    if (window.ChronoBridge && window.ChronoBridge.requestImages) {
                        window.ChronoBridge.requestImages(id);
                    } else {
                        resolve([]);
                    }
                    setTimeout(() => {
                        if (pending.has(id)) {
                            pending.delete(id);
                            resolve([]);
                        }
                    }, requestTimeout);
                });
                const originalRead = navigator.clipboard && navigator.clipboard.read
                    ? navigator.clipboard.read.bind(navigator.clipboard)
                    : null;
                const originalWriteText = navigator.clipboard && navigator.clipboard.writeText
                    ? navigator.clipboard.writeText.bind(navigator.clipboard)
                    : null;
                const originalWrite = navigator.clipboard && navigator.clipboard.write
                    ? navigator.clipboard.write.bind(navigator.clipboard)
                    : null;
                if (navigator.clipboard) {
                    navigator.clipboard.read = async () => {
                        const nativeItems = await requestNativeClipboard();
                        if (nativeItems && nativeItems.length) {
                            return nativeItems;
                        }
                        if (originalRead) {
                            return originalRead();
                        }
                        return [];
                    };
                    navigator.clipboard.writeText = async (text) => {
                        if (window.ChronoBridge && window.ChronoBridge.writeText) {
                            const success = !!window.ChronoBridge.writeText(String(text ?? ''));
                            if (success) {
                                return;
                            }
                        }
                        if (originalWriteText) {
                            return originalWriteText(text);
                        }
                        throw new Error('Clipboard write not supported');
                    };
                    navigator.clipboard.write = async (clipboardItems) => {
                        for (const item of clipboardItems) {
                            const types = item.types || [];
                            for (const type of types) {
                                if (type.startsWith('image/')) {
                                    try {
                                        const blob = await item.getType(type);
                                        const reader = new FileReader();
                                        const dataUrl = await new Promise((resolve, reject) => {
                                            reader.onloadend = () => resolve(reader.result);
                                            reader.onerror = reject;
                                            reader.readAsDataURL(blob);
                                        });
                                        if (window.ChronoBridge && window.ChronoBridge.writeImage) {
                                            const success = !!window.ChronoBridge.writeImage(dataUrl);
                                            if (success) {
                                                return;
                                            }
                                        }
                                    } catch (e) {
                                        console.warn('Failed to process clipboard image', e);
                                    }
                                }
                            }
                        }
                        if (originalWrite) {
                            return originalWrite(clipboardItems);
                        }
                        throw new Error('Clipboard write not supported');
                    };
                }
                updateBanner(0);
            })();
        """.trimIndent()
    }
}