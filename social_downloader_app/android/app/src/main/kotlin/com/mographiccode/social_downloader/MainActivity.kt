package com.mographiccode.social_downloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import dev.ffmpegkit_maintained.ytdlp.YtDlpResponse
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
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
            // Reported to Flutter when a download is requested.
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "getInitialShare" -> {
                    if (initialShareConsumed) {
                        result.success(null)
                    } else {
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
                else -> result.notImplemented()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val shared = extractSharedText(intent)
        if (!shared.isNullOrBlank()) {
            methodChannel?.invokeMethod("onSharedUrl", shared)
        }
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) return text
        }
        return intent.dataString
    }

    private fun startDownload(
        url: String,
        requestId: String,
        result: MethodChannel.Result,
    ) {
        if (!hasUsableNetwork()) {
            result.success(
                mapOf(
                    "success" to false,
                    "error" to "لا يوجد اتصال إنترنت صالح للتطبيق حاليًا. تأكد من Wi-Fi أو بيانات الهاتف ثم أعد المحاولة."
                )
            )
            return
        }

        val workDir = File(cacheDir, "ytdlp/$requestId")
        if (workDir.exists()) workDir.deleteRecursively()
        workDir.mkdirs()

        waiter.execute {
            try {
                // Direct media links do not need yt-dlp at all. Using Android's native
                // networking here also avoids Python/IPv6 routing problems.
                if (looksLikeDirectMediaUrl(url)) {
                    try {
                        emitProgress(0f, 0L, "اتصال مباشر بالرابط...")
                        val direct = downloadDirectMedia(url, workDir)
                        val publishedUri = publishToDownloads(direct)
                        runOnUiThread {
                            result.success(
                                mapOf(
                                    "success" to true,
                                    "fileName" to direct.name,
                                    "uri" to publishedUri.toString()
                                )
                            )
                        }
                        workDir.deleteRecursively()
                        return@execute
                    } catch (_: Exception) {
                        // Fall through to yt-dlp. Some "direct-looking" URLs require
                        // extractor headers or redirects that yt-dlp handles better.
                    }
                }

                emitProgress(0f, 0L, "تهيئة محرك التحميل...")
                YtDlp.init(applicationContext)

                // First attempt: force IPv4. This is important on Android networks where
                // Python sees an IPv6 route but the route is unusable and raises Errno 101.
                var response = executeYtDlp(url, workDir, forceIpv4 = true)

                // If the embedded Python networking still reports a route problem,
                // retry once without the forced address family. This covers IPv6-only
                // networks/NAT64 setups where Android itself has connectivity.
                if (!response.isSuccess && isNetworkRouteError(response)) {
                    emitProgress(0f, 0L, "إعادة المحاولة بمسار شبكة بديل...")
                    workDir.listFiles()?.forEach { if (it.isFile) it.delete() }
                    response = executeYtDlp(url, workDir, forceIpv4 = false)
                }

                if (!response.isSuccess) {
                    val detail = buildResponseError(response)
                    runOnUiThread {
                        result.success(
                            mapOf(
                                "success" to false,
                                "error" to friendlyMessage(detail)
                            )
                        )
                    }
                    return@execute
                }

                val downloaded = workDir.listFiles()
                    ?.filter { file ->
                        file.isFile &&
                            !file.name.endsWith(".part") &&
                            !file.name.endsWith(".ytdl") &&
                            !file.name.endsWith(".json")
                    }
                    ?.maxByOrNull { it.lastModified() }

                if (downloaded == null || downloaded.length() == 0L) {
                    runOnUiThread {
                        result.success(
                            mapOf(
                                "success" to false,
                                "error" to "انتهى محرك التحميل دون إنشاء ملف فيديو صالح."
                            )
                        )
                    }
                    return@execute
                }

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
            } catch (e: Exception) {
                runOnUiThread {
                    result.success(
                        mapOf(
                            "success" to false,
                            "error" to friendlyMessage(e.cause?.message ?: e.message ?: e.javaClass.simpleName)
                        )
                    )
                }
            }
        }
    }

    private fun executeYtDlp(url: String, workDir: File, forceIpv4: Boolean): YtDlpResponse {
        val outputTemplate = File(workDir, "%(title).80s.%(ext)s").absolutePath
        val request = YtDlpRequest(url)
            .setOutputTemplate(outputTemplate)
            .addOption(
                "-f",
                "best[acodec!=none][vcodec!=none][ext=mp4]/best[acodec!=none][vcodec!=none]/best"
            )
            .addOption("--no-playlist")
            .addOption("--restrict-filenames")
            .addOption("--no-overwrites")
            .addOption("--socket-timeout", "30")
            .addOption("--retries", "5")
            .addOption("--fragment-retries", "5")
            .addOption("--extractor-retries", "3")
            .addOption("--retry-sleep", "1")
            .addOption("--concurrent-fragments", "1")

        if (forceIpv4) {
            request.addOption("--force-ipv4")
        }

        val future = YtDlp.executeAsync(request) { progress, eta, line ->
            emitProgress(progress, eta, line ?: "")
        }
        return future.get()
    }

    private fun emitProgress(progress: Float, eta: Long, line: String) {
        runOnUiThread {
            methodChannel?.invokeMethod(
                "downloadProgress",
                mapOf(
                    "progress" to progress,
                    "eta" to eta,
                    "line" to line
                )
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
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "*/*")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }

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

            if (!output.exists() || output.length() == 0L) {
                throw IllegalStateException("Downloaded file is empty")
            }
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

    private fun isNetworkRouteError(response: YtDlpResponse): Boolean {
        val all = (response.errorOutput + "\n" + response.output).lowercase(Locale.US)
        return all.contains("errno 101") ||
            all.contains("network is unreachable") ||
            all.contains("no route to host")
    }

    private fun buildResponseError(response: YtDlpResponse): String {
        val error = response.errorOutput.trim()
        val output = response.output.trim()
        return when {
            error.isNotEmpty() -> error.takeLast(1200)
            output.isNotEmpty() -> output.takeLast(1200)
            else -> "yt-dlp exited with code ${response.exitCode}"
        }
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
            val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            contentResolver.update(uri, ready, null, null)
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun friendlyMessage(messageRaw: String): String {
        val message = messageRaw.trim()
        return when {
            message.contains("DRM", ignoreCase = true) ->
                "هذا الفيديو يستخدم DRM ولا يمكن للتطبيق تنزيل المحتوى المحمي بهذه التقنية."
            message.contains("login", ignoreCase = true) ||
                message.contains("sign in", ignoreCase = true) ->
                "هذا الرابط يحتاج جلسة تسجيل دخول أو صلاحية من الموقع."
            message.contains("private", ignoreCase = true) ->
                "المحتوى خاص أو غير متاح للعامة."
            message.contains("Errno 101", ignoreCase = true) ||
                message.contains("Network is unreachable", ignoreCase = true) ||
                message.contains("No route to host", ignoreCase = true) ->
                "الهاتف متصل بالإنترنت، لكن محرك الموقع لم يتمكن من إنشاء مسار اتصال بالخادم حتى بعد إعادة المحاولة. جرّب شبكة أخرى أو بيانات الهاتف؛ التطبيق حاول IPv4 والمسار التلقائي."
            message.contains("timed out", ignoreCase = true) ->
                "انتهت مهلة الاتصال بالخادم بعد عدة محاولات. أعد المحاولة أو جرّب شبكة أخرى."
            else -> if (message.isBlank()) "تعذر تنزيل الرابط." else message.take(700)
        }
    }
}
