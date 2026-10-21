package com.movtery.zalithlauncher.tr

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.movtery.zalithlauncher.game.version.installed.Version
import java.io.ByteArrayInputStream

private const val HOST = "tr.local"

private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "html" -> "text/html"
    "js" -> "application/javascript"
    "css" -> "text/css"
    "json" -> "application/json"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    else -> "application/octet-stream"
}

/**
 * Pantalla principal de T.R Launcher: la misma interfaz del launcher de PC (carpeta assets/tr-ui) dentro de un WebView.
 * Se sirve desde una dirección interna (https://tr.local/) para que todo funcione sin Internet y sin permisos de archivos.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun TrWebHome(
    onLaunch: (version: Version, serverAddress: String?) -> Unit,
    onEngineSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    AndroidView(
        modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.parseColor("#050824"))
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = false
                settings.setSupportZoom(false)

                val bridge = TrBridge(ctx, this, scope, onLaunch, onEngineSettings)
                addJavascriptInterface(bridge, "TRNative")

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url
                        if (url.host != HOST) return null
                        var path = (url.path ?: "/").trimStart('/')
                        if (path.isEmpty()) path = "index.html"
                        if (path.contains("..")) return null
                        return try {
                            WebResourceResponse(mimeOf(path), "UTF-8", ctx.assets.open("tr-ui/$path"))
                        } catch (_: Exception) {
                            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }
                    }

                    // Nada de navegar fuera de la app: los enlaces externos los abre el navegador del teléfono.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.url.host == HOST) return false
                        bridge.invoke(0, "open", org.json.JSONObject().put("url", request.url.toString()).toString())
                        return true
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    // Para «Subir skin PNG…»: abre el selector de archivos del teléfono
                    override fun onShowFileChooser(
                        webView: WebView,
                        filePathCallback: ValueCallback<Array<Uri>>,
                        fileChooserParams: FileChooserParams
                    ): Boolean {
                        fileCallback?.onReceiveValue(null)
                        fileCallback = filePathCallback
                        return try {
                            chooser.launch(fileChooserParams.createIntent())
                            true
                        } catch (_: Exception) {
                            fileCallback = null
                            filePathCallback.onReceiveValue(null)
                            false
                        }
                    }
                }

                loadUrl("https://$HOST/index.html")
            }
        },
        onRelease = { it.destroy() }
    )
}
