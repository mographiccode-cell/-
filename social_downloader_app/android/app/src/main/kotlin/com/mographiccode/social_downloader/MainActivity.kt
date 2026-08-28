package com.mographiccode.social_downloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Html
import android.webkit.MimeTypeMap
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
import java.nio.ByteBuffer
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
                val host = Uri.parse(url).host.orEmpty().lowercase(Locale.US)

                if (host.contains("instagram.com")) {
                    try {
                        emitProgress(0f, 0L, "استخراج رابط Instagram العام...")
                        val media = extractInstagramPublicMedia(url)
                        if (media != null) {
                            val direct = downloadDirectMedia(
                                rawUrl = media.url,
                                workDir = workDir,
                                referer = media.referer,
                                cookieHeader = media.cookies,
                            )
                            finishSuccess(direct, workDir, result)
                            return@execute
                        }
                    } catch (_: Exception) {
                        // Fall through to yt-dlp's public Instagram extractor.
                    }
                }

                if (looksLikeDirectMediaUrl(url)) {
                    try {
                        emitProgress(0f, 0L, "اتصال مباشر بالرابط...")
                        val direct = downloadDirectMedia(url, workDir, referer = url)
                        finishSuccess(direct, workDir, result)
                        return@execute
                    } catch (_: Exception) {
                    }
                }

                emitProgress(0f, 0L, "فحص الصيغ المتاحة...")
                YtDlp.init(applicationContext)

                val run = executeWithStrategies(url, workDir)
                if (!run.success) {
                    runOnUiThread {
                        result.success(
                            mapOf(
                                "success" to false,
                                "error" to friendlyMessage(run.error),
                                "needsAuth" to false,
                            )
                        )
                    }
                    return@execute
                }

                val downloaded = when {
                    !run.finalFile.isNullOrBlank() -> File(run.finalFile)
                    !run.videoFile.isNullOrBlank() && !run.audioFile.isNullOrBlank() -> {
                        emitProgress(94f, 0L, "دمج الصوت والصورة داخل التطبيق...")
                        val merged = File(workDir, "youtube_${System.currentTimeMillis()}.mp4")
                        muxVideoAndAudio(File(run.videoFile), File(run.audioFile), merged)
                    }
                    else -> findDownloadedFile(workDir)
                }

                if (downloaded == null || !downloaded.exists() || downloaded.length() == 0L) {
                    runOnUiThread {
                        result.success(mapOf("success" to false, "error" to "انتهى التحميل دون إنشاء ملف فيديو صالح."))
                    }
                    return@execute
                }

                finishSuccess(downloaded, workDir, result)
            } catch (e: Exception) {
                val message = e.cause?.message ?: e.message ?: e.javaClass.simpleName
                runOnUiThread {
                    result.success(mapOf("success" to false, "error" to friendlyMessage(message), "needsAuth" to false))
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
            strategies += youtubeOptions("mweb")
            strategies += youtubeOptions("web_embedded")
            strategies += youtubeOptions("tv_embedded")
        } else {
            strategies += baseOptions().apply {
                put("http_headers", JSONObject().apply {
                    put("User-Agent", browserUserAgent)
                    put("Accept-Language", "en-US,en;q=0.9")
                    if (host.contains("instagram.com")) put("Referer", "https://www.instagram.com/")
                })
            }
        }

        var last = PythonRunResult(false, "تعذر تنزيل الرابط.")
        for ((index, options) in strategies.withIndex()) {
            if (index > 0) {
                emitProgress(0f, 0L, "تجربة مصدر صيغ بديل...")
                workDir.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
            }
            last = executePythonYtDlp(url, workDir, options)
            if (last.success) return last
        }
        return last
    }

    private fun youtubeOptions(client: String): JSONObject {
        return baseOptions().apply {
            put("http_headers", JSONObject().apply {
                put("User-Agent", browserUserAgent)
                put("Accept-Language", "en-US,en;q=0.9")
                put("Referer", "https://www.youtube.com/")
            })
            put("extractor_args", JSONObject().apply {
                put("youtube", JSONObject().apply {
                    put("player_client", JSONArray().put(client))
                })
            })
        }
    }

    private fun baseOptions(): JSONObject {
        return JSONObject().apply {
            put("noplaylist", true)
            put("restrictfilenames", true)
            put("overwrites", true)
            put("continuedl", true)
            put("socket_timeout", 30)
            put("retries", 5)
            put("fragment_retries", 5)
            put("extractor_retries", 3)
            put("concurrent_fragment_downloads", 1)
            put("force_ipv4", true)
            put("cachedir", false)
            put("quiet", true)
            put("no_warnings", false)
        }
    }

    private fun executePythonYtDlp(url: String, workDir: File, options: JSONObject): PythonRunResult {
        val outputTemplate = File(workDir, "%(title).80s.%(ext)s").absolutePath
        val python = Python.getInstance()
        val pyDir = File(filesDir, "python_ext").apply { mkdirs() }
        val runnerFile = File(pyDir, "social_runner_v6.py")
        if (!runnerFile.exists() || runnerFile.readText() != pythonRunnerSource) {
            runnerFile.writeText(pythonRunnerSource)
        }
        python.getModule("sys").get("path")?.callAttr("insert", 0, pyDir.absolutePath)
        val runner = python.getModule("social_runner_v6")
        val callback = DownloadProgressCallback { progress, eta, line ->
            emitProgress(progress, eta, line ?: "")
        }
        val json = runner.callAttr("execute", url, outputTemplate, options.toString(), callback).toString()
        val obj = JSONObject(json)
        return PythonRunResult(
            success = obj.optBoolean("success", false),
            error = obj.optString("error").ifBlank { obj.optString("log").ifBlank { "تعذر تنزيل الرابط." } },
            finalFile = obj.optString("final_file").takeIf { it.isNotBlank() },
            videoFile = obj.optString("video_file").takeIf { it.isNotBlank() },
            audioFile = obj.optString("audio_file").takeIf { it.isNotBlank() },
        )
    }

    private fun muxVideoAndAudio(videoFile: File, audioFile: File, outputFile: File): File {
        if (outputFile.exists()) outputFile.delete()

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrack = findTrack(videoExtractor, "video/")
            val audioTrack = findTrack(audioExtractor, "audio/")
            if (videoTrack < 0 || audioTrack < 0) {
                throw IllegalStateException("لم يتم العثور على مسار صوت أو فيديو صالح للدمج.")
            }

            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                val rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION)
                if (rotation == 90 || rotation == 180 || rotation == 270) muxer.setOrientationHint(rotation)
            }
            val outVideoTrack = muxer.addTrack(videoFormat)
            val outAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            copyTrack(videoExtractor, muxer, outVideoTrack)
            copyTrack(audioExtractor, muxer, outAudioTrack)
            muxer.stop()
            muxer.release()
            muxer = null

            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw IllegalStateException("فشل إنشاء ملف الفيديو النهائي بعد الدمج.")
            }
            return outputFile
        } finally {
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return index
        }
        return -1
    }

    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, outputTrack: Int) {
        val buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
    }

    private fun extractInstagramPublicMedia(sourceUrl: String): InstagramMedia? {
        val normalized = sourceUrl.substringBefore('?').trimEnd('/')
        val pages = linkedSetOf(
            "$normalized/",
            "$normalized/embed/",
            "$normalized/embed/captioned/",
        )

        for (page in pages) {
            try {
                val fetched = fetchInstagramPage(page)
                val mediaUrl = parseInstagramVideoUrl(fetched.html)
                if (!mediaUrl.isNullOrBlank()) {
                    return InstagramMedia(
                        url = mediaUrl,
                        referer = "$normalized/",
                        cookies = fetched.cookies,
                    )
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun fetchInstagramPage(pageUrl: String): FetchedPage {
        val connection = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 25_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", browserUserAgent)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Referer", "https://www.instagram.com/")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Instagram HTTP $code")
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val cookies = connection.headerFields
                .filterKeys { it?.equals("Set-Cookie", ignoreCase = true) == true }
                .values
                .flatten()
                .mapNotNull { raw -> raw.substringBefore(';').takeIf { it.contains('=') } }
                .joinToString("; ")
                .takeIf { it.isNotBlank() }
            return FetchedPage(html, cookies)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseInstagramVideoUrl(html: String): String? {
        val metaPatterns = listOf(
            Regex("""<meta[^>]+(?:property|name)=["']og:video(?::secure_url)?["'][^>]+content=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:video(?::secure_url)?["'][^>]*>""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+(?:property|name)=["']twitter:player:stream["'][^>]+content=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE),
        )
        for (pattern in metaPatterns) {
            pattern.find(html)?.groupValues?.getOrNull(1)?.let {
                val decoded = decodeWebValue(it)
                if (decoded.startsWith("http")) return decoded
            }
        }

        val jsonPatterns = listOf(
            Regex("\\\"video_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", RegexOption.IGNORE_CASE),
            Regex("\\\"contentUrl\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", RegexOption.IGNORE_CASE),
        )
        for (pattern in jsonPatterns) {
            pattern.find(html)?.groupValues?.getOrNull(1)?.let {
                val decoded = decodeWebValue(it)
                if (decoded.startsWith("http")) return decoded
            }
        }
        return null
    }

    private fun decodeWebValue(raw: String): String {
        var value = raw.replace("\\/", "/")
        value = Regex("\\\\u([0-9a-fA-F]{4})").replace(value) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun findDownloadedFile(workDir: File): File? {
        return workDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    !file.name.endsWith(".part") &&
                    !file.name.endsWith(".ytdl") &&
                    !file.name.endsWith(".json") &&
                    !file.name.endsWith(".m4a")
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
                    "uri" to publishedUri.toString(),
                    "needsAuth" to false,
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

    private fun downloadDirectMedia(
        rawUrl: String,
        workDir: File,
        referer: String? = null,
        cookieHeader: String? = null,
    ): File {
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 35_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", browserUserAgent)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
            if (!cookieHeader.isNullOrBlank()) setRequestProperty("Cookie", cookieHeader)
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

    private fun friendlyMessage(messageRaw: String): String {
        val message = messageRaw.trim()
        return when {
            message.contains("DRM", ignoreCase = true) ->
                "هذا الفيديو يستخدم DRM ولا يمكن تنزيل المحتوى المحمي بهذه التقنية."
            message.contains("Requested format is not available", ignoreCase = true) ->
                "لم يعثر المحرك على صيغة قابلة للتنزيل من هذا المصدر بعد فحص الصيغ المتاحة."
            message.contains("login", ignoreCase = true) && message.contains("Instagram", ignoreCase = true) ->
                "Instagram لم يسمح بالوصول المجهول لهذا الرابط من الشبكة الحالية. جُرّبت صفحة المنشور العامة ونسخة embed ومحرك Instagram بدون تسجيل دخول."
            message.contains("private", ignoreCase = true) ->
                "المحتوى خاص أو غير متاح للعامة."
            message.contains("Errno 101", ignoreCase = true) ||
                message.contains("Network is unreachable", ignoreCase = true) ||
                message.contains("No route to host", ignoreCase = true) ->
                "تعذر الوصول إلى خادم الفيديو من مسار الشبكة الحالي."
            message.contains("timed out", ignoreCase = true) ->
                "انتهت مهلة الاتصال بالخادم بعد عدة محاولات."
            else -> if (message.isBlank()) "تعذر تنزيل الرابط." else message.take(900)
        }
    }

    data class PythonRunResult(
        val success: Boolean,
        val error: String,
        val finalFile: String? = null,
        val videoFile: String? = null,
        val audioFile: String? = null,
    )

    data class InstagramMedia(val url: String, val referer: String, val cookies: String?)
    data class FetchedPage(val html: String, val cookies: String?)

    companion object {
        private const val browserUserAgent =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        private val pythonRunnerSource = """
import copy
import glob
import json
import os
import traceback
import yt_dlp


def _has_video(f):
    return bool(f.get('vcodec') and f.get('vcodec') != 'none')


def _has_audio(f):
    return bool(f.get('acodec') and f.get('acodec') != 'none')


def _score(f):
    return (
        int(f.get('height') or 0),
        int(f.get('fps') or 0),
        float(f.get('tbr') or 0),
        int(f.get('filesize') or f.get('filesize_approx') or 0),
    )


def _latest_file(workdir, prefix):
    files = [p for p in glob.glob(os.path.join(workdir, prefix + '.*'))
             if not p.endswith('.part') and not p.endswith('.ytdl')]
    return max(files, key=os.path.getmtime) if files else None


def _logger(logs):
    class Logger:
        def debug(self, msg): logs.append(str(msg))
        def info(self, msg): logs.append(str(msg))
        def warning(self, msg): logs.append(str(msg))
        def error(self, msg): logs.append(str(msg))
    return Logger()


def _hook(progress_callback, base, span, label):
    def hook(d):
        if progress_callback is None:
            return
        try:
            if d.get('status') == 'downloading':
                raw = float(str(d.get('_percent_str', '0')).strip().replace('%', '') or 0)
                pct = base + (raw * span / 100.0)
                eta = int(d.get('eta') or 0)
                progress_callback.onProgressUpdate(float(pct), eta, label)
            elif d.get('status') == 'finished':
                progress_callback.onProgressUpdate(float(base + span), 0, label)
        except Exception:
            pass
    return hook


def _download_exact(url, opts, fmt_id, outtmpl, logs, callback, base, span, label):
    local = copy.deepcopy(opts)
    local['format'] = str(fmt_id)
    local['outtmpl'] = outtmpl
    local['logger'] = _logger(logs)
    local['progress_hooks'] = [_hook(callback, base, span, label)]
    with yt_dlp.YoutubeDL(local) as ydl:
        code = ydl.download([url])
    if code:
        raise RuntimeError('yt-dlp exit code %s' % code)


def _download_youtube(url, output_template, opts, logs, callback):
    workdir = os.path.dirname(output_template)
    probe = copy.deepcopy(opts)
    probe.pop('format', None)
    probe['logger'] = _logger(logs)

    if callback is not None:
        callback.onProgressUpdate(1.0, 0, 'قراءة صيغ YouTube المتاحة...')

    with yt_dlp.YoutubeDL(probe) as ydl:
        info = ydl.extract_info(url, download=False)

    formats = [f for f in (info.get('formats') or []) if isinstance(f, dict)]
    combined = [f for f in formats if _has_video(f) and _has_audio(f)]

    if combined:
        chosen = max(combined, key=_score)
        _download_exact(
            url, opts, chosen.get('format_id'),
            os.path.join(workdir, 'video_single.%(ext)s'),
            logs, callback, 4.0, 92.0,
            'تنزيل فيديو YouTube...')
        final_file = _latest_file(workdir, 'video_single')
        if not final_file:
            raise RuntimeError('Combined format downloaded but output file was not found')
        return {
            'success': True,
            'final_file': final_file,
            'selected_format': str(chosen.get('format_id')),
            'height': int(chosen.get('height') or 0),
        }

    videos = [f for f in formats if _has_video(f) and not _has_audio(f)]
    audios = [f for f in formats if _has_audio(f) and not _has_video(f)]

    avc = [f for f in videos
           if f.get('ext') == 'mp4'
           and str(f.get('vcodec') or '').lower().startswith(('avc1', 'h264'))]
    aac = [f for f in audios
           if f.get('ext') in ('m4a', 'mp4')
           and str(f.get('acodec') or '').lower().startswith(('mp4a', 'aac'))]

    if not avc:
        avc = [f for f in videos if f.get('ext') == 'mp4']
    if not aac:
        aac = [f for f in audios if f.get('ext') in ('m4a', 'mp4')]

    if avc and aac:
        video = max(avc, key=_score)
        audio = max(aac, key=_score)
        _download_exact(
            url, opts, video.get('format_id'),
            os.path.join(workdir, 'video_only.%(ext)s'),
            logs, callback, 4.0, 72.0,
            'تنزيل صورة YouTube...')
        _download_exact(
            url, opts, audio.get('format_id'),
            os.path.join(workdir, 'audio_only.%(ext)s'),
            logs, callback, 76.0, 17.0,
            'تنزيل صوت YouTube...')
        video_file = _latest_file(workdir, 'video_only')
        audio_file = _latest_file(workdir, 'audio_only')
        if not video_file or not audio_file:
            raise RuntimeError('Separate YouTube streams downloaded but output files were not found')
        return {
            'success': True,
            'video_file': video_file,
            'audio_file': audio_file,
            'video_format': str(video.get('format_id')),
            'audio_format': str(audio.get('format_id')),
            'height': int(video.get('height') or 0),
        }

    raise RuntimeError('No downloadable combined format or Android-muxable MP4/AAC streams were found')


def _download_generic(url, output_template, opts, logs, callback):
    local = copy.deepcopy(opts)
    local.pop('format', None)
    local['outtmpl'] = output_template
    local['logger'] = _logger(logs)
    local['progress_hooks'] = [_hook(callback, 0.0, 100.0, 'تنزيل الفيديو...')]
    with yt_dlp.YoutubeDL(local) as ydl:
        code = ydl.download([url])
    if code:
        raise RuntimeError('yt-dlp exit code %s' % code)
    workdir = os.path.dirname(output_template)
    candidates = [p for p in glob.glob(os.path.join(workdir, '*'))
                  if os.path.isfile(p)
                  and not p.endswith('.part')
                  and not p.endswith('.ytdl')
                  and not p.endswith('.json')]
    if not candidates:
        raise RuntimeError('Download finished but output file was not found')
    return {'success': True, 'final_file': max(candidates, key=os.path.getmtime)}


def execute(url, output_template, options_json, progress_callback=None):
    opts = json.loads(options_json or '{}')
    logs = []
    try:
        host = (url.split('/')[2] if '://' in url else '').lower()
        if 'youtube.com' in host or 'youtu.be' in host:
            result = _download_youtube(url, output_template, opts, logs, progress_callback)
        else:
            result = _download_generic(url, output_template, opts, logs, progress_callback)
        result['log'] = '\n'.join(logs[-50:])
        return json.dumps(result, ensure_ascii=False)
    except Exception as exc:
        logs.append(traceback.format_exc())
        return json.dumps({
            'success': False,
            'error': str(exc),
            'log': '\n'.join(logs[-80:]),
        }, ensure_ascii=False)
""".trimIndent()
    }
}
