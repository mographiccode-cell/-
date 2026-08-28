package com.mographiccode.social_downloader

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
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
        val workDir = File(cacheDir, "ytdlp/$requestId")
        if (workDir.exists()) workDir.deleteRecursively()
        workDir.mkdirs()

        val outputTemplate = File(workDir, "%(title).80s.%(ext)s").absolutePath

        try {
            YtDlp.init(applicationContext)
            val request = YtDlpRequest(url)
                .setOutputTemplate(outputTemplate)
                .addOption(
                    "-f",
                    "best[acodec!=none][vcodec!=none][ext=mp4]/best[acodec!=none][vcodec!=none]/best"
                )
                .addOption("--no-playlist")
                .addOption("--restrict-filenames")
                .addOption("--no-overwrites")

            val future = YtDlp.executeAsync(request) { progress, eta, line ->
                runOnUiThread {
                    methodChannel?.invokeMethod(
                        "downloadProgress",
                        mapOf(
                            "progress" to progress,
                            "eta" to eta,
                            "line" to (line ?: "")
                        )
                    )
                }
            }

            waiter.execute {
                try {
                    val response = future.get()
                    if (!response.isSuccess) {
                        runOnUiThread {
                            result.success(
                                mapOf(
                                    "success" to false,
                                    "error" to "yt-dlp exited with code ${response.exitCode}"
                                )
                            )
                        }
                        return@execute
                    }

                    val downloaded = workDir.listFiles()
                        ?.filter { file ->
                            file.isFile &&
                                !file.name.endsWith(".part") &&
                                !file.name.endsWith(".ytdl")
                        }
                        ?.maxByOrNull { it.lastModified() }

                    if (downloaded == null || downloaded.length() == 0L) {
                        runOnUiThread {
                            result.success(
                                mapOf(
                                    "success" to false,
                                    "error" to "Download completed but no media file was found."
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
                                "error" to friendlyError(e)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            result.success(mapOf("success" to false, "error" to friendlyError(e)))
        }
    }

    private fun publishToDownloads(source: File): Uri {
        val extension = source.extension.lowercase()
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

    private fun friendlyError(error: Throwable): String {
        val message = error.cause?.message ?: error.message ?: error.javaClass.simpleName
        return when {
            message.contains("DRM", ignoreCase = true) -> "هذا الفيديو محمي بـ DRM ولا يمكن تنزيله بواسطة التطبيق."
            message.contains("login", ignoreCase = true) -> "هذا الرابط يحتاج تسجيل دخول أو صلاحية خاصة."
            message.contains("private", ignoreCase = true) -> "المحتوى خاص أو غير متاح للعامة."
            else -> message.take(500)
        }
    }
}
