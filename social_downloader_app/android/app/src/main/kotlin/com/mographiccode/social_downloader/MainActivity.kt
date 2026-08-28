package com.mographiccode.social_downloader

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.chaquo.python.Python
import dev.ffmpegkit_maintained.ytdlp.DownloadProgressCallback
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val channelName = "social_downloader/native"
    private var methodChannel: MethodChannel? = null
    private var initialShareConsumed = false
    private val waiter = Executors.newCachedThreadPool()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            YtDlp.init(applicationContext)
        } catch (_: Exception) {
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "getInitialShare" -> {
                    if (initialShareConsumed) result.success(null)
                    else {
                        initialShareConsumed = true
                        result.success(extractSharedText(intent))
                    }
                }
                "download" -> {
                    val url = call.argument<String>("url")?.trim().orEmpty()
                    val requestId = call.argument<String>("requestId")?.trim().orEmpty()
                    if (url.isBlank() || requestId.isBlank()) {
                        result.error("invalid_args", "Missing URL or request id", null)
                    } else {
                        startDownload(url, requestId, result)
                    }
                }
                "loginSite" -> {
                    val site = call.argument<String>("site")?.lowercase(Locale.US).orEmpty()
                    if (site != "instagram" && site != "youtube") {
                        result.error("invalid_site", "Unsupported site", null)
                    } else {
                        openSiteLogin(site, result)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedText(intent)?.takeIf { it.isNotBlank() }?.let {
            methodChannel?.invokeMethod("onSharedUrl", it)
        }
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return intent.dataString
    }

    private fun startDownload(url: String, requestId: String, result: MethodChannel.Result) {
        if (!hasUsableNetwork()) {
            result.success(mapOf("success" to false, "error" to "لا يوجد اتصال إنترنت صالح للتطبيق حاليًا."))
            return
        }

        val workDir = File(cacheDir, "ytdlp/$requestId")
        if (workDir.exists()) workDir.deleteRecursively()
        workDir.mkdirs()

        waiter.execute {
            try {
                if (looksLikeDirectMediaUrl(url)) {
                    try {
                        emitProgress(0f, 0L, "اتصال مباشر بالرابط...")
                        val direct = downloadDirectMedia(url, workDir)
                        finishSuccess(direct, workDir, result)
                        return@execute
                    } catch (_: Exception) {
                    }
                }

                emitProgress(0f, 0L, "تهيئة محرك التحميل...")
                YtDlp.init(applicationContext)

                val run = executeWithStrategies(url, workDir)
                if (!run.success) {
                    val authSite = authRequiredSite(url, run.error)
                    runOnUiThread {
                        result.success(
                            mapOf(
                                "success" to false,
                                "error" to friendlyMessage(run.error),
                                "needsAuth" to (authSite != null),
                                "authSite" to authSite
                            )
                        )
                    }
                    return@execute
                }

                val downloaded = findDownloadedFile(workDir)
                if (downloaded == null || downloaded.length() == 0L) {
                    runOnUiThread {
                        result.success(mapOf("success" to false, "error" to "انتهى التحميل دون إنشاء ملف فيديو صالح."))
                    }
                    return@execute
                }
                finishSuccess(downloaded, workDir, result)
            } catch (e: Exception) {
                val message = e.cause?.message ?: e.message ?: e.javaClass.simpleName
                runOnUiThread {
                    result.success(mapOf("success" to false, "error" to friendlyMessage(message)))
                }
            }
        }
    }

    private fun executeWithStrategies(url: String, workDir: File): PythonRunResult {
        val host = Uri.parse(url).host.orEmpty().lowercase(Locale.US)
        val strategies = mutableListOf<JSONObject>()

        if (host.contains("youtube.com") || host.contains("youtu.be")) {
            strategies += youtubeOptions("android_vr")
            strategies += youtubeOptions("web_safari")
            strategies += youtubeOptions("web_embedded")
        } else {
            strategies += baseOptions().apply {
                put("format", "best[ext=mp4]/best")
                put("http_headers", JSONObject().apply {
                    put("User-Agent", browserUserAgent)
                    if (host.contains("instagram.com")) put("Referer", "https://www.instagram.com/")
                })
                cookieFileForHost(host)?.let { put("cookiefile", it.absolutePath) }
            }
        }

        var last = PythonRunResult(false, "تعذر تنزيل الرابط.")
        for ((index, options) in strategies.withIndex()) {
            if (index > 0) {
                emitProgress(0f, 0L, "تجربة طريقة تحميل بديلة...")
                workDir.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
            }
            last = executePythonYtDlp(url, workDir, options)
            if (last.success) return last
        }
        return last
    }

    private fun youtubeOptions(client: String): JSONObject {
        return baseOptions().apply {
            put("format", if (client == "web_safari") {
                "best[protocol*=m3u8][acodec!=none][vcodec!=none]/best[ext=mp4]/best"
            } else {
                "best[ext=mp4]/best"
            })
            put("http_headers", JSONObject().apply {
                put("User-Agent", browserUserAgent)
                put("Referer", "https://www.youtube.com/")
            })
            put("extractor_args", JSONObject().apply {
                put("youtube", JSONObject().apply {
                    put("player_client", JSONArray().put(client))
                })
            })
            cookieFileForHost("youtube.com")?.let { put("cookiefile", it.absolutePath) }
        }
    }

    private fun baseOptions(): JSONObject {
        return JSONObject().apply {
            put("noplaylist", true)
            put("restrictfilenames", true)
            put("overwrites", false)
            put("continuedl", true)
            put("socket_timeout", 30)
            put("retries", 5)
            put("fragment_retries", 5)
            put("extractor_retries", 3)
            put("concurrent_fragment_downloads", 1)
            put("force_ipv4", true)
            put("cachedir", false)
            put("quiet", true)
        }
    }

    private fun executePythonYtDlp(url: String, workDir: File, options: JSONObject): PythonRunResult {
        val outputTemplate = File(workDir, "%(title).80s.%(ext)s").absolutePath
        val python = Python.getInstance()
        val pyDir = File(filesDir, "python_ext").apply { mkdirs() }
        val runnerFile = File(pyDir, "social_runner_v4.py")
        if (!runnerFile.exists() || runnerFile.readText() != pythonRunnerSource) {
            runnerFile.writeText(pythonRunnerSource)
        }
        python.getModule("sys").get("path")?.callAttr("insert", 0, pyDir.absolutePath)
        val runner = python.getModule("social_runner_v4")
        val callback = DownloadProgressCallback { progress, eta, line ->
            emitProgress(progress, eta, line ?: "")
        }
        val json = runner.callAttr("execute", url, outputTemplate, options.toString(), callback).toString()
        val obj = JSONObject(json)
        return PythonRunResult(
            success = obj.optBoolean("success", false),
            error = obj.optString("error").ifBlank { obj.optString("log").ifBlank { "تعذر تنزيل الرابط." } }
        )
    }

    private fun findDownloadedFile(workDir: File): File? {
        return workDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    !file.name.endsWith(".part") &&
                    !file.name.endsWith(".ytdl") &&
                    !file.name.endsWith(".json")
            }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun finishSuccess(downloaded: File, workDir: File, result: MethodChannel.Result) {
        val publishedUri = publishToDownloads(downloaded)
        runOnUiThread {
            result.success(
                mapOf(
                    "success" to true,
                    "fileName" to downloaded.name,
                    "uri" to publishedUri.toString()
                )
            )
        }
        workDir.deleteRecursively()
    }

    private fun emitProgress(progress: Float, eta: Long, line: String) {
        runOnUiThread {
            methodChannel?.invokeMethod(
                "downloadProgress",
                mapOf("progress" to progress, "eta" to eta, "line" to line)
            )
        }
    }

    private fun hasUsableNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun looksLikeDirectMediaUrl(rawUrl: String): Boolean {
        return try {
            val path = Uri.parse(rawUrl).path?.lowercase(Locale.US).orEmpty()
            listOf(".mp4", ".webm", ".mov", ".m4v", ".mkv", ".avi", ".3gp").any { path.endsWith(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadDirectMedia(rawUrl: String, workDir: File): File {
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", browserUserAgent)
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val extension = extensionFor(rawUrl, connection.contentType)
            val output = File(workDir, "video_${System.currentTimeMillis()}.$extension")
            val total = connection.contentLengthLong
            var copied = 0L
            var lastPercent = -1
            BufferedInputStream(connection.inputStream).use { input ->
                output.outputStream().buffered().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            val percent = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                emitProgress(percent.toFloat(), 0L, "تنزيل مباشر...")
                            }
                        }
                    }
                }
            }
            if (!output.exists() || output.length() == 0L) throw IllegalStateException("Downloaded file is empty")
            return output
        } finally {
            connection.disconnect()
        }
    }

    private fun extensionFor(rawUrl: String, contentType: String?): String {
        val pathExt = MimeTypeMap.getFileExtensionFromUrl(rawUrl)?.lowercase(Locale.US)
        if (!pathExt.isNullOrBlank() && pathExt.length <= 5) return pathExt
        val fromMime = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return fromMime ?: "mp4"
    }

    private fun publishToDownloads(source: File): Uri {
        val extension = source.extension.lowercase(Locale.US)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: if (extension == "mp4") "video/mp4" else "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SocialDownloader")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create output file in Downloads")
        try {
            contentResolver.openOutputStream(uri, "w")!!.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun authRequiredSite(url: String, error: String): String? {
        val lower = error.lowercase(Locale.US)
        if (!(lower.contains("login") || lower.contains("sign in") || lower.contains("not a bot") || lower.contains("cookies"))) return null
        val host = Uri.parse(url).host.orEmpty().lowercase(Locale.US)
        return when {
            host.contains("instagram.com") -> "instagram"
            host.contains("youtube.com") || host.contains("youtu.be") -> "youtube"
            else -> null
        }
    }

    private fun cookieFileForHost(host: String): File? {
        val site = when {
            host.contains("instagram") -> "instagram"
            host.contains("youtube") || host.contains("youtu.be") -> "youtube"
            else -> return null
        }
        val file = File(filesDir, "cookies/$site.txt")
        return file.takeIf { it.exists() && it.length() > 20 }
    }

    private fun openSiteLogin(site: String, result: MethodChannel.Result) {
        runOnUiThread {
            val dialog = Dialog(this)
            val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val webView = WebView(this)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.webViewClient = WebViewClient()
            val cookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            val saveButton = Button(this).apply {
                text = "حفظ الجلسة والعودة"
                setOnClickListener {
                    val target = if (site == "instagram") "https://www.instagram.com/" else "https://www.youtube.com/"
                    val cookieHeader = cookieManager.getCookie(target).orEmpty()
                    if (cookieHeader.isBlank()) {
                        Toast.makeText(this@MainActivity, "لم يتم العثور على جلسة بعد. سجّل الدخول أولًا.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    saveCookies(site, cookieHeader)
                    result.success(true)
                    dialog.dismiss()
                }
            }

            root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(saveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            dialog.setContentView(root)
            dialog.setOnCancelListener { result.success(false) }
            dialog.setOnShowListener {
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            dialog.show()

            val loginUrl = if (site == "instagram") {
                "https://www.instagram.com/accounts/login/"
            } else {
                "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com/"
            }
            webView.loadUrl(loginUrl)
        }
    }

    private fun saveCookies(site: String, header: String) {
        val domain = if (site == "instagram") ".instagram.com" else ".youtube.com"
        val dir = File(filesDir, "cookies").apply { mkdirs() }
        val file = File(dir, "$site.txt")
        val expires = (System.currentTimeMillis() / 1000L) + 60L * 60L * 24L * 365L
        val lines = header.split(';')
            .mapNotNull { raw ->
                val part = raw.trim()
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                "$domain\tTRUE\t/\tTRUE\t$expires\t$name\t$value"
            }
        file.writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n")
    }

    private fun friendlyMessage(messageRaw: String): String {
        val message = messageRaw.trim()
        return when {
            message.contains("DRM", ignoreCase = true) ->
                "هذا الفيديو يستخدم DRM ولا يمكن تنزيل المحتوى المحمي بهذه التقنية."
            message.contains("login", ignoreCase = true) ||
                message.contains("sign in", ignoreCase = true) ||
                message.contains("not a bot", ignoreCase = true) ->
                "الموقع يطلب جلسة دخول. يمكنك تسجيل الدخول من داخل التطبيق ثم إعادة المحاولة."
            message.contains("private", ignoreCase = true) ->
                "المحتوى خاص أو غير متاح للعامة."
            message.contains("Errno 101", ignoreCase = true) ||
                message.contains("Network is unreachable", ignoreCase = true) ||
                message.contains("No route to host", ignoreCase = true) ->
                "تعذر الوصول إلى خادم الفيديو من مسار الشبكة الحالي."
            message.contains("timed out", ignoreCase = true) ->
                "انتهت مهلة الاتصال بالخادم بعد عدة محاولات."
            message.contains("javascript runtime", ignoreCase = true) ->
                "YouTube طلب تحدي JavaScript لم يتمكن المحرك المدمج من حله لهذا الفيديو. سيجرب التطبيق عملاء YouTube بديلة تلقائيًا."
            else -> if (message.isBlank()) "تعذر تنزيل الرابط." else message.take(700)
        }
    }

    data class PythonRunResult(val success: Boolean, val error: String)

    companion object {
        private const val browserUserAgent =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        private val pythonRunnerSource = """
import json
import traceback
import yt_dlp

def execute(url, output_template, options_json, progress_callback=None):
    opts = json.loads(options_json or '{}')
    opts['outtmpl'] = output_template
    logs = []

    class Logger:
        def debug(self, msg):
            logs.append(str(msg))
        def info(self, msg):
            logs.append(str(msg))
        def warning(self, msg):
            logs.append(str(msg))
        def error(self, msg):
            logs.append(str(msg))

    opts['logger'] = Logger()

    if progress_callback is not None:
        def hook(d):
            try:
                status = d.get('status')
                if status == 'downloading':
                    pct = float(str(d.get('_percent_str', '0')).strip().replace('%', '') or 0)
                    eta = int(d.get('eta') or 0)
                    progress_callback.onProgressUpdate(pct, eta, str(d.get('_default_template', '')))
                elif status == 'finished':
                    progress_callback.onProgressUpdate(100.0, 0, 'اكتمل تنزيل الملف')
            except Exception:
                pass
        opts['progress_hooks'] = [hook]

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            code = ydl.download([url])
        return json.dumps({
            'success': code == 0,
            'code': int(code or 0),
            'log': '\\n'.join(logs[-40:])
        }, ensure_ascii=False)
    except Exception as exc:
        logs.append(traceback.format_exc())
        return json.dumps({
            'success': False,
            'code': 1,
            'error': str(exc),
            'log': '\\n'.join(logs[-50:])
        }, ensure_ascii=False)
""".trimIndent()
    }
}
